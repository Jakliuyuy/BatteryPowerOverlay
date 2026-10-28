package android.os;

public class Looper {
    private static final Looper MAIN_LOOPER = new Looper();

    public static Looper getMainLooper() {
        return MAIN_LOOPER;
    }

    public static Looper myLooper() {
        return MAIN_LOOPER;
    }
}
