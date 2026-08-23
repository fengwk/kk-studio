package fun.fengwk.kkstudio.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 守护受 Git 管理的后端生产代码与配置，防止 Redis 客户端、依赖或部署配置回流。 */
class NoRedisArchitectureTest {

  private static final List<String> EXCLUDED_ROOTS = List.of("frontend/", "docs/", "scripts/");
  private static final List<String> FORBIDDEN_JAVA_PACKAGES =
      List.of(
          "io.lettuce.",
          "org.redisson.",
          "org.springframework.boot.autoconfigure.data.redis.",
          "org.springframework.data.redis.",
          "redis.clients.");
  private static final Pattern FORBIDDEN_JAVA_TYPE =
      Pattern.compile("\\b(?:Jedis|Lettuce|Redis|Redisson)[A-Za-z0-9_]*\\b");
  private static final Pattern FORBIDDEN_POM_GROUP =
      Pattern.compile(
          "<groupId>\\s*(?:io\\.lettuce|org\\.redisson|redis\\.clients)\\s*</groupId>",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern FORBIDDEN_POM_ARTIFACT =
      Pattern.compile(
          "<artifactId>\\s*[^<]*(?:jedis|lettuce|redis|redisson)[^<]*</artifactId>",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern FORBIDDEN_CONFIGURATION =
      Pattern.compile("(?:jedis|lettuce|redis|redisson)", Pattern.CASE_INSENSITIVE);

  /** 扫描 Git 索引而非工作目录猜测，确保最终集成时 deploy 配置也自动进入同一守卫。 */
  @Test
  void trackedProductionFilesDoNotRestoreRedisStack() throws Exception {
    Path root = repositoryRoot();
    List<String> violations = new ArrayList<>();
    for (String relative : gitTrackedFiles(root)) {
      if (isExcluded(relative)) {
        continue;
      }
      Path path = root.resolve(relative);
      if (isProductionJava(relative)) {
        inspectJava(relative, Files.readString(path, StandardCharsets.UTF_8), violations);
      } else if (relative.endsWith("pom.xml")) {
        inspectPom(relative, Files.readString(path, StandardCharsets.UTF_8), violations);
      } else if (isConfiguration(relative)) {
        inspectConfiguration(relative, Files.readString(path, StandardCharsets.UTF_8), violations);
      }
    }
    assertTrue(
        violations.isEmpty(),
        () ->
            "Redis stack must be absent from tracked backend files:\n"
                + String.join("\n", violations));
  }

  /** Reactor 是独立能力：合法 Reactor 用法必须放行，而 Redis Java/POM/环境配置必须分别命中。 */
  @Test
  void reactorIsAllowedAndRedisFixturesAreRejected() {
    List<String> reactorViolations = new ArrayList<>();
    String reactorImport =
        "import "
            + String.join(".", "reactor", "core", "publisher", "Mono")
            + ";\nclass ReactorService { Mono<String> get() { return Mono.just(\"ok\"); } }";
    inspectJava(
        "sample/src/main/java/sample/ReactorService.java", reactorImport, reactorViolations);
    inspectPom(
        "sample/pom.xml",
        "<dependency><groupId>io.projectreactor</groupId>"
            + "<artifactId>reactor-core</artifactId></dependency>",
        reactorViolations);
    assertTrue(
        reactorViolations.isEmpty(),
        () -> "Reactor must remain a legal non-Redis dependency: " + reactorViolations);

    List<String> redisViolations = new ArrayList<>();
    String redisImport =
        "import "
            + String.join(".", "org", "springframework", "data", "redis", "core", "RedisTemplate")
            + ";";
    inspectJava("sample/src/main/java/sample/RedisStore.java", redisImport, redisViolations);
    inspectPom(
        "sample/pom.xml",
        "<dependency><groupId>org.springframework.boot</groupId>"
            + "<artifactId>spring-boot-starter-data-redis</artifactId></dependency>",
        redisViolations);
    inspectConfiguration(
        "sample/application.yml",
        "url: ${KK_STUDIO_REDIS_URL:redis://localhost:6379}",
        redisViolations);
    assertEquals(
        3, redisViolations.size(), () -> "Redis fixtures must be rejected: " + redisViolations);
  }

  private static void inspectJava(String relative, String source, List<String> violations) {
    boolean inBlockComment = false;
    String[] lines = source.split("\\R");
    for (int index = 0; index < lines.length; index++) {
      String line = lines[index];
      StringBuilder code = new StringBuilder();
      for (int cursor = 0; cursor < line.length(); ) {
        if (inBlockComment) {
          int end = line.indexOf("*/", cursor);
          if (end < 0) {
            cursor = line.length();
          } else {
            inBlockComment = false;
            cursor = end + 2;
          }
        } else if (line.startsWith("//", cursor)) {
          cursor = line.length();
        } else if (line.startsWith("/*", cursor)) {
          inBlockComment = true;
          cursor += 2;
        } else {
          code.append(line.charAt(cursor));
          cursor++;
        }
      }
      String inspected = code.toString();
      boolean forbiddenPackage = FORBIDDEN_JAVA_PACKAGES.stream().anyMatch(inspected::contains);
      if (forbiddenPackage || FORBIDDEN_JAVA_TYPE.matcher(inspected).find()) {
        violations.add(relative + ":" + (index + 1) + ": " + inspected.trim());
      }
    }
  }

  private static void inspectPom(String relative, String source, List<String> violations) {
    inspectLines(
        relative,
        source,
        line ->
            FORBIDDEN_POM_GROUP.matcher(line).find() || FORBIDDEN_POM_ARTIFACT.matcher(line).find(),
        violations);
  }

  private static void inspectConfiguration(
      String relative, String source, List<String> violations) {
    inspectLines(
        relative, source, line -> FORBIDDEN_CONFIGURATION.matcher(line).find(), violations);
  }

  private static void inspectLines(
      String relative, String source, LineViolation predicate, List<String> violations) {
    String[] lines = source.split("\\R");
    for (int index = 0; index < lines.length; index++) {
      if (predicate.matches(lines[index])) {
        violations.add(relative + ":" + (index + 1) + ": " + lines[index].trim());
      }
    }
  }

  private static List<String> gitTrackedFiles(Path root) throws IOException, InterruptedException {
    Process process =
        new ProcessBuilder("git", "-C", root.toString(), "ls-files", "-z")
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int exitCode = process.waitFor();
    if (exitCode != 0) {
      throw new IllegalStateException("git ls-files failed with exit " + exitCode + ": " + output);
    }
    return Arrays.stream(output.split("\\x00")).filter(path -> !path.isBlank()).sorted().toList();
  }

  private static boolean isExcluded(String relative) {
    return EXCLUDED_ROOTS.stream().anyMatch(relative::startsWith);
  }

  private static boolean isProductionJava(String relative) {
    return relative.endsWith(".java") && relative.contains("/src/main/java/");
  }

  private static boolean isConfiguration(String relative) {
    String lower = relative.toLowerCase(Locale.ROOT);
    String filename = Path.of(relative).getFileName().toString().toLowerCase(Locale.ROOT);
    return lower.endsWith(".yml")
        || lower.endsWith(".yaml")
        || lower.endsWith(".properties")
        || filename.startsWith(".env")
        || filename.endsWith(".env")
        || filename.contains("dockerfile");
  }

  private static Path repositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath().normalize();
    while (candidate != null) {
      if (Files.isRegularFile(candidate.resolve("pom.xml"))
          && Files.isDirectory(candidate.resolve("platform/src/main/java"))
          && Files.isDirectory(candidate.resolve("web/src/main/java"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException(
        "cannot locate repository root from " + Path.of("").toAbsolutePath().normalize());
  }

  @FunctionalInterface
  private interface LineViolation {

    boolean matches(String line);
  }
}
