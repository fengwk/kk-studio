package fun.fengwk.kkstudio.core.ai.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/** 守护 Core 与 framework-free Harness runtime API 的组合/应用边界。 */
class CoreHarnessArchitectureTest {

  private static final String HARNESS_RUNTIME_SPRING =
      "fun.fengwk.kkstudio.harness.runtime.spring.";

  /** Core 不是组合根：main 源码和 pom 不得依赖 runtime-spring。 */
  @Test
  void coreNeverDependsOnRuntimeSpring() throws IOException {
    Path main = locateCoreMainJava();
    List<String> violations = new ArrayList<>();
    try (Stream<Path> paths = Files.walk(main)) {
      for (Path path : paths.filter(candidate -> candidate.toString().endsWith(".java")).toList()) {
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
          String trimmed = line.trim();
          if (trimmed.startsWith("import ")
              && normalizeImport(trimmed).startsWith(HARNESS_RUNTIME_SPRING)) {
            violations.add(relative(main, path) + ": " + trimmed);
          }
        }
      }
    }

    Path pom = locateCorePom(main);
    String pomText = Files.readString(pom, StandardCharsets.UTF_8);
    assertFalse(
        pomText.contains("<artifactId>kk-studio-harness-runtime-spring</artifactId>"),
        "core/pom.xml must not declare kk-studio-harness-runtime-spring");

    assertTrue(
        violations.isEmpty(),
        () -> "Core runtime-spring dependency violations:\n" + String.join("\n", violations));
  }

  /**
   * Core V1 迁移以字节级相同的方式嵌入 runtime-spring schema 源文件：连续的代码块从首行 {@code -- Harness Runtime durable
   * schema.} 一直到整个 runtime-spring 文件末尾（含全部 comment）必须与 runtime-spring 文件完全相等，因此两个 schema
   * 源文件绝不能发生漂移。
   */
  @Test
  void coreV1SchemaEmbedsRuntimeSpringSchemaByteIdentically() throws IOException {
    Path v1 = locateCoreV1Schema();
    Path runtimeSpring = locateRuntimeSpringSchema();
    byte[] v1Bytes = Files.readAllBytes(v1);
    byte[] runtimeSpringBytes = Files.readAllBytes(runtimeSpring);

    int blockStart =
        indexOf(v1Bytes, "-- Harness Runtime durable schema.".getBytes(StandardCharsets.UTF_8));
    assertTrue(blockStart >= 0, "V1 must contain the runtime-spring protocol block start");
    assertTrue(
        v1Bytes.length >= blockStart + runtimeSpringBytes.length,
        "V1 must be long enough to embed the whole runtime-spring schema");
    byte[] embedded =
        Arrays.copyOfRange(v1Bytes, blockStart, blockStart + runtimeSpringBytes.length);

    assertArrayEquals(
        runtimeSpringBytes,
        embedded,
        () ->
            "core V1 harness protocol block must stay byte-identical to "
                + runtimeSpring
                + " ("
                + v1
                + ")");
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i <= haystack.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  private static String normalizeImport(String importLine) {
    String imported = importLine.substring("import ".length()).replace(";", "").trim();
    if (imported.startsWith("static ")) {
      imported = imported.substring("static ".length()).trim();
    }
    return imported;
  }

  private static String relative(Path main, Path path) {
    return main.relativize(path).toString();
  }

  private static Path locateCoreMainJava() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    for (Path candidate :
        List.of(cwd.resolve("src/main/java"), cwd.resolve("core/src/main/java"))) {
      Path normalized = candidate.normalize();
      if (Files.isDirectory(normalized) && !normalized.toString().contains("/target/")) {
        return normalized;
      }
    }
    throw new IllegalStateException("cannot locate core main sources from " + cwd);
  }

  private static Path locateCorePom(Path mainJava) {
    Path moduleRoot = mainJava.getParent().getParent().getParent();
    Path pom = moduleRoot.resolve("pom.xml");
    if (Files.isRegularFile(pom)) {
      return pom;
    }
    Path cwd = Path.of("").toAbsolutePath().normalize();
    Path reactorPom = cwd.resolve("core/pom.xml");
    if (Files.isRegularFile(reactorPom)) {
      return reactorPom;
    }
    throw new IllegalStateException("cannot locate core/pom.xml from " + mainJava + " or " + cwd);
  }

  private static Path locateCoreV1Schema() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    for (Path candidate :
        List.of(
            cwd.resolve("src/main/resources/db/migration/V1__schema.sql"),
            cwd.resolve("core/src/main/resources/db/migration/V1__schema.sql"))) {
      Path normalized = candidate.normalize();
      if (Files.isRegularFile(normalized) && !normalized.toString().contains("/target/")) {
        return normalized;
      }
    }
    throw new IllegalStateException("cannot locate core V1 schema from " + cwd);
  }

  private static Path locateRuntimeSpringSchema() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    for (Path candidate :
        List.of(
            cwd.resolve(
                "../harness/runtime-spring/src/main/resources/fun/fengwk/kkstudio/harness/runtime/"
                    + "spring/postgresql/harness-runtime-schema.sql"),
            cwd.resolve(
                "harness/runtime-spring/src/main/resources/fun/fengwk/kkstudio/harness/runtime/"
                    + "spring/postgresql/harness-runtime-schema.sql"))) {
      Path normalized = candidate.normalize();
      if (Files.isRegularFile(normalized) && !normalized.toString().contains("/target/")) {
        return normalized;
      }
    }
    throw new IllegalStateException("cannot locate runtime-spring schema from " + cwd);
  }
}
