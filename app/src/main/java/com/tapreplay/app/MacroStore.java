package com.tapreplay.app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** 宏版本持久化：应用私有目录 macros/<id>.json，一宏一文件。 */
public final class MacroStore {

    private final File dir;

    public MacroStore(Context ctx) {
        dir = new File(ctx.getFilesDir(), "macros");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    public List<MacroModel.Macro> loadAll() {
        List<MacroModel.Macro> out = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files != null) {
            for (File f : files) {
                try {
                    MacroModel.Macro m = MacroModel.Macro.fromJson(readAll(f));
                    if (m.id == null || m.id.isEmpty())
                        m.id = f.getName().replace(".json", "");
                    out.add(m);
                } catch (Exception ignored) { /* 跳过损坏文件 */ }
            }
        }
        out.sort(Comparator.comparingLong((MacroModel.Macro m) -> m.updated).reversed());
        return out;
    }

    public void save(MacroModel.Macro m) {
        if (m.id == null || m.id.isEmpty()) m.id = UUID.randomUUID().toString().substring(0, 8);
        m.updated = System.currentTimeMillis();
        if (m.created == 0) m.created = m.updated;
        try (FileWriter w = new FileWriter(new File(dir, m.id + ".json"))) {
            w.write(m.toJsonString());
        } catch (Exception ignored) { }
    }

    public void delete(String id) {
        //noinspection ResultOfMethodCallIgnored
        new File(dir, id + ".json").delete();
    }

    /** 复制为新版本（保留原宏，另存一份带“副本”后缀的新 id）。 */
    public MacroModel.Macro duplicate(MacroModel.Macro src) {
        MacroModel.Macro copy;
        try {
            copy = MacroModel.Macro.fromJson(src.toJsonString());
        } catch (Exception e) {
            return src;
        }
        copy.id = UUID.randomUUID().toString().substring(0, 8);
        copy.name = src.name + " 副本";
        copy.created = copy.updated = System.currentTimeMillis();
        save(copy);
        return copy;
    }

    public void exportTo(MacroModel.Macro m, OutputStream os) throws Exception {
        os.write(m.toJsonString().getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    public MacroModel.Macro importFrom(InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        MacroModel.Macro m = MacroModel.Macro.fromJson(sb.toString());
        m.id = UUID.randomUUID().toString().substring(0, 8);  // 避免覆盖同名版本
        save(m);
        return m;
    }

    private static String readAll(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
