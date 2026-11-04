package edu.mocklab;

import android.app.*;
import android.content.Intent;
import android.location.*;
import android.os.*;

/** Continuous, explicitly visible educational test provider. */
public final class MockStreamService extends Service {
    private static final String PROVIDER = "edu_mock_lab_stream";
    public static volatile boolean running;
    public static volatile String status = "Поток остановлен.";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LocationManager manager;
    private PowerManager.WakeLock wakeLock;
    private boolean owned;
    private double latitude, longitude;
    private float accuracy;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try {
                Location point = new Location(PROVIDER);
                point.setLatitude(latitude);
                point.setLongitude(longitude);
                point.setAccuracy(accuracy);
                point.setTime(System.currentTimeMillis());
                point.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                manager.setTestProviderLocation(PROVIDER, point);
                handler.postDelayed(this, 1000);
            } catch (RuntimeException error) {
                status = "Поток остановлен: Android отказал в подаче тестовой позиции.";
                stopSelf();
            }
        }
    };

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null || "stop".equals(intent.getAction())) {
            status = "Поток остановлен пользователем.";
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("stream",
                "Тестовые координаты", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, MockStreamService.class).setAction("stop"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 2,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        startForeground(1, new Notification.Builder(this, "stream")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Mock Location Lab — поток включён")
                .setContentText("Учебные координаты каждую секунду · Стоп для завершения")
                .setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Стоп", stop).build()).build());
        latitude = intent.getDoubleExtra("lat", Double.NaN);
        longitude = intent.getDoubleExtra("lon", Double.NaN);
        accuracy = intent.getFloatExtra("accuracy", Float.NaN);
        if (!Double.isFinite(latitude) || Math.abs(latitude) > 90
                || !Double.isFinite(longitude) || Math.abs(longitude) > 180
                || !Float.isFinite(accuracy) || accuracy <= 0) {
            status = "Некорректная тестовая позиция.";
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            manager = getSystemService(LocationManager.class);
            if (!owned) {
                manager.addTestProvider(PROVIDER, false, false, false, false,
                        false, false, false, Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
                owned = true;
                manager.setTestProviderEnabled(PROVIDER, true);
            }
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager.class).newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "mocklab:stream");
                wakeLock.acquire();
            }
            running = true;
            status = "Фоновый поток 1 Гц включён. Остановка — кнопкой Стоп здесь или в уведомлении.";
            handler.removeCallbacks(tick);
            handler.post(tick);
        } catch (RuntimeException error) {
            status = "Запуск отклонён Android. Проверьте mock-app и точное местоположение.";
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (owned && manager != null) {
            try { manager.removeTestProvider(PROVIDER); }
            catch (RuntimeException error) {
                status += " Не удалось удалить провайдер; снимите выбор mock-приложения в настройках.";
            }
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
