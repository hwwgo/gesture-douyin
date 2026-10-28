package com.handsfree.douyin;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * ShortX DeepLink 执行器（v2.7 新增；v3.0 起成为唯一执行方式）。
 *
 * 背景：语音识别/硬件触发已稳定，但滑动执行需要 root 或无障碍服务。
 * ShortX（Xposed 自动化 App）已有成熟的 root 执行链路，
 * 且提供 shortx://run?id=xxx DeepLink 直接触发某条规则。
 * 因此本 App 只负责识别/触发，拉起这个 DeepLink，
 * 由 ShortX 完成 input swipe —— 本 App 不使用 root、不做页面验证。
 *
 * 注意：
 * - DeepLink 投递成功 ≠ 规则一定执行成功（ShortX 可能没启用/规则被删），
 *   失败由反馈层以「长震+低音+切换失败」提示；
 * - 从后台服务 startActivity 受系统「后台弹出界面」权限限制，
 *   需按使用指南给本 App 授权；
 * - 重新导入 ShortX 规则后 id 会变，可在 App 首页 DeepLink 输入框里改。
 */
public final class ShortX {

    /** 用户提供的默认规则 id（下一个） */
    public static final String DEFAULT_NEXT =
            "shortx://run?id=DA-1d634d7d-da36-49b8-a588-b3c3d00be499";

    /** 执行结果码 */
    public static final int OK = 0;        // DeepLink 已投递（直接）
    public static final int OK_OVERLAY = 3; // DeepLink 已投递（悬浮窗辅助通道）
    public static final int FAIL = 1;      // 无 App 响应 shortx:// scheme
    public static final int NO_PREV = 2;   // 未配置「上一个」的 DeepLink

    private ShortX() {
    }

    /** 「下一个」的 DeepLink（App 内可改） */
    public static String urlNext(Context c) {
        return pref(c, "shortx_url_next", DEFAULT_NEXT);
    }

    /** 「上一个」的 DeepLink；未配置时返回 null（调用方决定回退行为） */
    public static String urlPrev(Context c) {
        return pref(c, "shortx_url_prev", null);
    }

    /** 当前配置的 DeepLink 是否可被系统解析（ShortX 已安装且注册了 scheme） */
    public static boolean available(Context c) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(urlNext(c)));
            return i.resolveActivity(c.getPackageManager()) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 触发「下一个」规则（直接投递，供前台/测试用） */
    public static int runNext(Context c) {
        return fire(c, urlNext(c), false);
    }

    /** 触发「上一个」规则（直接投递，供前台/测试用）；未配置返回 NO_PREV */
    public static int runPrev(Context c) {
        String u = urlPrev(c);
        if (u == null || u.trim().isEmpty()) {
            return NO_PREV;
        }
        return fire(c, u, false);
    }

    /** 触发「下一个」规则；fromBackground=true 时优先走悬浮窗辅助通道 */
    public static int runNext(Context c, boolean fromBackground) {
        return fire(c, urlNext(c), fromBackground);
    }

    /** 触发「上一个」规则；fromBackground=true 时优先走悬浮窗辅助通道；未配置返回 NO_PREV */
    public static int runPrev(Context c, boolean fromBackground) {
        String u = urlPrev(c);
        if (u == null || u.trim().isEmpty()) {
            return NO_PREV;
        }
        return fire(c, u, fromBackground);
    }

    private static int fire(Context c, String url, boolean fromBackground) {
        if (url == null || url.trim().isEmpty()) {
            return FAIL;
        }
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // 后台场景：先尝试悬浮窗辅助（ColorOS 对持有悬浮窗权限/有可见 overlay 的 App 放行）
        if (fromBackground && OverlayLaunch.launch(c, i)) {
            return OK_OVERLAY;
        }
        try {
            c.startActivity(i);
            return OK;
        } catch (ActivityNotFoundException e) {
            return FAIL;
        } catch (Exception e) {
            // 后台弹出界面被系统拦截等不会抛异常，这里兜其余未知错误
            return FAIL;
        }
    }

    private static String pref(Context c, String key, String def) {
        try {
            String s = c.getSharedPreferences("hf", Context.MODE_PRIVATE)
                    .getString(key, def);
            return s;
        } catch (Exception e) {
            return def;
        }
    }

    /** 供 App 内自检：手动触发一次（Activity 上下文） */
    public static int testRun(Activity a) {
        return fire(a, urlNext(a), false);
    }
}
