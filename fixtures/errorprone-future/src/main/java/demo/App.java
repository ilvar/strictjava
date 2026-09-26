package demo;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

final class App {
    private App() {}

    static Future<String> task() {
        return CompletableFuture.completedFuture("ok");
    }

    static void run() {
        task();
    }
}
