package com.handsfree.douyin;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局崩溃捕获：任何线程的未捕获异常都写入 crash-*.log，
 * 否则用户只看到"闪退"，无法定位（Vosk 引擎 dlopen 失败等场景）。
 */
public class HandsFreeApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // debug 包（build.sh debug）把事件日志同步到 logcat，正式版保持安静
        boolean dbg = (getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        EventLog.setLogcat(dbg);

        final Context ctx = this;
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            private final Thread.UncaughtExceptionHandler def =
                    Thread.getDefaultUncaughtExceptionHandler();

            @Override
            public void uncaughtException(Thread t, Throwable e) {
                writeCrashLog(ctx, t, e);
                if (def != null) {
                    def.uncaughtException(t, e);
                } else {
                    System.exit(2);
                }
            }
        });
    }

    static void writeCrashLog(Context ctx, Thread t, Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) {
                dir = ctx.getFilesDir();
            }
            File f = new File(dir, "crash-" + stamp + ".log");
            FileWriter w = new FileWriter(f);
            w.write("时间: " + stamp + "\n");
            w.write("线程: " + t.getName() + "\n");
            w.write("设备: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                    + " / Android " + android.os.Build.VERSION.RELEASE
                    + " (API " + android.os.Build.VERSION.SDK_INT + ")\n");
            w.write("原生库目录: " + ctx.getApplicationInfo().nativeLibraryDir + "\n\n");
            w.write(sw.toString());
            w.close();
            Log.e("HandsFreeApp", "崩溃日志已写入: " + f.getAbsolutePath(), e);
        } catch (Throwable ignore) {
        }
    }
}
