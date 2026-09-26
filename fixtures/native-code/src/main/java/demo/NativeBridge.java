package demo;

public final class NativeBridge {
    private NativeBridge() {}

    static native int raw();

    static void load() {
        System.loadLibrary("demo");
    }
}
