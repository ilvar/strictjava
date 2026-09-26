// strictjava: capability
package pw.rkd.strictjava.skills;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SkillInstaller {
    private SkillInstaller() {}

    public static List<String> installDetected() throws IOException {
        Path home = homeDirectory();
        List<Target> targets = detectedTargets(home);
        if (targets.isEmpty()) {
            throw new IOException(
                    "no supported agent installation detected; start Codex or Claude Code once, then rerun 'strictjava install-skills'");
        }

        String content = skillContent();
        preflight(targets, content);

        List<Path> created = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        try {
            for (Target target : targets) {
                if (Files.isRegularFile(target.path())) {
                    messages.add(target.agent() + " skill is already current: " + target.path());
                    continue;
                }

                Files.createDirectories(target.path().getParent());
                Files.writeString(
                        target.path(),
                        content,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                created.add(target.path());
                messages.add("installed " + target.agent() + " skill: " + target.path());
            }
        } catch (IOException exception) {
            for (Path path : created) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }
            }
            throw exception;
        }

        return messages;
    }

    private static Path homeDirectory() throws IOException {
        String home = System.getenv("HOME");
        if (home == null || home.isBlank()) {
            home = System.getenv("USERPROFILE");
        }
        if (home == null || home.isBlank()) {
            throw new IOException("cannot determine the user home directory");
        }
        return Path.of(home).toAbsolutePath().normalize();
    }

    private static List<Target> detectedTargets(Path home) {
        List<Target> targets = new ArrayList<>();
        if (Files.isDirectory(home.resolve(".codex"))
                || Files.isDirectory(home.resolve(".agents"))
                || commandExists("codex")) {
            targets.add(new Target(
                    "Codex",
                    home.resolve(".agents").resolve("skills").resolve("strictjava").resolve("SKILL.md")));
        }
        if (Files.isDirectory(home.resolve(".claude")) || commandExists("claude")) {
            targets.add(new Target(
                    "Claude Code",
                    home.resolve(".claude").resolve("skills").resolve("strictjava").resolve("SKILL.md")));
        }
        return targets;
    }

    private static void preflight(List<Target> targets, String content) throws IOException {
        for (Target target : targets) {
            if (!Files.exists(target.path())) {
                continue;
            }
            if (!Files.isRegularFile(target.path())) {
                throw new IOException("skill destination is not a regular file: " + target.path());
            }
            String existing = Files.readString(target.path(), StandardCharsets.UTF_8);
            if (!existing.equals(content)) {
                throw new IOException(
                        "refusing to overwrite a modified " + target.agent() + " skill at "
                                + target.path() + "; remove it explicitly and rerun the command");
            }
        }
    }

    private static String skillContent() throws IOException {
        try (InputStream input = SkillInstaller.class.getResourceAsStream("/skills/strictjava/SKILL.md")) {
            if (input == null) {
                throw new IOException("missing embedded strictjava skill");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static boolean commandExists(String name) {
        String rawPath = System.getenv("PATH");
        if (rawPath == null || rawPath.isBlank()) {
            return false;
        }
        for (String directory : rawPath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (directory.isBlank()) {
                continue;
            }
            Path base = Path.of(directory);
            if (Files.isRegularFile(base.resolve(name))) {
                return true;
            }
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                for (String extension : List.of(".exe", ".cmd", ".bat")) {
                    if (Files.isRegularFile(base.resolve(name + extension))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private record Target(String agent, Path path) {}
}
