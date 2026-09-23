package com.tapreplay.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 宏数据模型。
 * 一次录制 = 一个 Macro（版本）；Macro = 一串 Action；
 * 一个 Action = 同一时段的一组手指轨迹（多指并行）；单指轨迹 = Stroke（采样点 + 时长）。
 */
public final class MacroModel {

    /** 单指轨迹。 */
    public static final class Stroke {
        public final List<float[]> pts = new ArrayList<>();  // [x, y] 采样点
        public long duration;                                // 毫秒

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            JSONArray xs = new JSONArray(), ys = new JSONArray();
            for (float[] p : pts) { xs.put(p[0]); ys.put(p[1]); }
            o.put("xs", xs); o.put("ys", ys); o.put("dur", duration);
            return o;
        }

        static Stroke fromJson(JSONObject o) throws JSONException {
            Stroke s = new Stroke();
            JSONArray xs = o.getJSONArray("xs"), ys = o.getJSONArray("ys");
            for (int i = 0; i < xs.length(); i++)
                s.pts.add(new float[]{ (float) xs.getDouble(i), (float) ys.getDouble(i) });
            s.duration = Math.max(1, o.optLong("dur", 100));
            return s;
        }
    }

    /** 一次手势（可含多指并行轨迹）；delayAfter = 本手势结束到下一手势开始的真实间隔。
     *  globalAction != 0 时本动作是系统动作（AccessibilityService.GLOBAL_ACTION_*，如返回/主页），无轨迹。 */
    public static final class Action {
        public final List<Stroke> strokes = new ArrayList<>();
        public long delayAfter;
        public int globalAction;   // 0 = 触摸手势

        long maxDuration() {
            if (globalAction != 0) return 300;   // 系统动作给系统动画留时间
            long d = 0;
            for (Stroke s : strokes) d = Math.max(d, s.duration);
            return d;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            JSONArray arr = new JSONArray();
            for (Stroke s : strokes) arr.put(s.toJson());
            o.put("strokes", arr); o.put("gap", delayAfter);
            o.put("global", globalAction);
            return o;
        }

        static Action fromJson(JSONObject o) throws JSONException {
            Action a = new Action();
            JSONArray arr = o.getJSONArray("strokes");
            for (int i = 0; i < arr.length(); i++) a.strokes.add(Stroke.fromJson(arr.getJSONObject(i)));
            a.delayAfter = Math.max(0, o.optLong("gap", 0));
            a.globalAction = o.optInt("global", 0);
            return a;
        }
    }

    /** 一个宏（一个版本）。 */
    public static final class Macro {
        public String id, name;
        public long created, updated;
        public int width, height;   // 录制时屏幕分辨率：换设备/旋转后回放按比例缩放
        public int rotation;        // 录制时屏幕方向（Surface.ROTATION_*）
        public final List<Action> actions = new ArrayList<>();

        public int strokeCount() {
            int n = 0;
            for (Action a : actions) n += a.strokes.size();
            return n;
        }

        public long totalDuration() {
            long d = 0;
            for (Action a : actions) { d += a.maxDuration() + a.delayAfter; }
            return d;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id); o.put("name", name);
            o.put("created", created); o.put("updated", updated);
            o.put("width", width); o.put("height", height);
            o.put("rotation", rotation);
            o.put("format", 1);
            JSONArray arr = new JSONArray();
            for (Action a : actions) arr.put(a.toJson());
            o.put("actions", arr);
            return o;
        }

        public String toJsonString() {
            try { return toJson().toString(1); }
            catch (JSONException e) { return "{}"; }
        }

        public static Macro fromJson(String text) throws JSONException {
            JSONObject o = new JSONObject(text);
            Macro m = new Macro();
            m.id = o.optString("id");
            m.name = o.optString("name", "未命名宏");
            m.created = o.optLong("created");
            m.updated = o.optLong("updated");
            m.width = o.optInt("width", 1080);
            m.height = o.optInt("height", 1920);
            m.rotation = o.optInt("rotation", 0);
            JSONArray arr = o.getJSONArray("actions");
            for (int i = 0; i < arr.length(); i++)
                m.actions.add(Action.fromJson(arr.getJSONObject(i)));
            return m;
        }
    }

    private MacroModel() {}
}
