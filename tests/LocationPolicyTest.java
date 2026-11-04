import edu.mocklab.LocationPolicy;

/** Dependency-free tests. Run with javac and java; no Android runtime needed. */
public final class LocationPolicyTest {
    private static int cases;

    public static void main(String[] args) {
        // Identical plausible data changes the final decision only by mock flag.
        expect("plausible unmarked position", 43.238949, 76.889709, 5.0f, 0L,
                false, true, true);
        expect("plausible mock position", 43.238949, 76.889709, 5.0f, 0L,
                true, true, false);

        expect("NaN latitude", Double.NaN, 0.0, 5.0f, 0L,
                false, false, false);
        expect("infinite latitude", Double.POSITIVE_INFINITY, 0.0, 5.0f, 0L,
                false, false, false);
        expect("NaN longitude", 0.0, Double.NaN, 5.0f, 0L,
                false, false, false);
        expect("infinite longitude", 0.0, Double.NEGATIVE_INFINITY, 5.0f, 0L,
                false, false, false);

        expect("latitude above range", 90.000001, 0.0, 5.0f, 0L,
                false, false, false);
        expect("latitude below range", -90.000001, 0.0, 5.0f, 0L,
                false, false, false);
        expect("longitude above range", 0.0, 180.000001, 5.0f, 0L,
                false, false, false);
        expect("longitude below range", 0.0, -180.000001, 5.0f, 0L,
                false, false, false);

        expect("zero accuracy", 0.0, 0.0, 0.0f, 0L,
                false, false, false);
        expect("negative accuracy", 0.0, 0.0, -1.0f, 0L,
                false, false, false);
        expect("NaN accuracy", 0.0, 0.0, Float.NaN, 0L,
                false, false, false);
        expect("infinite accuracy", 0.0, 0.0, Float.POSITIVE_INFINITY, 0L,
                false, false, false);
        expect("poor accuracy", 0.0, 0.0, 50.00001f, 0L,
                false, false, false);

        expect("stale position", 0.0, 0.0, 5.0f, 10_001L,
                false, false, false);
        expect("future position", 0.0, 0.0, 5.0f, -1L,
                false, false, false);
        expect("very stale position", 0.0, 0.0, 5.0f, Long.MAX_VALUE,
                false, false, false);

        expect("inclusive positive bounds", 90.0, 180.0, 50.0f, 10_000L,
                false, true, true);
        expect("inclusive negative bounds with mock", -90.0, -180.0, 50.0f, 10_000L,
                true, true, false);
        expect("invalid position remains rejected with mock", 91.0, 0.0, 5.0f, 0L,
                true, false, false);

        System.out.println("LocationPolicy: " + cases + " cases passed.");
    }

    private static void expect(String name, double latitude, double longitude,
                               float accuracy, long age, boolean mocked,
                               boolean plausible, boolean accepted) {
        LocationPolicy.Result result = LocationPolicy.evaluate(
                latitude, longitude, accuracy, age, mocked);
        if (result.plausible != plausible || result.accepted != accepted) {
            throw new AssertionError(name + ": expected plausible=" + plausible
                    + ", accepted=" + accepted + "; got plausible=" + result.plausible
                    + ", accepted=" + result.accepted);
        }
        if (result.reason == null || result.reason.trim().isEmpty()) {
            throw new AssertionError(name + ": missing explanation");
        }
        if (result.accepted && (!result.plausible || mocked)) {
            throw new AssertionError(name + ": acceptance invariant violated");
        }
        cases++;
    }
}
