package com.tapreplay.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

/**
 * 无障碍服务核心：
 * 1. 用 dispatchGesture 回放/透传手势（注入的触摸会穿过无障碍悬浮层直达底层应用）；
 * 2. 持有悬浮控制层（录制面板 + 录制触摸捕获层）。
 */
public class MacroService extends AccessibilityService {

    public static MacroService instance;          // MainActivity 用它判断服务是否已开启

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

    public boolean isPlaying() {
        Thread t = playThread;
        return t != null && t.isAlive();
    }

    /** 回放整个宏（后台线程，可被 stopPlayback 打断）。 */
    public synchronized void playMacro(MacroModel.Macro m, Runnable onDone) {
        if (isPlaying()) return;
        if (m == null || m.actions.isEmpty()) {
            toast("这个宏是空的，先录制一段操作吧");
            return;
        }
        cancelled = false;
        playThread = new Thread(() -> {
            Point size = screenSize();
            float sx = size.x / (float) Math.max(1, m.width);
            float sy = size.y / (float) Math.max(1, m.height);
            for (MacroModel.Action a : m.actions) {
                if (cancelled || Thread.currentThread().isInterrupted()) break;
                dispatchAction(a, sx, sy);
                sleep(a.maxDuration());
                sleep(a.delayAfter);
            }
            ui.post(() -> { if (onDone != null) onDone.run(); });
        }, "macro-play");
        playThread.start();
    }

    public void stopPlayback() {
        cancelled = true;
        Thread t = playThread;
        if (t != null) t.interrupt();
    }

    /** 录制时的实时透传：用户抬手后立即把刚录下的手势注入底层应用，实现"边录边生效"。 */
    public void dispatchAction(MacroModel.Action a, float sx, float sy) {
        GestureDescription.Builder b = new GestureDescription.Builder();
        for (MacroModel.Stroke s : a.strokes) {
            if (s.pts.isEmpty()) continue;
            Path p = new Path();
            float[] f0 = s.pts.get(0);
            p.moveTo(f0[0] * sx, f0[1] * sy);
            for (int i = 1; i < s.pts.size(); i++) {
                float[] pi = s.pts.get(i);
                p.lineTo(pi[0] * sx, pi[1] * sy);
            }
            b.addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(1, s.duration)));
        }
        try {
            dispatchGesture(b.build(), null, null);
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ 工具

    Point screenSize() {
        DisplayMetrics dm = new DisplayMetrics();
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            //noinspection deprecation
            wm.getDefaultDisplay().getRealMetrics(dm);
        } catch (Exception e) {
            dm = getResources().getDisplayMetrics();
        }
        return new Point(dm.widthPixels, dm.heightPixels);
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
