package edu.mocklab;

/**
 * Two deliberately small checks for the educational lab.
 * Plausibility checks the values only; acceptance additionally checks the
 * mock flag reported by Android. Neither result proves physical presence.
 */
public final class LocationPolicy {
    public static final float MAX_ACCURACY_METERS = 50.0f;
    public static final long MAX_AGE_MILLIS = 10_000L;

    private LocationPolicy() {
    }

    public static Result evaluate(double latitude, double longitude,
                                  float accuracyMeters, long ageMillis,
                                  boolean mocked) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
            return invalid("Координаты должны быть конечными числами.");
        }
        if (latitude < -90.0 || latitude > 90.0
                || longitude < -180.0 || longitude > 180.0) {
            return invalid("Широта должна быть от −90 до 90°, долгота — от −180 до 180°.");
        }
        if (!Float.isFinite(accuracyMeters) || accuracyMeters <= 0.0f) {
            return invalid("Точность должна быть конечным положительным числом.");
        }
        if (accuracyMeters > MAX_ACCURACY_METERS) {
            return invalid("Заявленная погрешность превышает учебный порог 50 м.");
        }
        if (ageMillis < 0L) {
            return invalid("Время позиции находится в будущем относительно монотонных часов.");
        }
        if (ageMillis > MAX_AGE_MILLIS) {
            return invalid("Позиция старше учебного порога 10 секунд.");
        }
        if (mocked) {
            return new Result(true, false,
                    "Параметры правдоподобны, но Android пометил позицию как mock.");
        }
        return new Result(true, true,
                "Учебная проверка пройдена. Это не доказательство реального местоположения.");
    }

    private static Result invalid(String reason) {
        return new Result(false, false, reason);
    }

    public static final class Result {
        public final boolean plausible;
        public final boolean accepted;
        public final String reason;

        private Result(boolean plausible, boolean accepted, String reason) {
            this.plausible = plausible;
            this.accepted = accepted;
            this.reason = reason;
        }
    }
}
