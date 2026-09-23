package com.tapreplay.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 无障碍服务核心 v2：
 * 1. dispatchGesture 回放（注入触摸会命中上层悬浮窗，故回放期间面板隐藏，见 OverlayController）；
 * 2. 回放坐标做旋转 + 分辨率变换并钳制到当前屏幕内；
 * 3. 回放开始/结束联动悬浮层状态。
 */
public class MacroService extends AccessibilityService {

    public static MacroService instance;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private OverlayController overlay;
    private MacroStore store;
    private volatile Thread playThread;
    private volatile boolean cancelled;

    @Override
    public void onServiceConnected() {
        instance = this;
        store = new MacroStore(this);
        overlay = new OverlayController(this, store);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stopPlayback();
        if (overlay != null) overlay.hideAll();
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { /* 不读取屏幕内容 */ }

    @Override
    public void onInterrupt() {
        stopPlayback();
    }

    // ------------------------------------------------------------ 对外入口

    public MacroStore store() { return store; }
    public OverlayController overlay() { return overlay; }

    public void showOverlay() {
        if (overlay != null) overlay.showPanel();
    }

    /** 悬浮条显示期间拉起保活前台服务（荣耀/MagicOS 后台管控激进）。 */
    void startKeepAlive() {
        KeepAliveService.start(this);
    }

    void stopKeepAlive() {
        KeepAliveService.stop(this);
    }

    public boolean isPlaying() {
        Thread t = playThread;
        return t != null && t.isAlive();
    }

    /** 回放整个宏（后台线程，可被 stopPlayback 打断）。 */
    public synchronized void playMacro(MacroModel.Macro m) {
        if (isPlaying()) return;
        if (m == null || m.actions.isEmpty()) {
            toast("这个宏是空的，先录制一段操作吧");
            return;
        }
        cancelled = false;
        ui.post(() -> overlay.onPlayStart());
        toast("回放「" + m.name + "」…");
        playThread = new Thread(() -> {
            Point size = screenSize();
            int delta = (displayRotation() - m.rotation + 4) % 4;
            List<MacroModel.Action> transformed = transform(m, size, delta);
            for (MacroModel.Action a : transformed) {
                if (cancelled || Thread.currentThread().isInterrupted()) break;
                dispatchAction(a);
                sleep(a.maxDuration());
                sleep(a.delayAfter);
            }
            ui.post(() -> {
                overlay.onPlayEnd();
                toast("回放结束");
            });
        }, "macro-play");
        playThread.start();
    }

    public void stopPlayback() {
        cancelled = true;
        Thread t = playThread;
        if (t != null) t.interrupt();
    }

    /** 把录制坐标按当前旋转/分辨率变换到注入坐标，并钳制到屏幕内。 */
    private List<MacroModel.Action> transform(MacroModel.Macro m, Point cur, int delta) {
        float rw = Math.max(1, m.width), rh = Math.max(1, m.height);
        float cw = cur.x, ch = cur.y;
        List<MacroModel.Action> out = new ArrayList<>();
        for (MacroModel.Action a : m.actions) {
            MacroModel.Action na = new MacroModel.Action();
            na.delayAfter = a.delayAfter;
            for (MacroModel.Stroke s : a.strokes) {
                MacroModel.Stroke ns = new MacroModel.Stroke();
                ns.duration = s.duration;
                for (float[] p : s.pts) {
                    float x = p[0], y = p[1], X, Y;
                    switch (delta) {
                        case Surface.ROTATION_90:
                            X = (rh - y) * cw / rh; Y = x * ch / rw; break;
                        case Surface.ROTATION_180:
                            X = (rw - x) * cw / rw; Y = (rh - y) * ch / rh; break;
                        case Surface.ROTATION_270:
                            X = y * cw / rh; Y = (rw - x) * ch / rw; break;
                        default:
                            X = x * cw / rw; Y = y * ch / rh; break;
                    }
                    X = Math.min(Math.max(X, 0), cw - 1);
                    Y = Math.min(Math.max(Y, 0), ch - 1);
                    ns.pts.add(new float[]{X, Y});
                }
                na.strokes.add(ns);
            }
            out.add(na);
        }
        return out;
    }

    /** 注入一个手势（多指并行轨迹同一个 GestureDescription，同时开始）。 */
    void dispatchAction(MacroModel.Action a) {
        dispatchAction(a, null);
    }

    /** 注入一个手势，可选在完成/取消时回调（用于录制反馈后精确恢复捕获层）。 */
    void dispatchAction(MacroModel.Action a, Runnable onComplete) {
        GestureDescription.Builder b = new GestureDescription.Builder();
        for (MacroModel.Stroke s : a.strokes) {
            if (s.pts.isEmpty()) continue;
            Path p = new Path();
            float[] f0 = s.pts.get(0);
            p.moveTo(f0[0], f0[1]);
            for (int i = 1; i < s.pts.size(); i++) {
                float[] pi = s.pts.get(i);
                p.lineTo(pi[0], pi[1]);
            }
            b.addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(1, s.duration)));
        }
        GestureDescription desc = b.build();
        GestureResultCallback cb = onComplete == null ? null : new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                onComplete.run();
            }
            @Override public void onCancelled(GestureDescription gestureDescription) {
                onComplete.run();
            }
        };
        try {
            dispatchGesture(desc, cb, null);
        } catch (Exception e) {
            if (onComplete != null) onComplete.run();
        }
    }

    /** 自测：在屏幕中央注入一个小方形手势并回调提示。用户能立刻判断注入在设备上是否有效。 */
    void testInject() {
        Point s = screenSize();
        float cx = s.x / 2f, cy = s.y / 2f, r = 160f;
        MacroModel.Action a = new MacroModel.Action();
        MacroModel.Stroke st = new MacroModel.Stroke();
        st.pts.add(new float[]{cx - r, cy - r});
        st.pts.add(new float[]{cx + r, cy - r});
        st.pts.add(new float[]{cx + r, cy + r});
        st.pts.add(new float[]{cx - r, cy + r});
        st.pts.add(new float[]{cx - r, cy - r});
        st.duration = 700;
        a.strokes.add(st);
        dispatchAction(a, () -> toast("手势注入完成（若刚看到小方形轨迹，说明注入正常）"));
    }

    // ------------------------------------------------------------ 工具

    Point screenSize() {
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                // Android 11+ 官方口径：最大窗口边界 = 物理屏尺寸（兼容折叠屏/多窗口）
                android.graphics.Rect b = wm.getMaximumWindowMetrics().getBounds();
                return new Point(b.width(), b.height());
            }
            DisplayMetrics dm = new DisplayMetrics();
            //noinspection deprecation
            wm.getDefaultDisplay().getRealMetrics(dm);
            return new Point(dm.widthPixels, dm.heightPixels);
        } catch (Exception e) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            return new Point(dm.widthPixels, dm.heightPixels);
        }
    }

    int displayRotation() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.view.Display d = getDisplay();   // Context#getDisplay (API 30+)
                if (d != null) return d.getRotation();
            }
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            //noinspection deprecation
            return wm.getDefaultDisplay().getRotation();
        } catch (Exception e) {
            return Surface.ROTATION_0;
        }
    }

    void toast(String s) {
        ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }

    private static void sleep(long ms) {
        if (ms > 0) {
            try { Thread.sleep(ms); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }
}
