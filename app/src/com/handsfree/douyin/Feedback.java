package com.handsfree.douyin;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * v4.5：反馈层**只剩震动**（提示音 ToneGenerator、语音播报 TTS 已按需求删除）。
 *
 * 四个事件用不同的震动节奏区分：
 *   EV_LISTENING   开始识别        双短震
 *   EV_RECOGNIZED  识别到动作      两短震
 *   EV_TRIGGERED   DeepLink 已拉起  单击
 *   EV_FAIL        拉起失败        长震组
 *
 * 震动只由「成功识别动作」及其后续动作触发，环境变化不会让手机嗡嗡响。
 */
public final class Feedback {

    public static final int EV_LISTENING = 1;
    public static final int EV_RECOGNIZED = 2;
    public static final int EV_TRIGGERED = 3;
    public static final int EV_FAIL = 4;

    private static final String PREF = "hf";
    private static final String K_VIB = "fb_vibrate";

    /** 缓存 Vibrator 与 hasVibrator() 结果——避免每次触发都做一次 binder 调用 */
    private static Vibrator vib;
    private static boolean hasVib = false;

    private Feedback() {
    }

    public static void init(Context c) {
        if (vib != null || c == null) {
            return;
        }
        try {
            Context app = c.getApplicationContext();
            vib = (Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
            hasVib = vib != null && vib.hasVibrator();
        } catch (Throwable t) {
            vib = null;
            hasVib = false;
        }
    }

    public static void release() {
        vib = null;
        hasVib = false;
    }

    public static void play(Context c, int ev) {
        if (c == null) {
            return;
        }
        try {
            SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            if (!sp.getBoolean(K_VIB, true)) {
                return;
            }
        } catch (Exception e) {
            return;
        }
        if (vib == null) {
            init(c);
        }
        if (!hasVib || vib == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                long[] p;
                switch (ev) {
                    case EV_LISTENING:
                        p = new long[]{0, 40, 60, 40}; // 双短震：就绪
                        break;
                    case EV_RECOGNIZED:
                        p = new long[]{0, 50, 50, 50}; // 两短震：识别到动作
                        break;
                    case EV_TRIGGERED:
                        p = new long[]{0, 25}; // 单击：DeepLink 已拉起
                        break;
                    case EV_FAIL:
                        p = new long[]{0, 150, 100, 150}; // 长震组：失败
                        break;
                    default:
                        p = new long[]{0, 40};
                        break;
                }
                vib.vibrate(VibrationEffect.createWaveform(p, -1));
            } else {
                vib.vibrate(ev == EV_FAIL ? 250 : 60);
            }
        } catch (Throwable ignore) {
        }
    }
}
