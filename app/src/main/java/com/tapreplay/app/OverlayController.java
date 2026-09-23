package com.tapreplay.app;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 悬浮层控制 v2。
 *
 * 关键认知：dispatchGesture 注入的触摸会命中"最上层的无障碍悬浮窗"。
 * 所以任何注入发生的时刻，面板必须整体隐藏，否则复刻的点击会砸中面板按钮
 * （v1 的"多出点击"根因）；录制时若做实时透传，注入会打回全屏捕获层形成
 * 自录自放死循环——因此录制采用盲录（注入只在回放阶段发生，录制零注入）。
 *
 * 状态机：IDLE(完整面板) → RECORDING(捕获层+防误触小条) / PLAYING(防误触小条)。
 */
public class OverlayController {

    private final MacroService svc;
    private final MacroStore store;
    private final WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private View panel, capture, chip;
    private TextView chipText;
    private Button btnRec, btnPlay, btnMacro;

    private boolean recording;
    private MacroModel.Macro current;                       // 正在录制的宏
    private MacroModel.Macro selected;                      // 面板选中的回放对象
    private MacroModel.Action lastAction;                   // 上一个手势（回填真实间隔）
    private long lastGestureEnd = -1;
    private long lastChipTap;                               // 录制小条双击判定
    private final Map<Integer, List<float[]>> paths = new HashMap<>();
    private final Map<Integer, Long> downAt = new HashMap<>();
    private final List<MacroModel.Stroke> finishedStrokes = new ArrayList<>();

    public OverlayController(MacroService svc, MacroStore store) {
        this.svc = svc;
        this.store = store;
        this.wm = (WindowManager) svc.getSystemService(MacroService.WINDOW_SERVICE);
    }

    // ------------------------------------------------------------ IDLE 面板

    @SuppressLint("ClickableViewAccessibility")
    public void showPanel() {
        if (panel != null || recording || svc.isPlaying()) return;
        panel = LayoutInflater.from(svc).inflate(R.layout.overlay_panel, null);
        panel.setBackgroundColor(Color.TRANSPARENT);

        btnRec = panel.findViewById(R.id.btnRec);
        btnPlay = panel.findViewById(R.id.btnPlay);
        btnMacro = panel.findViewById(R.id.btnMacro);
        Button btnClose = panel.findViewById(R.id.btnClose);

        btnRec.setOnClickListener(v -> startRecord());
        btnPlay.setOnClickListener(v -> startPlay());
        btnClose.setOnClickListener(v -> hideAll());
        btnMacro.setOnClickListener(v -> cycleMacro());
        refreshMacroButton();

        final WindowManager.LayoutParams lp = overlayParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT);
        lp.x = 0; lp.y = 120;
        panel.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = lp.x - e.getRawX(); dy = lp.y - e.getRawY();
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = (int) (e.getRawX() + dx);
                        lp.y = (int) (e.getRawY() + dy);
                        try { wm.updateViewLayout(panel, lp); } catch (Exception ignored) { }
                        return false;
                    default:
                        return false;
                }
            }
        });
        try { wm.addView(panel, lp); } catch (Exception e) { panel = null; }
        svc.startKeepAlive();
    }

    private void removePanel() {
        if (panel != null) {
            try { wm.removeView(panel); } catch (Exception ignored) { }
            panel = null;
            btnRec = btnPlay = btnMacro = null;
        }
    }

    private void refreshMacroButton() {
        if (selected == null) {
            List<MacroModel.Macro> all = store.loadAll();
            if (!all.isEmpty()) selected = all.get(0);
        }
        if (btnMacro != null)
            btnMacro.setText(selected == null ? "选择宏" : shortName(selected.name));
    }

    private void cycleMacro() {
        List<MacroModel.Macro> all = store.loadAll();
        if (all.isEmpty()) {
            svc.toast("还没有宏，先点 ⏺ 录一个吧");
            return;
        }
        int i = -1;
        if (selected != null)
            for (int k = 0; k < all.size(); k++)
                if (all.get(k).id.equals(selected.id)) { i = k; break; }
        selected = all.get((i + 1) % all.size());
        refreshMacroButton();
        svc.toast("已选中：" + selected.name);
    }

    private static String shortName(String n) {
        return n.length() <= 6 ? n : n.substring(0, 6) + "…";
    }

    // ------------------------------------------------------------ 防误触小条

    /** 录制/回放期间唯一保留的悬浮物：小条。面板隐藏，注入的触摸不会误伤按钮。 */
    private void showChip(boolean forPlayback) {
        removeChip();
        chip = LayoutInflater.from(svc).inflate(R.layout.overlay_chip, null);
        chipText = chip.findViewById(R.id.chipText);
        WindowManager.LayoutParams lp = overlayParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
        lp.y = 36;
        if (forPlayback) {
            chipText.setText("⏹ 回放中 · 长按停止");
            // 长按 600ms 停止：注入的短触/滑动不会误触发
            chip.setOnTouchListener(new View.OnTouchListener() {
                final Runnable stopper = () -> svc.stopPlayback();
                @Override public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            ui.postDelayed(stopper, 600);
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            ui.removeCallbacks(stopper);
                            return true;
                        default:
                            return true;
                    }
                }
            });
        } else {
            chipText.setText("● 录制中 0 个手势 · 双击结束");
            // 双击停止：注入的单次点击不会误触发
            chip.setOnClickListener(v -> {
                long t = System.currentTimeMillis();
                if (t - lastChipTap < 350) stopRecord();
                lastChipTap = t;
            });
        }
        try { wm.addView(chip, lp); } catch (Exception e) { chip = null; chipText = null; }
    }

    private void removeChip() {
        if (chip != null) {
            try { wm.removeView(chip); } catch (Exception ignored) { }
            chip = null;
            chipText = null;
        }
    }

    // ------------------------------------------------------------ 录制（盲录）

    private void startRecord() {
        Point size = svc.screenSize();
        current = new MacroModel.Macro();
        current.name = "宏 " + new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        current.width = size.x;
        current.height = size.y;
        current.rotation = svc.displayRotation();
        lastAction = null;
        lastGestureEnd = -1;
        paths.clear();
        downAt.clear();
        finishedStrokes.clear();
        recording = true;
        removePanel();          // 面板隐藏：之后的注入（回放验证）不会误触按钮
        addCaptureView();
        showChip(false);
        svc.toast("录制中：每次抬手后会自动回放刚做的手势，稍候再继续下一步");
    }

    private void stopRecord() {
        if (!recording) return;
        recording = false;
        removeCaptureView();
        removeChip();
        if (current != null && !current.actions.isEmpty()) {
            store.save(current);
            selected = current;
            svc.toast("已保存「" + current.name + "」：" + current.actions.size() + " 个手势，点 ▶ 验证");
        } else {
            svc.toast("什么都没录到，已丢弃");
        }
        current = null;
        showPanel();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void addCaptureView() {
        if (capture != null) return;
        capture = LayoutInflater.from(svc).inflate(R.layout.overlay_capture, null);
        capture.setOnTouchListener((v, e) -> { onCaptureTouch(e); return true; });
        WindowManager.LayoutParams lp = overlayParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT);
        try { wm.addView(capture, lp); } catch (Exception e) { capture = null; }
    }

    private void removeCaptureView() {
        if (capture != null) {
            try { wm.removeView(capture); } catch (Exception ignored) { }
            capture = null;
        }
    }

    /** 录制核心：绝对坐标（getRawX/Y）+ 高密度采样（2px）；多指各自成轨迹。 */
    private void onCaptureTouch(MotionEvent e) {
        long t = e.getEventTime();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int pid = e.getPointerId(e.getActionIndex());
                List<float[]> pts = new ArrayList<>();
                pts.add(new float[]{e.getRawX(e.getActionIndex()), e.getRawY(e.getActionIndex())});
                paths.put(pid, pts);
                downAt.put(pid, t);
                if (paths.size() == 1 && lastGestureEnd >= 0 && lastAction != null)
                    lastAction.delayAfter = t - lastGestureEnd;
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    List<float[]> pts = paths.get(e.getPointerId(i));
                    if (pts == null) continue;
                    float x = e.getRawX(i), y = e.getRawY(i);
                    float[] last = pts.get(pts.size() - 1);
                    float ddx = x - last[0], ddy = y - last[1];
                    if (ddx * ddx + ddy * ddy >= 4) {          // 2px 采样，保留曲线与末端速度
                        pts.add(new float[]{x, y});
                        if (pts.size() > 600) downsample(pts); // 安全上限
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int pid = e.getPointerId(e.getActionIndex());
                List<float[]> pts = paths.remove(pid);
                Long d0 = downAt.remove(pid);
                if (pts != null && !pts.isEmpty()) {
                    MacroModel.Stroke s = new MacroModel.Stroke();
                    s.pts.addAll(pts);
                    s.duration = Math.max(1, t - (d0 != null ? d0 : t));
                    if (s.pts.size() == 1 && s.duration < 50) s.duration = 60;  // 极短误触 → 标准轻点
                    finishedStrokes.add(s);
                }
                if (paths.isEmpty()) finishGesture(t);
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                paths.clear();
                downAt.clear();
                finishedStrokes.clear();
                break;
        }
    }

    private static void downsample(List<float[]> pts) {
        // 超上限时均匀抽稀一半，保持形状
        for (int i = pts.size() - 2; i > 0; i -= 2) pts.remove(i);
    }

    private void finishGesture(long upTime) {
        MacroModel.Action a = new MacroModel.Action();
        a.strokes.addAll(finishedStrokes);
        finishedStrokes.clear();
        lastGestureEnd = upTime;
        if (a.strokes.isEmpty() || current == null) return;
        current.actions.add(a);
        lastAction = a;
        updateChipCount();
        // 间隙注入：先撤捕获层再把刚录的手势回灌给应用——用户立刻看到应用的真实反馈，
        // 且注入发生在捕获层下线期间，不会打回捕获层形成自录自放循环。
        replayForFeedback(a);
    }

    private void updateChipCount() {
        if (chipText != null && current != null)
            chipText.setText("● 录制中 " + current.actions.size() + " 个手势 · 双击结束");
    }

    /** 录制反馈：撤捕获层 → 等窗口移除同步到输入系统 → 注入刚录的手势 → 播完恢复捕获层。 */
    private void replayForFeedback(MacroModel.Action a) {
        removeCaptureView();
        if (chipText != null) chipText.setText("↻ 回放刚录的手势…");
        final long dur = Math.max(60, a.maxDuration());
        // 关键：removeView 只从 WindowManager 摘窗，输入调度器要等下一次布局同步才知道窗口没了。
        // 立即注入会命中“正在消失的捕获层”（这就是上一版看不到反馈的原因）——先等 200ms。
        ui.postDelayed(() -> {
            if (!recording) return;
            svc.dispatchAction(a, () -> ui.postDelayed(this::restoreCapture, 80));
            // 回调在部分 ROM 上可能丢失：兜底按时长 + 1s 强制恢复
            ui.postDelayed(() -> {
                if (recording && capture == null) restoreCapture();
            }, dur + 1000);
        }, 200);
    }

    private void restoreCapture() {
        if (!recording || capture != null) return;
        removeChip();                   // 重排 z 序：捕获层在下、小条在上
        addCaptureView();
        showChip(false);
        updateChipCount();
    }

    // ------------------------------------------------------------ 回放

    private void startPlay() {
        if (selected == null) {
            List<MacroModel.Macro> all = store.loadAll();
            if (all.isEmpty()) {
                svc.toast("还没有宏，先点 ⏺ 录一个吧");
                return;
            }
            selected = all.get(0);
        }
        if (selected.actions.isEmpty()) {
            svc.toast("这个宏是空的");
            return;
        }
        refreshMacroButton();
        svc.playMacro(selected);   // 面板/小条切换由 onPlayStart/onPlayEnd 处理
    }

    /** 回放开始（由 MacroService 调用）：隐藏一切按钮，只留防误触停止条。 */
    public void onPlayStart() {
        removePanel();
        showChip(true);
    }

    /** 回放结束/中断（由 MacroService 调用）：恢复完整面板。
     *  延迟 120ms：等回放线程彻底退出，showPanel 的 isPlaying 守卫才不会误判。 */
    public void onPlayEnd() {
        ui.postDelayed(() -> {
            removeChip();
            showPanel();
        }, 120);
    }

    // ------------------------------------------------------------ 窗口工具

    private WindowManager.LayoutParams overlayParams(int w, int h) {
        return new WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
    }

    public void hideAll() {
        if (recording) {
            recording = false;
            removeCaptureView();
            current = null;
        }
        removeChip();
        removePanel();
        svc.stopKeepAlive();
    }
}
