package demo;

import java.util.Optional;

public final class App {
    private App() {}

    enum State { STARTING, RUNNING, STOPPED }

    static String read(Optional<String> value, State state) {
        String resolved = value.orElse("missing");
        return switch (state) {
            case STARTING -> resolved;
            case RUNNING -> "running";
            case STOPPED -> "stopped";
        };
    }
}
