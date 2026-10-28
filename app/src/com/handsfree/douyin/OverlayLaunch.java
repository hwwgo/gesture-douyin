package com.handsfree.douyin;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;

/**
 * 悬浮窗辅助启动（v3.1 新增，仅 DeepLink 通道使用）。
 *
 * ColorOS 上，后台服务直接 startActivity 拉 shortx:// 会被静默拦截
 * （不抛异常，只是 ShortX 收不到）。持有「悬浮窗」权限时，
 * 先挂一个 1x1 的不可见 overlay 窗口再 startActivity——
 * 要么系统因 overlay 可见窗口放行，要么 ColorOS 因悬浮窗权限放行，两者至少命中一个。
 *
 * 注意：addView 需要 Looper，调用方须保证在主线程（Trigger 已负责切主线程）。
 */
public final class OverlayLaunch {

    /** 是否已授予悬浮窗权限 */
    public static boolean canOverlay(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    /** 去设置里授权悬浮窗的 Intent */
    public static android.content.Intent grantIntent(Context c) {
        return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + c.getPackageName()));
    }

    /**
     * 带 overlay 辅助的后台启动。
     *
     * @return true=已通过悬浮窗通道投递；false=无悬浮窗权限或挂窗失败（调用方自行直接启动）
     */
    public static boolean launch(Context c, Intent intent) {
        if (Looper.myLooper() == null) {
            return false; // 非主线程不冒险，让调用方直接启动
        }
        if (!canOverlay(c)) {
            return false;
        }
        try {
            final WindowManager wm =
                    (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                return false;
            }
            final View v = new View(c);
            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    1, 1, type,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            wm.addView(v, lp);
            boolean started;
            try {
                c.startActivity(intent);
                started = true;
            } finally {
                final WindowManager wmF = wm;
                final View vF = v;
                // 延迟移除，给系统处理启动的时间
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            wmF.removeView(vF);
                        } catch (Exception ignore) {
                        }
                    }
                }, 800);
            }
            return started;
        } catch (Exception e) {
            return false;
        }
    }

    private OverlayLaunch() {
    }
}
