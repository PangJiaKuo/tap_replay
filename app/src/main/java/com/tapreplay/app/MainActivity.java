package com.tapreplay.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 主页：授权入口 + 宏版本管理（列表 / 重命名 / 副本 / 导入导出 / 删除）。 */
public class MainActivity extends Activity {

    private static final int REQ_EXPORT = 41, REQ_IMPORT = 42;

    private MacroStore store;
    private List<MacroModel.Macro> macros;
    private BaseAdapter adapter;
    private TextView txtEmpty;
    private MacroModel.Macro exportTarget;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = new MacroStore(this);

        findViewById(R.id.btnAccessibility).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.btnOverlay).setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this))
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            else
                toast("悬浮窗权限已授予");
        });
        findViewById(R.id.btnPanel).setOnClickListener(v -> showFloatingPanel());
        findViewById(R.id.btnTest).setOnClickListener(v -> testInject());
        findViewById(R.id.btnImport).setOnClickListener(v -> importMacro());
        txtEmpty = findViewById(R.id.txtEmpty);
        // Android 13+（含 MagicOS 10 / Android 16）侧载应用的受限设置解除入口
        findViewById(R.id.txtRestricted).setOnClickListener(v -> openAppDetails());
        setupHonorSection();

        ListView list = findViewById(R.id.listMacros);
        adapter = new MacroAdapter();
        list.setAdapter(adapter);
        list.setEmptyView(txtEmpty);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        macros = store.loadAll();
        adapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------ 荣耀/MagicOS 适配

    private static final int REQ_NOTIF = 43;

    private void setupHonorSection() {
        TextView note = findViewById(R.id.txtHonorNote);
        boolean isHonor = "honor".equalsIgnoreCase(android.os.Build.MANUFACTURER);
        if (isHonor)
            note.setText("检测到荣耀设备（" + android.os.Build.MODEL + " / MagicOS "
                    + magicOsHint() + "）：请完成下面三项保活设置，否则悬浮条会被后台清理");

        findViewById(R.id.btnBattery).setOnClickListener(v -> {
            try {
                // 直接申请加入电池优化白名单（荣耀400/MagicOS 9 有效）
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                } catch (Exception e2) {
                    toast("请手动到 设置→应用→耗电管理 中允许本应用后台运行");
                }
            }
        });

        findViewById(R.id.btnHonorBg).setOnClickListener(v -> openHonorAutoStart());

        findViewById(R.id.btnKeepAlive).setOnClickListener(v -> {
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != getPackageManager().PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                return;
            }
            KeepAliveService.start(this);
            toast("保活服务已开启：状态栏出现「TapReplay 运行中」即生效");
        });
    }

    private String magicOsHint() {
        // MagicOS 10 = Android 16 底包；9 = Android 15。用 SDK 版本粗判显示
        int sdk = android.os.Build.VERSION.SDK_INT;
        if (sdk >= 36) return "10";
        if (sdk >= 35) return "9";
        return "";
    }

    private void openAppDetails() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
            toast("点右上角 ⋮ →「允许受限设置」，然后重开无障碍开关");
        } catch (Exception e) {
            toast("请手动打开 设置→应用→TapReplay");
        }
    }

    /** 荣耀自启动/后台运行入口：MagicOS 各版本路径不同，多候选降级。 */
    private void openHonorAutoStart() {
        String[][] candidates = {
                // MagicOS 8/9/10：手机管家 → 应用启动管理
                {"com.hihonor.systemmanager", "com.hihonor.systemmanager.optimize.process.ProtectActivity"},
                // 旧 Magic UI / 华为 EMUI
                {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
        };
        for (String[] c : candidates) {
            try {
                Intent i = new Intent();
                i.setClassName(c[0], c[1]);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            } catch (Exception ignored) { }
        }
        // 降级 1：直接拉手机管家首页（MagicOS 10 组件名可能变动，launch intent 最稳）
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.hihonor.systemmanager");
            if (i != null) {
                startActivity(i);
                toast("请在手机管家 → 应用启动管理 中允许本应用");
                return;
            }
        } catch (Exception ignored) { }
        // 降级 2：应用信息页
        openAppDetails();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(requestCode, perms, grants);
        if (requestCode == REQ_NOTIF) {
            if (grants.length > 0 && grants[0] == getPackageManager().PERMISSION_GRANTED) {
                KeepAliveService.start(this);
                toast("保活服务已开启");
            } else {
                toast("未授予通知权限，保活服务无法显示常驻通知（荣耀机型建议允许）");
            }
        }
    }

    // ------------------------------------------------------------ 悬浮条与回放

    private void showFloatingPanel() {
        if (MacroService.instance == null) {
            toast("请先在系统设置中开启 TapReplay 无障碍服务");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("请先授予悬浮窗权限");
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        MacroService.instance.showOverlay();
        toast("悬浮控制条已显示，切到目标应用操作吧");
        moveTaskToBack(true);
    }

    /** 注入自测：2 秒后在屏幕中央画一个小方形，用于判断设备是否拦截手势注入。 */
    private void testInject() {
        if (MacroService.instance == null) {
            toast("请先开启无障碍服务再测试");
            return;
        }
        toast("看屏幕中央：2 秒后注入一个小方形轨迹");
        moveTaskToBack(true);
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> MacroService.instance.testInject(), 2000);
    }

    /** 列表里的回放：3 秒倒计时，留时间切到目标应用。 */
    private void playWithCountdown(MacroModel.Macro m) {
        if (MacroService.instance == null) {
            toast("请先开启无障碍服务");
            return;
        }
        toast("3 秒后开始回放「" + m.name + "」，请切到目标应用");
        moveTaskToBack(true);
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> MacroService.instance.playMacro(m), 3000);
    }

    // ------------------------------------------------------------ 版本管理

    private void showMenu(MacroModel.Macro m, View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("重命名");
        menu.getMenu().add("复制为新版本");
        menu.getMenu().add("导出");
        menu.getMenu().add("删除");
        menu.setOnMenuItemClickListener(item -> {
            switch (String.valueOf(item.getTitle())) {
                case "重命名": renameMacro(m); break;
                case "复制为新版本":
                    store.duplicate(m);
                    refresh();
                    toast("已创建副本");
                    break;
                case "导出": exportMacro(m); break;
                case "删除":
                    new AlertDialog.Builder(this)
                            .setMessage("删除「" + m.name + "」？")
                            .setPositiveButton("删除", (d, w) -> {
                                store.delete(m.id);
                                refresh();
                            })
                            .setNegativeButton("取消", null)
                            .show();
                    break;
            }
            return true;
        });
        menu.show();
    }

    private void renameMacro(MacroModel.Macro m) {
        EditText input = new EditText(this);
        input.setText(m.name);
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("重命名宏")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        m.name = name;
                        store.save(m);
                        refresh();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void exportMacro(MacroModel.Macro m) {
        exportTarget = m;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, m.name.replaceAll("[\\\\/:*?\"<>|]", "_") + ".json");
        startActivityForResult(i, REQ_EXPORT);
    }

    private void importMacro() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (requestCode == REQ_EXPORT && exportTarget != null) {
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    store.exportTo(exportTarget, os);
                }
                toast("已导出");
                exportTarget = null;
            } else if (requestCode == REQ_IMPORT) {
                MacroModel.Macro m;
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    m = store.importFrom(is);
                }
                refresh();
                toast("已导入「" + m.name + "」");
            }
        } catch (Exception e) {
            toast("操作失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------ 列表

    private class MacroAdapter extends BaseAdapter {
        @Override public int getCount() { return macros == null ? 0 : macros.size(); }
        @Override public Object getItem(int i) { return macros.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View v, ViewGroup parent) {
            if (v == null) v = getLayoutInflater().inflate(R.layout.row_macro, parent, false);
            MacroModel.Macro m = macros.get(i);
            ((TextView) v.findViewById(R.id.txtName)).setText(m.name);
            String when = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(m.updated));
            String desc = m.actions.size() + " 个手势 · " + m.strokeCount() + " 段轨迹 · 约 "
                    + Math.max(1, m.totalDuration() / 1000) + " 秒 · 更新 " + when;
            ((TextView) v.findViewById(R.id.txtDesc)).setText(desc);
            Button play = v.findViewById(R.id.btnPlay);
            play.setOnClickListener(x -> playWithCountdown(m));
            Button more = v.findViewById(R.id.btnMore);
            more.setOnClickListener(x -> showMenu(m, more));
            return v;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
