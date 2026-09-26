package demo;

public final class Broken {
    private Broken() {}

    static int value() {
        return "not an int";
    }
}
