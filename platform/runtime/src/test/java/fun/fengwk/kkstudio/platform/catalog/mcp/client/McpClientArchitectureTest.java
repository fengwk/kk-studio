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
 * <p>client 包只依赖 JDK、官方 MCP Java SDK、Jackson 3 与 {@code fun.fengwk.kkstudio.common}，不接触
 * Spring、存储或其它 Platform 包；不允许再引入 LangChain4j 或 Jackson 2 core/databind。
 */
class McpClientArchitectureTest {

  private static final String CLIENT_PACKAGE_PATH =
      "fun/fengwk/kkstudio/platform/catalog/mcp/client";

  /** client 包只导入 JDK、官方 SDK、Jackson 3、common 与自身。 */
  @Test
  void clientSourcesStayOnJdkSdkJackson3AndCommon() throws IOException {
    List<String> violations = new ArrayList<>();
    for (Path file : javaFiles(mainJava().resolve(CLIENT_PACKAGE_PATH))) {
      for (String imported : imports(file)) {
        if (!isAllowedClientImport(imported)) {
          violations.add(file.getFileName() + ": " + imported);
        }
      }
    }
    assertTrue(
        violations.isEmpty(), () -> "client imports violate the SDK boundary:\n" + violations);
  }

  private static boolean isAllowedClientImport(String imported) {
    return imported.startsWith("java.")
        || imported.startsWith("javax.")
        || imported.startsWith("io.modelcontextprotocol.")
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
