package com.handsfree.douyin;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * 事件日志：App 内可见的最近事件流水（听到什么、识别成什么、注入结果）。
 * 洗碗时顾不上看屏幕，事后打开 App 就能回放"刚才到底发生了什么"，
 * 也是反馈诊断的第一手材料。
 */
public final class EventLog {

    private static final int MAX = 40;
    private static final LinkedList<String> events = new LinkedList<String>();
    private static final SimpleDateFormat fmt =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    /** debug 包才把事件同步到 logcat（`adb logcat -s GestureDouyin`），release 不输出 */
    private static volatile boolean toLogcat;

    private EventLog() {
    }

    public static void setLogcat(boolean on) {
        toLogcat = on;
    }

    public static void add(String text) {
        String line = fmt.format(new Date()) + "  " + text;
        if (toLogcat) {
            android.util.Log.d("GestureDouyin", line);
        }
        synchronized (events) {
            events.addFirst(line);
            while (events.size() > MAX) {
                events.removeLast();
            }
        }
    }

    /** 最近事件（最新在上），用于 App 内展示 */
    public static String dump() {
        synchronized (events) {
            if (events.isEmpty()) {
                return "（暂无事件）";
            }
            StringBuilder sb = new StringBuilder();
            for (String s : events) {
                sb.append(s).append('\n');
            }
            return sb.toString();
        }
    }

    public static List<String> snapshot() {
        synchronized (events) {
            return new LinkedList<String>(events);
        }
    }

    public static void clear() {
        synchronized (events) {
            events.clear();
        }
    }
}
