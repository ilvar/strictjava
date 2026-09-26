// strictjava: capability
package demo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class App {
    private App() {}

    static long lines(Path path) throws IOException {
        return Files.lines(path).count();
    }
}
