package demo;

import java.util.*;

@SuppressWarnings("unused")
public final class App {
    static int counter = 0;

    enum State { STARTING, RUNNING, STOPPED }

    static String read(Optional<String> value, State state) {
        String result = value.get();
        return switch (state) {
            case STARTING -> result;
            default -> "other";
        };
    }

    static Class<?> reflect() throws ClassNotFoundException {
        return Class.forName("demo.App");
    }

    static void quit() {
        System.exit(2);
    }

    static void hardHalt() {
        Runtime.getRuntime().halt(3);
    }
}
