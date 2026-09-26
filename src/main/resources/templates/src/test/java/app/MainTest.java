package app;

public final class MainTest {
    private MainTest() {}

    public static void main(String[] args) {
        String expected = "Hello from __PROJECT__.";
        String actual = Main.greeting();
        if (!expected.equals(actual)) {
            throw new AssertionError("expected '" + expected + "' but got '" + actual + "'");
        }
    }
}
