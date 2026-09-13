package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jgit.ignore.FastIgnoreRule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * daemon 模块的轻量架构守卫。
 *
 * <p>Daemon 的 main 源码只允许依赖 JDK、Jackson、JGit（{@code org.eclipse.jgit.ignore.FastIgnoreRule}）、{@code
 * com.google.re2j}、{@code harness.common}、{@code harness.environment} 以及本模块自身包。 严禁直接依赖 {@code
 * harness.tool}、runtime/platform/web、Spring/MyBatis/servlet/Redis 以及 Provider SDK 和 LangChain4j。
 */
class DaemonModuleArchitectureTest {

  private static final List<String> FORBIDDEN_IMPORT_PREFIXES =
      List.of(
          "fun.fengwk.kkstudio.harness.tool.",
          "fun.fengwk.kkstudio.harness.runtime.",
          "fun.fengwk.kkstudio.harness.infra.",
          "fun.fengwk.kkstudio.harness.kernel.",
          "fun.fengwk.kkstudio.platform.",
          "fun.fengwk.kkstudio.web.",
          "dev.langchain4j.",
          "org.springframework.",
          "org.mybatis.",
          "org.apache.ibatis.",
          "jakarta.servlet.",
          "javax.servlet.",
          "jakarta.ws.",
          "javax.ws.",
          "io.lettuce.",
          "redis.clients.",
          "org.redisson.",
          "com.openai.",
          "com.anthropic.",
          "com.google.genai.",
          "com.google.ai.");

  private static final Pattern STATIC_EXECUTOR_FIELD =
      Pattern.compile(
          "\\bstatic\\s+(?:final\\s+)?(?:[\\w.]*Executor(?:Service)?|"
              + "ScheduledThreadPoolExecutor|ThreadPoolExecutor|ForkJoinPool)\\s+\\w+\\s*(?:=|;)");
  private static final Pattern DIRECT_VIRTUAL_THREAD_START =
      Pattern.compile(
          "Thread\\s*\\.\\s*ofVirtual\\s*\\(\\s*\\)(?:(?!;).)*?\\.start\\s*\\(", Pattern.DOTALL);
  private static final Pattern EXECUTOR_CREATION =
      Pattern.compile(
          "\\bExecutors\\s*\\.|new\\s+(?:ScheduledThreadPoolExecutor|ThreadPoolExecutor)\\s*\\(");

  @Test
  void daemonMainSourcesStayOnJdkJacksonEnvironmentAndOwnPackages() throws IOException {
    Path main = locateDaemonMainJava();
    assertTrue(Files.isDirectory(main), "daemon main sources must exist: " + main);

    List<String> violations = scanViolations(main);
    assertTrue(
        violations.isEmpty(), () -> "architecture violations:\n" + String.join("\n", violations));
  }

  /** Daemon main 只通过 Environment Capability SPI 执行，不依赖模型 Tool execution。 */
  @Test
  void daemonMainUsesCapabilityExecutionBoundary() throws IOException {
    Path main = locateDaemonMainJava();
    List<String> violations = new ArrayList<>();
    try (Stream<Path> stream = Files.walk(main)) {
      stream
          .filter(path -> path.toString().endsWith(".java"))
          .filter(path -> !isGeneratedOrTarget(path))
          .forEach(
              path -> {
                try {
                  String source = Files.readString(path, StandardCharsets.UTF_8);
                  if (source.contains("fun.fengwk.kkstudio.harness.tool.")) {
                    violations.add(relative(main, path));
                  }
                } catch (IOException error) {
                  throw new IllegalStateException(error);
                }
              });
    }
    assertTrue(
        violations.isEmpty(),
        () ->
            "daemon main must use Environment contract only (no tool imports):\n"
                + String.join("\n", violations));
  }

  /** Daemon main 源码不得持有 static executor，也不得绕过 runtime 统一 executor 直接启动虚拟线程。 */
  @Test
  void daemonMainSourcesKeepExecutorOwnershipInsideRuntimeInstances() throws IOException {
    Path main = locateDaemonMainJava();
    List<String> violations = new ArrayList<>();
    try (Stream<Path> stream = Files.walk(main)) {
      stream
          .filter(path -> path.toString().endsWith(".java"))
          .filter(path -> !isGeneratedOrTarget(path))
          .forEach(
              path -> {
                try {
                  String source = Files.readString(path, StandardCharsets.UTF_8);
                  String relativePath = relative(main, path).replace('\\', '/');
                  if (STATIC_EXECUTOR_FIELD.matcher(source).find()) {
                    violations.add(relativePath + ": static executor field");
                  }
                  if (DIRECT_VIRTUAL_THREAD_START.matcher(source).find()) {
                    violations.add(relativePath + ": direct virtual thread start");
                  }
                  if (!relativePath.equals("fun/fengwk/kkstudio/harness/daemon/DaemonRuntime.java")
                      && EXECUTOR_CREATION.matcher(source).find()) {
                    violations.add(relativePath + ": executor creation outside DaemonRuntime");
                  }
                } catch (IOException error) {
                  throw new IllegalStateException(error);
                }
              });
    }
    assertTrue(
        violations.isEmpty(),
        () -> "executor lifecycle violations:\n" + String.join("\n", violations));
  }

  @Test
  void daemonPomDeclaresEnvironmentInsteadOfToolDependency() throws IOException {
    Path moduleRoot = locateDaemonMainJava().getParent().getParent().getParent();
    Path pom = moduleRoot.resolve("pom.xml");
    String text = Files.readString(pom, StandardCharsets.UTF_8);
    Matcher matcher =
        Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL).matcher(text);
    List<String> violations = new ArrayList<>();
    while (matcher.find()) {
      String dependency = matcher.group(1);
      String coordinate =
          requiredTag(dependency, "groupId") + ":" + requiredTag(dependency, "artifactId");
      if ("fun.fengwk.kk-studio:kk-studio-harness-tool".equals(coordinate)) {
        violations.add("daemon pom must not directly depend on kk-studio-harness-tool");
      }
      if (coordinate.startsWith("dev.langchain4j:")
          || coordinate.startsWith("io.github.langchain4j:")
          || requiredTag(dependency, "artifactId").startsWith("langchain4j-")) {
        violations.add("daemon pom must not declare langchain4j dependency: " + coordinate);
      }
    }
    assertTrue(
        violations.isEmpty(),
        () -> "disallowed direct tool dependency in " + pom + ": " + violations);
  }

  private static List<String> scanViolations(Path main) throws IOException {
    List<String> violations = new ArrayList<>();
    try (Stream<Path> stream = Files.walk(main)) {
      stream
          .filter(path -> path.toString().endsWith(".java"))
          .filter(path -> !isGeneratedOrTarget(path))
          .forEach(
              path -> {
                try {
                  String source = Files.readString(path, StandardCharsets.UTF_8);
                  String relativePath = relative(main, path).replace('\\', '/');
                  for (String line : source.split("\\R")) {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("import ")) {
                      continue;
                    }
                    String imported = normalizeImport(trimmed);
                    for (String prefix : FORBIDDEN_IMPORT_PREFIXES) {
                      if (imported.startsWith(prefix)) {
                        violations.add(relativePath + ": " + trimmed);
                      }
                    }
                    if (!isAllowedImport(imported)) {
                      violations.add(relativePath + ": disallowed import " + trimmed);
                    }
                  }
                } catch (IOException error) {
                  throw new IllegalStateException(error);
                }
              });
    }
    return violations;
  }

  private static boolean isAllowedImport(String imported) {
    return imported.startsWith("java.")
        || imported.startsWith("javax.")
        || imported.startsWith("com.fasterxml.jackson.")
        || imported.startsWith("com.google.re2j.")
        || imported.startsWith("fun.fengwk.kkstudio.harness.common.")
        || imported.startsWith("fun.fengwk.kkstudio.harness.environment.")
        || imported.startsWith("fun.fengwk.kkstudio.harness.daemon.")
        || imported.equals(FastIgnoreRule.class.getName());
  }

  private static String normalizeImport(String importLine) {
    String imported = importLine.substring("import ".length()).replace(";", "").trim();
    if (imported.startsWith("static ")) {
      imported = imported.substring("static ".length()).trim();
    }
    return imported;
  }

  private static boolean isGeneratedOrTarget(Path path) {
    String normalized = path.toString().replace('\\', '/');
    return normalized.contains("/target/")
        || normalized.contains("/generated-sources/")
        || normalized.contains("/generated-test-sources/");
  }

  private static String relative(Path main, Path path) {
    try {
      return main.relativize(path).toString();
    } catch (IllegalArgumentException ignored) {
      return path.getFileName().toString();
    }
  }

  /** 当 Maven 在模块根或 reactor 根运行时解析 {@code src/main/java}；不会解析到 {@code target/} 下。 */
  private static Path locateDaemonMainJava() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    List<Path> candidates =
        List.of(cwd.resolve("src/main/java"), cwd.resolve("harness/daemon/src/main/java"));
    for (Path candidate : candidates) {
      Path normalized = candidate.normalize();
      if (Files.isDirectory(normalized) && !isGeneratedOrTarget(normalized)) {
        return normalized;
      }
    }
    throw new IllegalStateException("cannot locate daemon main sources from " + cwd);
  }

  private static String requiredTag(String block, String tag) {
    String value = optionalTag(block, tag);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("dependency must declare " + tag);
    }
    return value;
  }

  private static String optionalTag(String block, String tag) {
    Matcher matcher = Pattern.compile("<" + tag + ">\\s*([^<]+?)\\s*</" + tag + ">").matcher(block);
    return matcher.find() ? matcher.group(1).trim() : null;
  }
}
