// strictjava: capability
package pw.rkd.strictjava.project;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class ProjectGenerator {
    private static final String PROJECT_PLACEHOLDER = "__PROJECT__";
    private static final String STRICTJAVA_REF_PLACEHOLDER = "__STRICTJAVA_REF__";
    private static final String STRICTJAVA_REF = "fe731f3de1c5bfa247082efa058d584d5f06c2d6";

    private ProjectGenerator() {}

    public static Path create(Path parent, String name) throws IOException {
        validateName(name);

        Path normalizedParent = parent.toAbsolutePath().normalize();
        Path destination = normalizedParent.resolve(name);
        if (Files.exists(destination)) {
            throw new IOException("destination already exists: " + destination);
        }

        Path staging = normalizedParent.resolve("." + name + ".strictjava-tmp");
        if (Files.exists(staging)) {
            throw new IOException("staging path already exists: " + staging);
        }

        Files.createDirectory(staging);
        try {
            writeProject(staging, name);
            Files.move(staging, destination);
        } catch (IOException | RuntimeException exception) {
            deleteRecursively(staging);
            throw exception;
        }

        return destination;
    }

    private static void validateName(String name) {
        if (name.isEmpty() || !isLowerAsciiLetter(name.charAt(0))) {
            throw new IllegalArgumentException("project name must start with a lowercase ASCII letter");
        }
        for (int index = 1; index < name.length(); index++) {
            char value = name.charAt(index);
            if (!isLowerAsciiLetter(value)
                    && !(value >= '0' && value <= '9')
                    && value != '-'
                    && value != '_') {
                throw new IllegalArgumentException(
                        "project name may contain only lowercase ASCII letters, digits, '-' and '_'");
            }
        }
    }

    private static boolean isLowerAsciiLetter(char value) {
        return value >= 'a' && value <= 'z';
    }

    private static void writeProject(Path project, String name) throws IOException {
        for (TemplateFile template : textTemplates()) {
            writeText(
                    project,
                    template.destination(),
                    render(readTextResource(template.resource()), name));
        }

        writeBinary(
                project,
                "gradle/wrapper/gradle-wrapper.jar",
                "/templates/gradle/wrapper/gradle-wrapper.jar");

        setExecutable(project.resolve("gradlew"));
        setExecutable(project.resolve("scripts/commit.sh"));
        setExecutable(project.resolve("scripts/strictjava.sh"));
    }

    private static List<TemplateFile> textTemplates() {
        return List.of(
                new TemplateFile(".dockerignore", "/templates/dockerignore"),
                new TemplateFile(".github/workflows/ci.yml", "/templates/ci.yml"),
                new TemplateFile(".gitignore", "/templates/gitignore"),
                new TemplateFile(".pre-commit-config.yaml", "/templates/pre-commit-config.yaml"),
                new TemplateFile("AGENTS.md", "/templates/AGENTS.md"),
                new TemplateFile("CLAUDE.md", "/templates/CLAUDE.md"),
                new TemplateFile("Dockerfile", "/templates/Dockerfile"),
                new TemplateFile("Makefile", "/templates/Makefile"),
                new TemplateFile("README.md", "/templates/README.md"),
                new TemplateFile("build.gradle.kts", "/templates/build.gradle.kts"),
                new TemplateFile("gradle.lockfile", "/templates/gradle.lockfile"),
                new TemplateFile("gradle.properties", "/templates/gradle.properties"),
                new TemplateFile("gradle/verification-metadata.xml", "/templates/gradle/verification-metadata.xml"),
                new TemplateFile("gradle/wrapper/gradle-wrapper.properties", "/templates/gradle/wrapper/gradle-wrapper.properties"),
                new TemplateFile("gradlew", "/templates/gradlew"),
                new TemplateFile("gradlew.bat", "/templates/gradlew.bat"),
                new TemplateFile("scripts/commit.sh", "/templates/commit.sh"),
                new TemplateFile("scripts/strictjava.sh", "/templates/strictjava.sh"),
                new TemplateFile("settings.gradle.kts", "/templates/settings.gradle.kts"),
                new TemplateFile("src/main/java/app/Main.java", "/templates/src/main/java/app/Main.java"),
                new TemplateFile("src/main/java/app/package-info.java", "/templates/src/main/java/app/package-info.java"),
                new TemplateFile("src/test/java/app/MainTest.java", "/templates/src/test/java/app/MainTest.java"));
    }

    private static String render(String template, String name) {
        return template.replace(PROJECT_PLACEHOLDER, name)
                .replace(STRICTJAVA_REF_PLACEHOLDER, STRICTJAVA_REF);
    }

    private static String readTextResource(String resource) throws IOException {
        try (InputStream input = ProjectGenerator.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("missing embedded template: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void writeText(Path project, String relative, String content) throws IOException {
        Path target = target(project, relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private static void writeBinary(Path project, String relative, String resource) throws IOException {
        Path target = target(project, relative);
        Files.createDirectories(target.getParent());
        try (InputStream input = ProjectGenerator.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("missing embedded template: " + resource);
            }
            Files.copy(input, target);
        }
    }

    private static Path target(Path project, String relative) throws IOException {
        Path target = project.resolve(relative).normalize();
        if (!target.startsWith(project)) {
            throw new IOException("template path escapes project root: " + relative);
        }
        return target;
    }

    private static void setExecutable(Path path) throws IOException {
        try {
            Set<PosixFilePermission> permissions = EnumSet.copyOf(Files.getPosixFilePermissions(path));
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            if (!path.toFile().setExecutable(true, false)) {
                throw new IOException("failed to make executable: " + path);
            }
        }
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((left, right) -> right.compareTo(left)).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Cleanup must not hide the generator's original failure.
        }
    }

    private record TemplateFile(String destination, String resource) {}
}
