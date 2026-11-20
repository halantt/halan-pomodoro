package com.flowtime.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * 专注计时前台服务：App 退到后台、锁屏时仍保持计时，
 * 倒计时/正计时到点后发出提醒（通知 + 震动）。
 */
public class FocusService extends Service {

    public static final String ACTION_START = "com.flowtime.app.FOCUS_START";
    public static final String ACTION_STOP = "com.flowtime.app.FOCUS_STOP";
    public static final String ACTION_ALERT = "com.flowtime.app.FOCUS_ALERT";

    private static final String CH_ONGOING = "focus_ongoing";
    private static final String CH_DONE = "focus_done";
    private static final int NOTI_ONGOING = 1001;
    private static final int NOTI_DONE = 1002;
    private static final int ALARM_REQ = 2001;

    private boolean countdown = true;
    private long endAt = 0L;
    private long startAt = 0L;
    private String title = "专注中";

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel on = new NotificationChannel(CH_ONGOING, "专注计时", NotificationManager.IMPORTANCE_LOW);
        on.setShowBadge(false);
        on.setSound(null, null);
        nm.createNotificationChannel(on);
        NotificationChannel done = new NotificationChannel(CH_DONE, "专注完成提醒", NotificationManager.IMPORTANCE_HIGH);
        done.enableVibration(true);
        nm.createNotificationChannel(done);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            cancelAlert();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_ALERT.equals(action)) {
            fireDone();
            return START_NOT_STICKY;
        }
        if (intent != null) {
            countdown = intent.getBooleanExtra("countdown", true);
            endAt = intent.getLongExtra("endAt", 0L);
            startAt = intent.getLongExtra("startAt", System.currentTimeMillis());
            String t = intent.getStringExtra("title");
            if (t != null && t.trim().length() > 0) title = t.trim();
        }
        startForeground(NOTI_ONGOING, buildOngoing());
        scheduleAlert();
        return START_NOT_STICKY;
    }

    private PendingIntent openAppIntent() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flag = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flag |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, 0, open, flag);
    }

    @SuppressWarnings("deprecation")
    private Notification buildOngoing() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CH_ONGOING);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle(title)
                .setContentText(countdown ? "倒计时进行中，切到后台也会继续" : "正计时进行中，切到后台也会继续")
                .setSmallIcon(getApplicationInfo().icon)
                .setContentIntent(openAppIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STOPWATCH);
        if (Build.VERSION.SDK_INT >= 24) {
            if (countdown && endAt > 0) {
                b.setUsesChronometer(true).setChronometerCountDown(true).setWhen(endAt);
            } else if (!countdown) {
                b.setUsesChronometer(true).setWhen(startAt);
            }
        }
        if (Build.VERSION.SDK_INT >= 21) b.setShowWhen(true);
        return b.build();
    }

    @SuppressWarnings("deprecation")
    private Notification buildDone() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CH_DONE);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("专注完成")
                .setContentText(title + " · 这一段结束了，起来活动一下")
                .setSmallIcon(getApplicationInfo().icon)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ALARM);
        return b.build();
    }

    /** 到点提醒用系统闹钟，锁屏/深度休眠也能准时触发 */
    private void scheduleAlert() {
        cancelAlert();
        if (endAt <= 0) return;
        Intent i = new Intent(this, FocusService.class).setAction(ACTION_ALERT);
        int flag = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flag |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getService(this, ALARM_REQ, i, flag);
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, pi);
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, endAt, pi);
            }
        } catch (SecurityException e) {
            // 精确闹钟权限被拒时退回不精确闹钟，仍能提醒
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endAt, pi);
            } catch (Exception ignored) {
            }
        }
    }

    private void cancelAlert() {
        Intent i = new Intent(this, FocusService.class).setAction(ACTION_ALERT);
        int flag = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flag |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getService(this, ALARM_REQ, i, flag);
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pi);
        pi.cancel();
    }

    private void fireDone() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTI_DONE, buildDone());
        vibrate();
        stopForeground(true);
        stopSelf();
    }

    @SuppressWarnings("deprecation")
    private void vibrate() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        long[] pattern = new long[]{0, 320, 220, 320};
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } else {
            v.vibrate(pattern, -1);
        }
    }

    @Override
    public void onDestroy() {
        cancelAlert();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
