package fun.fengwk.kkstudio.platform.catalog.mcp.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * MCP client 包的 SDK 边界守卫。
 *
 * <p>client 包只依赖 JDK、LangChain4j SDK、Jackson 3 与 {@code fun.fengwk.kkstudio.common}，不接触
 * Spring、存储或其它 Platform 包；Jackson 2 core/databind 是 SDK 签名强加的唯一例外（{@code McpToolResultExtractor}
 * 固定接收 {@code com.fasterxml.jackson.databind.JsonNode}），整个 platform 主源码中只有 {@value
 * #JACKSON2_BOUNDARY_FILE} 可以导入它。
 */
class McpClientArchitectureTest {

  private static final String JACKSON2_BOUNDARY_FILE = "McpResultExtractor.java";

  private static final String CLIENT_PACKAGE_PATH =
      "fun/fengwk/kkstudio/platform/catalog/mcp/client";

  /** client 包只导入 JDK、SDK、Jackson 3、common 与自身。 */
  @Test
  void clientSourcesStayOnJdkSdkJackson3AndCommon() throws IOException {
    List<String> violations = new ArrayList<>();
    for (Path file : javaFiles(mainJava().resolve(CLIENT_PACKAGE_PATH))) {
      for (String imported : imports(file)) {
        if (!isJackson2CoreOrDatabind(imported) && !isAllowedClientImport(imported)) {
          violations.add(file.getFileName() + ": " + imported);
        }
      }
    }
    assertTrue(
        violations.isEmpty(), () -> "client imports violate the SDK boundary:\n" + violations);
  }

  /** platform 主源码中只有 SDK 边界文件导入 Jackson 2 core/databind。 */
  @Test
  void onlyResultExtractorImportsJackson2() throws IOException {
    List<String> violations = new ArrayList<>();
    for (Path file : javaFiles(mainJava())) {
      if (JACKSON2_BOUNDARY_FILE.equals(file.getFileName().toString())) {
        continue;
      }
      for (String imported : imports(file)) {
        if (isJackson2CoreOrDatabind(imported)) {
          violations.add(file.getFileName() + ": " + imported);
        }
      }
    }
    assertTrue(
        violations.isEmpty(),
        () -> "Jackson 2 imports outside the MCP SDK boundary:\n" + violations);
  }

  /** Jackson 3 仍复用的 {@code com.fasterxml.jackson.annotation} 不算 Jackson 2。 */
  private static boolean isJackson2CoreOrDatabind(String imported) {
    return imported.startsWith("com.fasterxml.jackson.core.")
        || imported.startsWith("com.fasterxml.jackson.databind.");
  }

  private static boolean isAllowedClientImport(String imported) {
    return imported.startsWith("java.")
        || imported.startsWith("javax.")
        || imported.startsWith("dev.langchain4j.")
        || imported.startsWith("com.fasterxml.jackson.annotation.")
        || imported.startsWith("tools.jackson.")
        || imported.startsWith("fun.fengwk.kkstudio.common.")
        || imported.startsWith("fun.fengwk.kkstudio.platform.catalog.mcp.client.");
  }

  private static List<String> imports(Path file) throws IOException {
    List<String> imports = new ArrayList<>();
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      String trimmed = line.trim();
      if (trimmed.startsWith("import ")) {
        String imported = trimmed.substring("import ".length()).replace(";", "").trim();
        imports.add(
            imported.startsWith("static ") ? imported.substring("static ".length()) : imported);
      }
    }
    return imports;
  }

  private static List<Path> javaFiles(Path directory) throws IOException {
    try (Stream<Path> stream = Files.walk(directory)) {
      return stream.filter(path -> path.toString().endsWith(".java")).toList();
    }
  }

  /** Maven 从模块根目录或 reactor 根目录运行。 */
  private static Path mainJava() {
    Path cwd = Path.of("").toAbsolutePath().normalize();
    for (Path candidate :
        List.of(cwd.resolve("src/main/java"), cwd.resolve("platform/runtime/src/main/java"))) {
      if (Files.isDirectory(candidate.resolve(CLIENT_PACKAGE_PATH))) {
        return candidate;
      }
    }
    throw new IllegalStateException("cannot locate platform main sources from " + cwd);
  }
}
