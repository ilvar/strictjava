package demo;

import examplelib.Greeting;

public final class App {
    private App() {}

    static String value() {
        return Greeting.text();
    }
}
