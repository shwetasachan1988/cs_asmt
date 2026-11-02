package com.sa.agentic.orchestrator.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sa.agentic.orchestrator.model.AgentResult;
import com.sa.agentic.orchestrator.model.Artifact;
import com.sa.agentic.orchestrator.model.Check;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.springframework.stereotype.Component;

/**
 * Really compiles the generated sources (in memory) and executes every {@code *Check.run()} it finds.
 * Generated code is loaded in a class loader whose parent is the platform loader, so it can only see the JDK -
 * and UnsafeCodePolicy has already rejected process/file/network/reflection APIs. This is a defence-in-depth
 * measure, not a sandbox: with untrusted (LLM) code the execution step belongs in a separate, locked-down container.
 */
@Component
public class UnitTestRunnerAgent extends BaseAgent {

    public UnitTestRunnerAgent(ObjectMapper json) {
        super(json);
    }

    @Override
    public String name() {
        return "unit-test-runner";
    }

    @Override
    public AgentResult execute(AgentContext ctx) throws Exception {
        List<Artifact> sources = ctx.upstreamArtifacts("source");
        if (sources.isEmpty()) {
            throw new NonRetryableAgentException("no source artifacts upstream - nothing to test");
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new NonRetryableAgentException("a JDK (not a JRE) is required to compile generated code");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<JavaFileObject> units = new ArrayList<>();
        for (Artifact a : sources) {
            units.add(new SourceUnit(a.name(), a.content()));
        }
        Map<String, byte[]> classes = new HashMap<>();
        boolean compiled;
        try (MemoryFileManager fm = new MemoryFileManager(compiler.getStandardFileManager(diagnostics, null,
                StandardCharsets.UTF_8), classes)) {
            compiled = compiler.getTask(null, fm, diagnostics, List.of("-proc:none"), null, units).call();
        }

        List<Check> checks = new ArrayList<>();
        StringBuilder report = new StringBuilder("# Unit test report\n\n| Check | Result | Detail |\n|---|---|---|\n");
        if (!compiled) {
            List<String> errors = diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .limit(5).map(d -> (d.getSource() == null ? "" : d.getSource().getName() + ":") + d.getLineNumber()
                            + " " + d.getMessage(null)).toList();
            checks.add(Check.fail("compile", String.join(" | ", errors)));
        } else {
            checks.add(Check.pass("compile", sources.size() + " source file(s) compiled, "
                    + classes.size() + " class(es) produced"));
            ClassLoader loader = new MemoryClassLoader(classes);
            List<String> checkClasses = classes.keySet().stream().filter(n -> n.endsWith("Check")).sorted().toList();
            if (checkClasses.isEmpty()) {
                checks.add(Check.fail("checks-present", "generated code ships no *Check class - untested code does not pass"));
            }
            for (String n : checkClasses) {
                checks.add(runCheck(loader, n));
            }
        }
        checks.forEach(c -> report.append("| ").append(c.name()).append(" | ").append(c.passed() ? "PASS" : "FAIL")
                .append(" | ").append(c.detail().replace("|", "/")).append(" |\n"));

        AgentResult.Builder out = AgentResult.builder(checks.stream().allMatch(Check::passed)
                        ? "All " + checks.size() + " unit check(s) passed on freshly compiled code"
                        : "Unit validation failed")
                .rationale("Code is compiled and executed, not just reviewed - a check that never ran proves nothing.")
                .artifact("test-report", "unit-tests.md", report.toString());
        checks.forEach(out::check);
        return out.build();
    }

    private Check runCheck(ClassLoader loader, String className) {
        try {
            Class<?> c = loader.loadClass(className);
            Method run = c.getMethod("run");
            Object result = run.invoke(null);
            if (!(result instanceof List<?> failures)) {
                return Check.fail(className, "run() must return List<String>");
            }
            return failures.isEmpty()
                    ? Check.pass(simple(className), "all expectations met")
                    : Check.fail(simple(className), failures.size() + " expectation(s) failed: " + failures);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return Check.fail(simple(className), "threw " + cause);
        }
    }

    private static String simple(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    // ---------------------------------------------------------------- in-memory compilation plumbing

    private static final class SourceUnit extends SimpleJavaFileObject {
        private final String code;

        SourceUnit(String path, String code) {
            super(URI.create("string:///" + path), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    private static final class ClassOutput extends SimpleJavaFileObject {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        ClassOutput(String className) {
            super(URI.create("mem:///" + className.replace('.', '/') + Kind.CLASS.extension), Kind.CLASS);
        }

        @Override
        public OutputStream openOutputStream() {
            return bytes;
        }
    }

    private static final class MemoryFileManager extends ForwardingJavaFileManager<JavaFileManager> {
        private final Map<String, byte[]> out;
        private final Map<String, ClassOutput> pending = new HashMap<>();

        MemoryFileManager(JavaFileManager delegate, Map<String, byte[]> out) {
            super(delegate);
            this.out = out;
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
                                                   FileObject sibling) {
            ClassOutput o = new ClassOutput(className);
            pending.put(className, o);
            return o;
        }

        @Override
        public void close() throws java.io.IOException {
            pending.forEach((name, o) -> out.put(name, o.bytes.toByteArray()));
            super.close();
        }
    }

    private static final class MemoryClassLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        MemoryClassLoader(Map<String, byte[]> classes) {
            super(ClassLoader.getPlatformClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] b = classes.get(name);
            if (b == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, b, 0, b.length);
        }
    }
}
