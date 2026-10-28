package com.handsfree.douyin;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * 语音口令 → 翻页执行触发器。
 *
 * v3.0：App 删除全部 root 功能，执行交给 ShortX。
 * v4.4：**只保留 ShortX DeepLink 通道**（按用户要求删除通知触发）。
 * 拉起 shortx://run?id=xxx；后台时优先走悬浮窗辅助
 * （需 ShortX 已装已启用 + 后台弹出界面/悬浮窗权限）。
 *
 * 反馈时机（对应用户要求“成功识别了才震动”）：
 *   - 识别成功：识别服务先播 EV_RECOGNIZED（两短震+提示音+播报口令）
 *   - 命令投递成功：本类播 EV_TRIGGERED（单击，表示已发给 ShortX）
 *   - 投递失败（DeepLink 无响应/上一个没配链接）：EV_FAIL（长震+低音）
 */
public final class Trigger {

    public interface Listener {
        void onVerdict(String text);
    }

    /** App 是否在前台（MainActivity onResume/onPause 维护）——决定 DeepLink 是否走悬浮窗辅助 */
    public static volatile boolean appVisible = false;

    private Trigger() {
    }

    public static void next(Context c, String source, Listener l) {
        fire(c, true, source, l);
    }

    public static void prev(Context c, String source, Listener l) {
        fire(c, false, source, l);
    }

    private static void fire(final Context c, final boolean isNext, final String source, final Listener l) {
        // overlay 辅助需要主线程（WindowManager.addView）；其余路径无所谓，统一切主线程省得判
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    fireInner(c, isNext, source, l);
                }
            });
        } else {
            fireInner(c, isNext, source, l);
        }
    }

    private static void fireInner(Context c, boolean isNext, String source, Listener l) {
        fireDeepLink(c, isNext, source, l);
    }

    /** DeepLink 通道：后台优先悬浮窗辅助，前台直接拉起 */
    private static void fireDeepLink(Context c, boolean isNext, String source, Listener l) {
        int r = isNext ? ShortX.runNext(c, !appVisible) : ShortX.runPrev(c, !appVisible);
        if (r == ShortX.OK || r == ShortX.OK_OVERLAY) {
            EventLog.add("已拉起 ShortX DeepLink（" + source + "）"
                    + (r == ShortX.OK_OVERLAY ? "（悬浮窗辅助通道）" : "")
                    + "：" + (isNext ? ShortX.urlNext(c) : ShortX.urlPrev(c)));
            Feedback.play(c, Feedback.EV_TRIGGERED);
            verdict(l, "已触发 ShortX 规则（" + source + "）——抖音应已翻页");
            return;
        }
        if (r == ShortX.NO_PREV) {
            EventLog.add("触发失败（" + source + "）：未配置「上一个」的 DeepLink");
            Feedback.play(c, Feedback.EV_FAIL);
            verdict(l, "未配置「上一个」的 ShortX DeepLink——请在 App 首页补齐上一个规则的链接");
            return;
        }
        EventLog.add("触发失败（" + source + "）：ShortX 未响应 shortx://");
        Feedback.play(c, Feedback.EV_FAIL);
        verdict(l, "ShortX 未响应：确认已安装启用、规则已导入，且本 App 已获"
                + "「后台弹出界面」/「悬浮窗」权限（设置→应用→本 App→权限）");
    }

    private static void verdict(Listener l, String text) {
        if (l != null) {
            try {
                l.onVerdict(text);
            } catch (Exception ignore) {
            }
        }
    }
}
