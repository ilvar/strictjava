package demo;

import org.jspecify.annotations.Nullable;

final class App {
    private App() {}

    static int value() {
        @Nullable int number = 1;
        return number;
    }
}
