package edu.mocklab;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/** Foreground-only lab: uses a named test provider, never replaces gps/network/fused. */
public final class MainActivity extends Activity {
    private static final String PROVIDER = "edu_mock_lab";
    private static final int PERMISSION_REQUEST = 8;
    private static final int SINGLE = 1;
    private static final int STREAM = 2;
    private static final int INK = Color.rgb(24, 37, 54);
    private static final int MUTED = Color.rgb(82, 101, 119);
    private static final int ACCENT = Color.rgb(25, 93, 158);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LocationManager locationManager;
    private EditText latitude;
    private EditText longitude;
    private EditText accuracy;
    private TextView session;
    private TextView observation;
    private TextView decision;
    private Button singleButton;
    private Button streamButton;
    private Button stopButton;
    private boolean notificationAsked;
    private boolean viewingStream;
    private boolean active;
    private final Runnable streamStatus = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (viewingStream || MockStreamService.running) session.setText(MockStreamService.status);
            updateControls();
            handler.postDelayed(this, 500);
        }
    };
    private boolean providerAdded;
    private boolean resumed;
    private boolean permissionRequestInFlight;
    private int mode;
    private int pendingMode;
    private int received;
    private double activeLatitude;
    private double activeLongitude;
    private float activeAccuracy;
    private long sessionStartNanos;

    private final Runnable timeout = new Runnable() {
        @Override public void run() {
            if (!active || received != 0) return;
            finishSession("ОС не прислала координаты за 8 секунд. Проверьте доступ к точной "
                    + "геопозиции и системный переключатель местоположения.");
        }
    };

    private final Runnable publish = new Runnable() {
        @Override public void run() {
            if (!active) return;
            try {
                Location point = new Location(PROVIDER);
                point.setLatitude(activeLatitude);
                point.setLongitude(activeLongitude);
                point.setAccuracy(activeAccuracy);
                point.setTime(System.currentTimeMillis());
                point.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                locationManager.setTestProviderLocation(PROVIDER, point);
                // Do not inspect point.isMock(): only the framework's returned fix is evidence.
                if (received == 0) {
                    session.setText("Тестовая точка отправлена. Ожидаем callback от Android…");
                }
                if (mode == STREAM && active) handler.postDelayed(this, 1000L);
            } catch (SecurityException exception) {
                finishSession("Android запретил тестовый провайдер. Выберите Mock Location Lab "
                        + "в пункте «Приложение для фиктивных местоположений» и разрешите геопозицию.");
            } catch (IllegalArgumentException | IllegalStateException exception) {
                finishSession("Провайдер стал недоступен. Остановите сессию, проверьте настройки "
                        + "местоположения и запустите её заново.");
            }
        }
    };

    private final LocationListener listener = new LocationListener() {
        @Override public void onLocationChanged(Location fix) {
            if (!active || !PROVIDER.equals(fix.getProvider())
                    || fix.getElapsedRealtimeNanos() < sessionStartNanos) return;
            received++;
            handler.removeCallbacks(timeout);
            boolean mocked = isMock(fix);
            long ageMillis = Math.floorDiv(
                    SystemClock.elapsedRealtimeNanos() - fix.getElapsedRealtimeNanos(), 1_000_000L);
            LocationPolicy.Result result = LocationPolicy.evaluate(fix.getLatitude(),
                    fix.getLongitude(), fix.hasAccuracy() ? fix.getAccuracy() : Float.NaN,
                    ageMillis, mocked);
            observation.setText(String.format(Locale.US,
                    "Получено от Android · %d\n\nПровайдер: %s\nШирота: %.6f\nДолгота: %.6f"
                            + "\nТочность: %.1f м\nВозраст: %d мс\nisMock: %s\nAPI: %s",
                    received, fix.getProvider(), fix.getLatitude(), fix.getLongitude(),
                    fix.getAccuracy(), ageMillis, mocked ? "true" : "false",
                    Build.VERSION.SDK_INT >= 31 ? "Location.isMock()" : "Location.isFromMockProvider()"));
            decision.setText("Проверка правдоподобия: "
                    + (result.plausible ? "пройдена" : "не пройдена")
                    + "\nПроверка с учётом isMock: "
                    + (result.accepted ? "принято" : "отклонено")
                    + "\n\n" + result.reason);
            decision.setTextColor(result.accepted ? Color.rgb(21, 113, 77) : Color.rgb(149, 63, 25));
            if (mode == SINGLE) {
                finishSession("Точка получена от Android. Однократная сессия завершена; "
                        + "ниже сохранён её результат.");
            } else {
                session.setText("Поток работает: 1 точка в секунду. Получено: " + received + ".");
            }
        }

        @Override public void onProviderDisabled(String provider) {
            if (active && PROVIDER.equals(provider)) {
                finishSession("Провайдер отключён системой. Проверьте включение местоположения.");
            }
        }
        @Override public void onProviderEnabled(String provider) { }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        buildScreen();
        if (state != null) {
            latitude.setText(state.getString("latitude", "43.238949"));
            longitude.setText(state.getString("longitude", "76.889709"));
            accuracy.setText(state.getString("accuracy", "5"));
            providerAdded = state.getBoolean("providerCleanupPending", false);
            session.setText("Сессия остановлена при смене экрана. Для нового измерения нажмите кнопку.");
        }
    }

    private void buildScreen() {
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.rgb(244, 247, 251));
        int systemUi = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else systemUi |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        getWindow().getDecorView().setSystemUiVisibility(systemUi);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(244, 247, 251));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(24), dp(20), dp(24));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else {
                int left = insets.getSystemWindowInsetLeft();
                int top = insets.getSystemWindowInsetTop();
                int right = insets.getSystemWindowInsetRight();
                int bottom = insets.getSystemWindowInsetBottom();
                if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                    left = Math.max(left, insets.getDisplayCutout().getSafeInsetLeft());
                    top = Math.max(top, insets.getDisplayCutout().getSafeInsetTop());
                    right = Math.max(right, insets.getDisplayCutout().getSafeInsetRight());
                    bottom = Math.max(bottom, insets.getDisplayCutout().getSafeInsetBottom());
                }
                view.setPadding(left, top, right, bottom);
            }
            return insets;
        });
        setContentView(scroll);

        text(content, "ANDROID · УЧЕБНЫЙ СТЕНД", 12, ACCENT, true);
        text(content, "Mock Location Lab", 28, INK, true);
        text(content, "Создайте тестовую позицию и посмотрите, как её видит детектор.", 16, MUTED, false);

        LinearLayout setup = card(content);
        text(setup, "1. Подготовка", 18, INK, true);
        text(setup, "Включите местоположение. В настройках разработчика выберите Mock Location Lab "
                + "как приложение для фиктивных местоположений. При запуске разрешите точную геопозицию.",
                14, MUTED, false);
        button(setup, "Настройки разработчика", view -> openDeveloperSettings());
        text(setup, "Работает только отдельный учебный провайдер edu_mock_lab. Он не заменяет gps, "
                + "network или fused. Одиночный тест завершается при сворачивании. Фоновый поток работает до кнопки Стоп.", 13, MUTED, false);

        LinearLayout coordinates = card(content);
        text(coordinates, "2. Тестовая точка", 18, INK, true);
        latitude = field(coordinates, "Широта · от −90 до 90", "43.238949");
        longitude = field(coordinates, "Долгота · от −180 до 180", "76.889709");
        accuracy = field(coordinates, "Точность · метры, больше 0", "5");
        text(coordinates, "Начальные координаты — пример для Алматы, а не ваша геопозиция.", 12, MUTED, false);
        button(coordinates, "Пример с плохой точностью: 500 м", view -> {
            if (!active) accuracy.setText("500");
        });
        singleButton = button(coordinates, "Подать точку", view -> requestRun(SINGLE));
        streamButton = button(coordinates, "Поток 1 Гц", view -> requestRun(STREAM));
        stopButton = button(coordinates, "Стоп", view -> {
            viewingStream = false;
            stopService(new Intent(this, MockStreamService.class));
            finishSession("Подача тестовых точек остановлена.");
        });
        stopButton.setEnabled(false);
        session = text(coordinates, "Сессия не запущена.", 14, ACCENT, false);

        LinearLayout result = card(content);
        text(result, "3. Наблюдение детектора", 18, INK, true);
        observation = text(result, "Данных от Android ещё нет. Флаг isMock пока не измерен.", 14, INK, false);
        observation.setTextIsSelectable(true);
        decision = text(result, "Результат появится после callback LocationManager.", 14, MUTED, false);

        LinearLayout explanation = card(content);
        text(explanation, "Что проверяем", 18, INK, true);
        text(explanation, "Правдоподобные координаты сами по себе не подтверждают реальное положение. "
                + "Системный тестовый провайдер помечает выданные координаты как mock. Детектор читает "
                + "isMock на Android 12+ и isFromMockProvider на более ранних версиях.", 14, MUTED, false);
        text(explanation, "Порог точности и возраст точки — учебные правила этого стенда. Проверка "
                + "принимает решение только о полученной точке и не оценивает все возможные способы подмены.",
                13, MUTED, false);
        text(content, "Локальный эксперимент · без сети · фоновый поток с уведомлением", 12, MUTED, false);
    }

    private void requestRun(int requestedMode) {
        if (active || permissionRequestInFlight || providerAdded || MockStreamService.running) return;
        if (!readCoordinates()) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            pendingMode = requestedMode;
            permissionRequestInFlight = true;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST);
            return;
        }
        beginSession(requestedMode);
    }

    private boolean readCoordinates() {
        try {
            activeLatitude = Double.parseDouble(latitude.getText().toString().trim().replace(',', '.'));
            activeLongitude = Double.parseDouble(longitude.getText().toString().trim().replace(',', '.'));
            activeAccuracy = Float.parseFloat(accuracy.getText().toString().trim().replace(',', '.'));
            if (!Double.isFinite(activeLatitude) || Math.abs(activeLatitude) > 90
                    || !Double.isFinite(activeLongitude) || Math.abs(activeLongitude) > 180
                    || !Float.isFinite(activeAccuracy) || activeAccuracy <= 0 || activeAccuracy > 100000) {
                session.setText("Проверьте значения: широта −90…90, долгота −180…180, "
                        + "точность больше 0 и не более 100000 м.");
                return false;
            }
            return true;
        } catch (NumberFormatException exception) {
            session.setText("Введите числа в трёх полях. Десятичный разделитель — точка или запятая.");
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private void beginSession(int requestedMode) {
        if (active || providerAdded || !resumed) return;
        if (locationManager == null) {
            session.setText("Служба местоположения недоступна на этом устройстве.");
            return;
        }
        boolean locationEnabled = Build.VERSION.SDK_INT >= 28
                ? locationManager.isLocationEnabled()
                : Settings.Secure.getInt(getContentResolver(), Settings.Secure.LOCATION_MODE,
                        Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF;
        if (!locationEnabled) {
            session.setText("Включите «Местоположение» в настройках Android и повторите запуск.");
            return;
        }
        viewingStream = requestedMode == STREAM;
        if (requestedMode == STREAM) {
            try {
                startForegroundService(new Intent(this, MockStreamService.class)
                        .putExtra("lat", activeLatitude).putExtra("lon", activeLongitude)
                        .putExtra("accuracy", activeAccuracy));
                session.setText("Запускается фоновый поток. Остановка — кнопкой Стоп или из уведомления.");
                observation.setText("Поток использует edu_mock_lab_stream. Для измерения isMock остановите поток и нажмите Подать точку.");
                decision.setText("Фоновая подача; нового измерения детектора здесь нет.");
                if (Build.VERSION.SDK_INT >= 33 && !notificationAsked
                        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notificationAsked = true;
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 9);
                }
            } catch (RuntimeException error) {
                session.setText("Android отклонил запуск фонового потока. Проверьте разрешения.");
            }
            return;
        }
        try {
            locationManager.addTestProvider(PROVIDER, false, false, false, false,
                    false, false, false, Criteria.POWER_LOW, Criteria.ACCURACY_FINE);
            providerAdded = true;
            locationManager.setTestProviderEnabled(PROVIDER, true);
            sessionStartNanos = SystemClock.elapsedRealtimeNanos();
            received = 0;
            mode = requestedMode;
            active = true;
            observation.setText("Новая сессия: ожидается первая точка от Android. isMock пока не измерен.");
            decision.setText("Ожидание callback LocationManager…");
            decision.setTextColor(MUTED);
            updateControls();
            locationManager.requestLocationUpdates(PROVIDER, 0L, 0f, listener, Looper.getMainLooper());
            handler.postDelayed(timeout, 8000L);
            handler.post(publish);
        } catch (SecurityException exception) {
            finishSession("Нет доступа к тестовой геопозиции. Выберите Mock Location Lab в настройках "
                    + "разработчика и разрешите точное местоположение приложению.");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            finishSession("Не удалось создать учебный провайдер. Проверьте настройки местоположения "
                    + "и повторите запуск.");
        }
    }

    private void finishSession(String message) {
        active = false;
        handler.removeCallbacks(publish);
        handler.removeCallbacks(timeout);
        if (locationManager != null) {
            try { locationManager.removeUpdates(listener); }
            catch (SecurityException | IllegalArgumentException ignored) { }
            if (providerAdded) {
                if (!removeOwnedProvider()) message += " Android не подтвердил удаление провайдера. "
                        + "Нажмите «Повторить очистку» или снимите выбор Mock Location Lab "
                        + "в настройках разработчика.";
            }
        }
        updateControls();
        if (session != null) session.setText(message);
    }

    @SuppressWarnings("deprecation")
    private boolean removeOwnedProvider() {
        try {
            locationManager.removeTestProvider(PROVIDER);
            providerAdded = false;
            return true;
        } catch (IllegalArgumentException exception) {
            // Another lifecycle path may already have removed our provider.
            try {
                if (locationManager.getProvider(PROVIDER) == null) {
                    providerAdded = false;
                    return true;
                }
            } catch (SecurityException ignored) { }
        } catch (SecurityException ignored) { }
        return false;
    }

    private void updateControls() {
        if (singleButton == null) return;
        singleButton.setEnabled(!active && !providerAdded && !MockStreamService.running);
        streamButton.setEnabled(!active && !providerAdded && !MockStreamService.running);
        stopButton.setEnabled(active || providerAdded || MockStreamService.running);
        stopButton.setText(!active && providerAdded ? "Повторить очистку" : "Стоп");
        latitude.setEnabled(!active);
        longitude.setEnabled(!active);
        accuracy.setEnabled(!active);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 9) return;
        if (request != PERMISSION_REQUEST) return;
        permissionRequestInFlight = false;
        int desiredMode = pendingMode;
        pendingMode = 0;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            if (desiredMode != 0 && !isFinishing()) {
                if (resumed) beginSession(desiredMode);
                else pendingMode = desiredMode;
            } else if (!active) {
                session.setText("Доступ к точной геопозиции разрешён. Нажмите кнопку запуска эксперимента.");
            }
        } else {
            session.setText("Для эксперимента нужен доступ к точному местоположению. "
                    + "Разрешение можно изменить в настройках приложения.");
        }
    }

    private void openDeveloperSettings() {
        new AlertDialog.Builder(this)
                .setTitle("Выберите приложение для mock-геопозиции")
                .setMessage("В разделе «Для разработчиков» найдите «Приложение для фиктивных "
                        + "местоположений» и выберите Mock Location Lab. Название пункта зависит от телефона. "
                        + "Если раздел скрыт, сначала включите режим разработчика.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Открыть", (dialog, which) -> {
                    try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
                    catch (ActivityNotFoundException exception) {
                        session.setText("Откройте раздел «Для разработчиков» в настройках телефона вручную.");
                    }
                }).show();
    }

    @SuppressWarnings("deprecation")
    private static boolean isMock(Location location) {
        return Build.VERSION.SDK_INT >= 31 ? location.isMock() : location.isFromMockProvider();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        handler.post(streamStatus);
        if (providerAdded && !active) finishSession("Сессия остановлена.");
        if (!permissionRequestInFlight && pendingMode != 0
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            int desiredMode = pendingMode;
            pendingMode = 0;
            beginSession(desiredMode);
        }
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(streamStatus);
        if (active || providerAdded) {
            finishSession("Сессия остановлена при уходе с экрана. Последнее наблюдение сохранено ниже.");
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (active || providerAdded) finishSession("Сессия завершена.");
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("latitude", latitude.getText().toString());
        state.putString("longitude", longitude.getText().toString());
        state.putString("accuracy", accuracy.getText().toString());
        state.putBoolean("providerCleanupPending", providerAdded);
        super.onSaveInstanceState(state);
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(16));
        card.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(16);
        parent.addView(card, params);
        return card;
    }

    private TextView text(LinearLayout parent, String value, int size, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        label.setLineSpacing(dp(3), 1f);
        if (bold) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(6);
        params.bottomMargin = dp(6);
        parent.addView(label, params);
        return label;
    }

    private EditText field(LinearLayout parent, String label, String value) {
        text(parent, label, 13, MUTED, false);
        EditText input = new EditText(this);
        input.setTextSize(17);
        input.setTextColor(INK);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setText(value);
        input.setContentDescription(label);
        input.setSelectAllOnFocus(true);
        parent.addView(input, new LinearLayout.LayoutParams(-1, dp(52)));
        return input;
    }

    private Button button(LinearLayout parent, String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(ACCENT);
        button.setOnClickListener(listener);
        parent.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, -2));
        return button;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
