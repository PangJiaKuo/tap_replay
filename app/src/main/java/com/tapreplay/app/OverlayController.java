package com.tapreplay.app;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
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
 * 悬浮层控制：控制条（录制/回放/选宏/关闭）+ 录制时的全屏触摸捕获层。
 * 两个窗口都是 TYPE_ACCESSIBILITY_OVERLAY：注入的手势会穿过它们直达底层应用，
 * 所以录制时可以"边录边生效"，回放也不会被自己的悬浮层截获。
 */
public class OverlayController {

    private final MacroService svc;
    private final MacroStore store;
    private final WindowManager wm;

    private View panel, capture;
    private Button btnRec, btnPlay, btnMacro;
    private TextView recHint;

    private boolean recording;
    private MacroModel.Macro current;                       // 正在录制的宏
    private MacroModel.Macro selected;                      // 面板选中的回放对象
    private MacroModel.Action lastAction;                   // 上一个手势（用于回填真实间隔）
    private long lastGestureEnd = -1;
    private final Map<Integer, List<float[]>> paths = new HashMap<>();
    private final Map<Integer, Long> downAt = new HashMap<>();
    private final List<MacroModel.Stroke> finishedStrokes = new ArrayList<>();

    public OverlayController(MacroService svc, MacroStore store) {
        this.svc = svc;
        this.store = store;
        this.wm = (WindowManager) svc.getSystemService(MacroService.WINDOW_SERVICE);
    }

    // ------------------------------------------------------------ 控制条

    @SuppressLint("ClickableViewAccessibility")
    public void showPanel() {
        if (panel != null) return;
        panel = LayoutInflater.from(svc).inflate(R.layout.overlay_panel, null);
        panel.setBackgroundColor(Color.TRANSPARENT);

        btnRec = panel.findViewById(R.id.btnRec);
        btnPlay = panel.findViewById(R.id.btnPlay);
        btnMacro = panel.findViewById(R.id.btnMacro);
        Button btnClose = panel.findViewById(R.id.btnClose);

        btnRec.setOnClickListener(v -> toggleRecord());
        btnPlay.setOnClickListener(v -> togglePlay());
        btnClose.setOnClickListener(v -> hideAll());
        btnMacro.setOnClickListener(v -> cycleMacro());
        refreshMacroButton();

        // 拖动控制条
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
                        return false;   // 不拦截按钮点击
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
        wm.addView(panel, lp);
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
        int i = all.indexOf(selected);
        selected = all.get((i + 1) % all.size());
        refreshMacroButton();
        svc.toast("已选中：" + selected.name);
    }

    private static String shortName(String n) {
        return n.length() <= 6 ? n : n.substring(0, 6) + "…";
    }

    // ------------------------------------------------------------ 录制

    private void toggleRecord() {
        if (recording) stopRecord();
        else startRecord();
    }

    private void startRecord() {
        Point size = svc.screenSize();
        current = new MacroModel.Macro();
        current.name = "宏 " + new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        current.width = size.x;
        current.height = size.y;
        lastAction = null;
        lastGestureEnd = -1;
        paths.clear();
        downAt.clear();
        recording = true;
        finishedStrokes.clear();
        btnRec.setText("⏹");
        btnPlay.setEnabled(false);
        addCaptureView();
        svc.toast("录制中：开始操作吧（触摸会同步生效）");
    }

    private void stopRecord() {
        recording = false;
        btnRec.setText("⏺");
        btnPlay.setEnabled(true);
        removeCaptureView();
        if (current != null && !current.actions.isEmpty()) {
            store.save(current);
            selected = current;
            refreshMacroButton();
            svc.toast("已保存「" + current.name + "」：" + current.actions.size() + " 个手势");
        } else {
            svc.toast("什么都没录到，已丢弃");
        }
        current = null;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void addCaptureView() {
        if (capture != null) return;
        capture = LayoutInflater.from(svc).inflate(R.layout.overlay_capture, null);
        recHint = capture.findViewById(R.id.recHint);
        capture.setOnTouchListener((v, e) -> { onCaptureTouch(e); return true; });
        WindowManager.LayoutParams lp = overlayParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT);
        wm.addView(capture, lp);
        // 控制条重建到捕获层之上（后加的窗口在上层）
        if (panel != null) {
            try { wm.removeView(panel); } catch (Exception ignored) { }
            panel = null;
        }
        showPanel();
        syncRecordButtons();   // 新按钮状态与录制中保持一致
    }

    private void removeCaptureView() {
        if (capture != null) {
            try { wm.removeView(capture); } catch (Exception ignored) { }
            capture = null;
            recHint = null;
        }
    }

    /** 录制层的核心：按手指（pointerId）累积轨迹，全部抬手后组成一个手势动作。 */
    private void onCaptureTouch(MotionEvent e) {
        long t = e.getEventTime();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int pid = e.getPointerId(e.getActionIndex());
                List<float[]> pts = new ArrayList<>();
                pts.add(new float[]{e.getX(e.getActionIndex()), e.getY(e.getActionIndex())});
                paths.put(pid, pts);
                downAt.put(pid, t);
                if (paths.size() == 1 && lastGestureEnd >= 0 && lastAction != null)
                    lastAction.delayAfter = t - lastGestureEnd;   // 回填与上一个手势的真实间隔
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    List<float[]> pts = paths.get(e.getPointerId(i));
                    if (pts == null) continue;
                    float x = e.getX(i), y = e.getY(i);
                    float[] last = pts.get(pts.size() - 1);
                    // 采样节流：位移 ≥ 4px 才记录，避免巨型滑动产生上千个点
                    float ddx = x - last[0], ddy = y - last[1];
                    if (ddx * ddx + ddy * ddy >= 16) pts.add(new float[]{x, y});
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                // 每指一条轨迹，先收进 finishedStrokes；全部抬手后才组成一个手势
                int pid = e.getPointerId(e.getActionIndex());
                List<float[]> pts = paths.remove(pid);
                Long d0 = downAt.remove(pid);
                if (pts != null && !pts.isEmpty()) {
                    MacroModel.Stroke s = new MacroModel.Stroke();
                    s.pts.addAll(pts);
                    s.duration = Math.max(1, t - (d0 != null ? d0 : t));
                    if (s.pts.size() == 1 && s.duration < 400) s.duration = 60;  // 快速点击 = 60ms 轻触
                    finishedStrokes.add(s);
                }
                if (paths.isEmpty()) finishGesture(t);
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                // 被打断：丢弃进行中的轨迹
                paths.clear();
                downAt.clear();
                finishedStrokes.clear();
                break;
            }
        }
        if (recHint != null)
            recHint.setText(paths.isEmpty() ? "● 录制中" : "● 录制中 " + paths.size() + " 指");
    }

    private void finishGesture(long upTime) {
        MacroModel.Action a = new MacroModel.Action();
        a.strokes.addAll(finishedStrokes);
        finishedStrokes.clear();
        lastGestureEnd = upTime;
        if (a.strokes.isEmpty() || current == null) return;
        current.actions.add(a);
        lastAction = a;
        // 实时透传：让底层应用同步响应这一次手势
        svc.dispatchAction(a, 1f, 1f);
    }

    private void syncRecordButtons() {
        if (btnRec != null) btnRec.setText(recording ? "⏹" : "⏺");
        if (btnPlay != null) btnPlay.setEnabled(!recording);
    }

    // ------------------------------------------------------------ 回放

    private void togglePlay() {
        if (svc.isPlaying()) {
            svc.stopPlayback();
            btnPlay.setText("▶");
            return;
        }
        if (selected == null) {
            List<MacroModel.Macro> all = store.loadAll();
            if (all.isEmpty()) {
                svc.toast("还没有宏，先点 ⏺ 录一个吧");
                return;
            }
            selected = all.get(0);
        }
        refreshMacroButton();
        btnPlay.setText("⏹");
        svc.toast("回放「" + selected.name + "」…");
        svc.playMacro(selected, () -> {
            if (btnPlay != null) btnPlay.setText("▶");
            svc.toast("回放结束");
        });
    }

    // ------------------------------------------------------------ 窗口工具

    private WindowManager.LayoutParams overlayParams(int w, int h) {
        return new WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
    }

    public void hideAll() {
        if (recording) stopRecord();
        removeCaptureView();
        if (panel != null) {
            try { wm.removeView(panel); } catch (Exception ignored) { }
            panel = null;
            btnRec = btnPlay = btnMacro = null;
        }
    }
}
