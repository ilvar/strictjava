package demo;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Effects {
    private Effects() {}

    static String read(Path path) throws IOException {
        String home = System.getenv("HOME");
        return home + Files.readString(path);
    }

    static FileInputStream open(Path path) throws IOException {
        return new FileInputStream(path.toFile());
    }

    static Class<?> reflect() throws ClassNotFoundException {
        return Class.forName("demo.Effects");
    }
}
