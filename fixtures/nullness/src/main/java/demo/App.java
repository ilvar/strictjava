package demo;

import org.jspecify.annotations.Nullable;

final class App {
    private App() {}

    static int length(@Nullable String value) {
        return value.length();
    }
}
