package pw.rkd.strictjava.check;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

final class SourceFiles {
    private SourceFiles() {}

    static List<Path> find(Path project) throws IOException {
        if (!Files.exists(project)) {
            throw new IOException("path does not exist: " + project);
        }
        if (Files.isRegularFile(project)) {
            if (!project.toString().endsWith(".java")) {
                throw new IOException("not a Java source file: " + project);
            }
            return List.of(project.toAbsolutePath().normalize());
        }

        List<Path> roots = conventionalRoots(project);
        if (roots.isEmpty()) {
            roots = List.of(project);
        }

        List<Path> result = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> !isIgnored(project.relativize(path)))
                        .map(path -> path.toAbsolutePath().normalize())
                        .forEach(result::add);
            }
        }
        result.sort(Comparator.comparing(Path::toString));
        return result;
    }

    private static List<Path> conventionalRoots(Path project) {
        List<Path> roots = new ArrayList<>();
        Path main = project.resolve("src/main/java");
        Path test = project.resolve("src/test/java");
        if (Files.isDirectory(main)) {
            roots.add(main);
        }
        if (Files.isDirectory(test)) {
            roots.add(test);
        }
        return roots;
    }

    private static boolean isIgnored(Path relative) {
        for (Path part : relative) {
            String value = part.toString();
            if (value.equals("build")
                    || value.equals("out")
                    || value.equals("target")
                    || value.equals(".gradle")
                    || value.equals(".git")) {
                return true;
            }
        }
        return false;
    }
}
