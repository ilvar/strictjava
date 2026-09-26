// strictjava: capability
package pw.rkd.strictjava.check;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import pw.rkd.strictjava.model.Report;
import pw.rkd.strictjava.model.SourcePosition;
import pw.rkd.strictjava.model.SourceSpan;
import pw.rkd.strictjava.model.StrictDiagnostic;

public final class StrictJavaChecker {
    private final Path project;
    private final String classpath;

    public StrictJavaChecker(Path project) {
        this(project, null);
    }

    public StrictJavaChecker(Path project, String classpath) {
        this.project = project.toAbsolutePath().normalize();
        this.classpath = classpath;
    }

    public Report check() throws IOException {
        List<Path> sources = SourceFiles.find(project);
        if (sources.isEmpty()) {
            return new Report(List.of(new StrictDiagnostic(
                    "error",
                    "strictjava",
                    "strictjava::no_sources",
                    "no Java source files found",
                    null)));
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("strictjava requires a JDK, not a JRE");
        }

        DiagnosticCollector<JavaFileObject> compilerDiagnostics = new DiagnosticCollector<>();
        List<StrictDiagnostic> diagnostics = new ArrayList<>();
        Map<Path, String> sourceText = readSources(sources);
        Path output = Files.createTempDirectory("strictjava-classes-");

        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(compilerDiagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(sources);
            List<String> options = new ArrayList<>(List.of(
                    "-proc:none",
                    "-Xlint:all,-processing,-serial",
                    "-d",
                    output.toString()));
            if (classpath != null && !classpath.isBlank()) {
                options.add("-classpath");
                options.add(classpath);
            }
            JavacTask task = (JavacTask) compiler.getTask(
                    null, fileManager, compilerDiagnostics, options, null, units);

            List<CompilationUnitTree> parsed = new ArrayList<>();
            for (CompilationUnitTree unit : task.parse()) {
                parsed.add(unit);
            }
            try {
                task.analyze();
            } catch (RuntimeException ignored) {
                // javac diagnostics are authoritative; source-only strict rules can still run.
            }

            diagnostics.addAll(convertCompilerDiagnostics(compilerDiagnostics.getDiagnostics(), sourceText));
            Trees trees = Trees.instance(task);
            for (CompilationUnitTree unit : parsed) {
                new StrictScanner(trees, unit, sourceText, diagnostics).scan(unit, null);
            }
        } finally {
            deleteRecursively(output);
        }

        return new Report(diagnostics);
    }

    private boolean isTypeKind(Tree.Kind kind) {
        return kind == Tree.Kind.CLASS
                || kind == Tree.Kind.ENUM
                || kind == Tree.Kind.RECORD
                || kind == Tree.Kind.INTERFACE
                || kind == Tree.Kind.ANNOTATION_TYPE;
    }

    private Map<Path, String> readSources(List<Path> sources) throws IOException {
        Map<Path, String> text = new HashMap<>();
        for (Path source : sources) {
            text.put(source, Files.readString(source, StandardCharsets.UTF_8));
        }
        return text;
    }

    private List<StrictDiagnostic> convertCompilerDiagnostics(
            List<Diagnostic<? extends JavaFileObject>> raw,
            Map<Path, String> sourceText) {
        List<StrictDiagnostic> out = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> diagnostic : raw) {
            if (diagnostic.getKind() != Diagnostic.Kind.ERROR
                    && diagnostic.getKind() != Diagnostic.Kind.WARNING
                    && diagnostic.getKind() != Diagnostic.Kind.MANDATORY_WARNING) {
                continue;
            }
            String level = "error"; // warnings are errors in the strict profile.
            String code = diagnostic.getCode() == null ? "javac" : diagnostic.getCode();
            SourceSpan span = null;
            if (diagnostic.getSource() != null) {
                Path file = Path.of(diagnostic.getSource().toUri()).toAbsolutePath().normalize();
                String text = sourceText.getOrDefault(file, "");
                long start = safeOffset(diagnostic.getStartPosition(), diagnostic.getPosition());
                long end = diagnostic.getEndPosition() >= 0 ? diagnostic.getEndPosition() : start;
                span = span(file, text, start, end, diagnostic.getLineNumber(), diagnostic.getColumnNumber());
            }
            out.add(new StrictDiagnostic(
                    level,
                    "javac",
                    code,
                    diagnostic.getMessage(Locale.ROOT),
                    span));
        }
        return out;
    }

    private long safeOffset(long preferred, long fallback) {
        if (preferred >= 0) {
            return preferred;
        }
        return Math.max(fallback, 0);
    }

    private SourceSpan span(Path file, String text, long start, long end, long fallbackLine, long fallbackColumn) {
        long safeStart = Math.max(0, Math.min(start, text.length()));
        long safeEnd = Math.max(safeStart, Math.min(end, text.length()));
        SourcePosition startPosition = position(text, safeStart, fallbackLine, fallbackColumn);
        SourcePosition endPosition = position(text, safeEnd, startPosition.line(), startPosition.column());
        String snippet = snippet(text, safeStart);
        return new SourceSpan(relative(file), startPosition, endPosition, snippet);
    }

    private String relative(Path file) {
        if (Files.isRegularFile(project)) {
            return project.getFileName().toString();
        }
        if (file.startsWith(project)) {
            return project.relativize(file).toString().replace('\\', '/');
        }
        return file.toString().replace('\\', '/');
    }

    private SourcePosition position(String text, long offset, long fallbackLine, long fallbackColumn) {
        if (text.isEmpty()) {
            return new SourcePosition(Math.max(fallbackLine, 1), Math.max(fallbackColumn, 1), offset);
        }
        long line = 1;
        long column = 1;
        int limit = (int) Math.min(offset, text.length());
        for (int i = 0; i < limit; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new SourcePosition(line, column, offset);
    }

    private String snippet(String text, long offset) {
        if (text.isEmpty()) {
            return "";
        }
        int index = (int) Math.max(0, Math.min(offset, text.length()));
        int start = text.lastIndexOf('\n', Math.max(0, index - 1));
        start = start < 0 ? 0 : start + 1;
        int end = text.indexOf('\n', index);
        end = end < 0 ? text.length() : end;
        return text.substring(start, end);
    }

    private void deleteRecursively(Path root) {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Temp cleanup failure must not hide source diagnostics.
        }
    }

    private final class StrictScanner extends TreePathScanner<Void, Void> {
        private final Trees trees;
        private final CompilationUnitTree unit;
        private final String text;
        private final List<StrictDiagnostic> diagnostics;
        private final Path file;
        private final boolean capabilityBoundary;

        StrictScanner(
                Trees trees,
                CompilationUnitTree unit,
                Map<Path, String> sourceText,
                List<StrictDiagnostic> diagnostics) {
            this.trees = trees;
            this.unit = unit;
            this.file = Path.of(unit.getSourceFile().toUri()).toAbsolutePath().normalize();
            this.text = sourceText.getOrDefault(file, "");
            this.diagnostics = diagnostics;
            this.capabilityBoundary = hasCapabilityMarker(text);
        }

        @Override
        public Void visitImport(ImportTree node, Void unused) {
            if (node.getQualifiedIdentifier().toString().endsWith(".*")) {
                add("strictjava::no_wildcard_import", "wildcard imports are not allowed", node);
            }
            return super.visitImport(node, unused);
        }

        @Override
        public Void visitAnnotation(AnnotationTree node, Void unused) {
            String name = node.getAnnotationType().toString();
            if (name.equals("SuppressWarnings") || name.endsWith(".SuppressWarnings")) {
                add("strictjava::no_suppress_warnings", "@SuppressWarnings is not allowed", node);
            }
            return super.visitAnnotation(node, unused);
        }

        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            TreePath parent = getCurrentPath().getParentPath();
            if (parent != null && isTypeKind(parent.getLeaf().getKind())) {
                Element element = trees.getElement(getCurrentPath());
                Set<Modifier> flags = element != null ? element.getModifiers() : node.getModifiers().getFlags();
                if (flags.contains(Modifier.STATIC)) {
                    boolean enumConstant = element != null && element.getKind() == ElementKind.ENUM_CONSTANT;
                    boolean compileTimeConstant = element instanceof VariableElement variable
                            && variable.getConstantValue() != null;
                    if (!enumConstant && !compileTimeConstant) {
                        add("strictjava::no_mutable_global", "static state must be a compile-time constant", node);
                    }
                }
            }
            return super.visitVariable(node, unused);
        }

        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            if (node.getModifiers().getFlags().contains(Modifier.NATIVE)) {
                add(
                        "strictjava::no_native_code",
                        "native methods are not allowed; keep implementation inside the JVM",
                        node);
            }
            return super.visitMethod(node, unused);
        }

        @Override
        public Void visitNewClass(NewClassTree node, Void unused) {
            Element element = trees.getElement(getCurrentPath());
            if (element instanceof ExecutableElement executable) {
                Element enclosing = executable.getEnclosingElement();
                String owner = enclosing instanceof TypeElement type
                        ? type.getQualifiedName().toString()
                        : "";
                if (isCapabilityConstructor(owner) && !capabilityBoundary) {
                    add(
                            "strictjava::capability_boundary",
                            "filesystem and network effects must live in a capability source file",
                            node);
                }
            }
            return super.visitNewClass(node, unused);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            TreePath methodPath = new TreePath(getCurrentPath(), node.getMethodSelect());
            Element element = trees.getElement(methodPath);
            if (element instanceof ExecutableElement executable) {
                Element enclosing = executable.getEnclosingElement();
                String owner = enclosing instanceof TypeElement type
                        ? type.getQualifiedName().toString()
                        : "";
                String method = executable.getSimpleName().toString();

                if (owner.equals("java.util.Optional") && method.equals("get")) {
                    add("strictjava::no_optional_get", "Optional.get() is not allowed; handle the empty case explicitly", node);
                }
                if (owner.equals("java.lang.System") && method.equals("exit") && !insideMainMethod()) {
                    add("strictjava::no_system_exit", "System.exit() is allowed only at the top-level main boundary", node);
                }
                if (owner.equals("java.lang.Runtime") && (method.equals("halt") || method.equals("exit"))) {
                    add("strictjava::no_runtime_halt", "Runtime.exit()/halt() are not allowed", node);
                }
                if ((owner.equals("java.lang.System") || owner.equals("java.lang.Runtime"))
                        && (method.equals("load") || method.equals("loadLibrary"))) {
                    add("strictjava::no_native_code", "loading native libraries is not allowed", node);
                }
                if (isReflection(owner, method) && !capabilityBoundary) {
                    add("strictjava::no_reflection", "reflection is allowed only inside an explicit capability boundary", node);
                }
                if (isCapabilityEffect(owner, method) && !capabilityBoundary) {
                    add(
                            "strictjava::capability_boundary",
                            "filesystem, process, environment, and network effects must live in a capability source file",
                            node);
                }
            }
            return super.visitMethodInvocation(node, unused);
        }

        @Override
        public Void visitSwitch(SwitchTree node, Void unused) {
            checkSwitch(node.getExpression(), node.getCases(), node);
            return super.visitSwitch(node, unused);
        }

        @Override
        public Void visitSwitchExpression(SwitchExpressionTree node, Void unused) {
            checkSwitch(node.getExpression(), node.getCases(), node);
            return super.visitSwitchExpression(node, unused);
        }

        private void checkSwitch(Tree expression, List<? extends CaseTree> cases, Tree wholeSwitch) {
            boolean hasDefault = cases.stream()
                    .flatMap(c -> c.getLabels().stream())
                    .anyMatch(label -> label.getKind().name().equals("DEFAULT_CASE_LABEL"));
            if (!hasDefault) {
                return;
            }
            TypeMirror type = trees.getTypeMirror(new TreePath(getCurrentPath(), expression));
            if (!(type instanceof DeclaredType declared)) {
                return;
            }
            Element element = declared.asElement();
            if (element instanceof TypeElement typeElement) {
                boolean closed = typeElement.getKind() == ElementKind.ENUM
                        || !typeElement.getPermittedSubclasses().isEmpty();
                if (closed) {
                    add(
                            "strictjava::no_catchall_switch",
                            "default is not allowed for enum/sealed switches; enumerate every case",
                            wholeSwitch);
                }
            }
        }


        private boolean insideMainMethod() {
            TreePath path = getCurrentPath();
            while (path != null) {
                if (path.getLeaf() instanceof MethodTree method) {
                    return method.getName().contentEquals("main")
                            && method.getModifiers().getFlags().contains(Modifier.STATIC);
                }
                path = path.getParentPath();
            }
            return false;
        }

        private boolean hasCapabilityMarker(String source) {
            return source.lines()
                    .limit(20)
                    .map(String::trim)
                    .anyMatch(line -> line.equals("// strictjava: capability"));
        }

        private boolean isCapabilityConstructor(String owner) {
            return owner.equals("java.io.FileInputStream")
                    || owner.equals("java.io.FileOutputStream")
                    || owner.equals("java.io.FileReader")
                    || owner.equals("java.io.FileWriter")
                    || owner.equals("java.io.RandomAccessFile")
                    || owner.equals("java.net.Socket")
                    || owner.equals("java.net.ServerSocket")
                    || owner.equals("java.net.DatagramSocket");
        }

        private boolean isCapabilityEffect(String owner, String method) {
            if (owner.equals("java.nio.file.Files")) {
                return true;
            }
            if (owner.equals("java.lang.System")) {
                return method.equals("getenv") || method.equals("getProperty") || method.equals("getProperties");
            }
            if (owner.equals("java.lang.Runtime")) {
                return method.equals("exec");
            }
            if (owner.equals("java.lang.ProcessBuilder")) {
                return method.equals("start") || method.equals("startPipeline");
            }
            if (owner.equals("java.net.http.HttpClient")) {
                return method.equals("send") || method.equals("sendAsync");
            }
            if (owner.startsWith("java.net.")) {
                return method.equals("connect")
                        || method.equals("getInputStream")
                        || method.equals("getOutputStream")
                        || method.equals("openConnection")
                        || method.equals("openStream");
            }
            return false;
        }

        private boolean isReflection(String owner, String method) {
            if (owner.startsWith("java.lang.reflect.")) {
                return true;
            }
            if (!owner.equals("java.lang.Class")) {
                return false;
            }
            return method.equals("forName")
                    || method.startsWith("getDeclared")
                    || method.equals("getMethod")
                    || method.equals("getMethods")
                    || method.equals("getField")
                    || method.equals("getFields")
                    || method.equals("getConstructor")
                    || method.equals("getConstructors");
        }

        private void add(String code, String message, Tree tree) {
            long start = trees.getSourcePositions().getStartPosition(unit, tree);
            long end = trees.getSourcePositions().getEndPosition(unit, tree);
            if (start < 0) {
                start = 0;
            }
            if (end < start) {
                end = start;
            }
            diagnostics.add(new StrictDiagnostic(
                    "error",
                    "strictjava",
                    code,
                    message,
                    span(file, text, start, end, 1, 1)));
        }
    }
}
