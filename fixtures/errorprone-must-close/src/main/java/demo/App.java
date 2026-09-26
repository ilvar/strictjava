// strictjava: capability
package demo;

import com.google.errorprone.annotations.MustBeClosed;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

final class App {
    private App() {}

    @MustBeClosed
    static InputStream open() {
        return new ByteArrayInputStream(new byte[0]);
    }

    static int leak() throws Exception {
        InputStream input = open();
        return input.read();
    }
}
