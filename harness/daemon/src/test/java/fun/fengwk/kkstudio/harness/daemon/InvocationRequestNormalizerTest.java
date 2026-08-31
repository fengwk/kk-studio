package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** InvocationRequestNormalizer 的独立单元测试。 */
class InvocationRequestNormalizerTest {

  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  /** 验证 '.' 能正确规范化为 Environment Root 自身的 canonical 真实路径。 */
  @Test
  void resolvesRootWorkspaceSuccessfully(@TempDir Path tempDir) throws IOException {
    Path root = tempDir.toRealPath();
    InvocationRequestNormalizer normalizer = new InvocationRequestNormalizer(root, DEFAULT_TIMEOUT);

    Path resolved = normalizer.canonicalWorkspace(".");

    assertEquals(root, resolved);
  }

  /** 验证根目录下的多层有效相对子目录路径（如 projects/web）能正确解析为 canonical 真实路径。 */
  @Test
  void resolvesNestedWorkspaceSuccessfully(@TempDir Path tempDir) throws IOException {
    Path root = tempDir.toRealPath();
    Path nested = Files.createDirectories(root.resolve("projects").resolve("web"));
    InvocationRequestNormalizer normalizer = new InvocationRequestNormalizer(root, DEFAULT_TIMEOUT);

    Path resolved = normalizer.canonicalWorkspace("projects/web");

    assertEquals(nested.toRealPath(), resolved);
  }

  /** 验证当符号链接目标仍在 Environment Root 内部时，能正常解析为目标真实路径。 */
  @Test
  void resolvesInternalSymlinkSuccessfully(@TempDir Path tempDir) throws IOException {
    Path root = tempDir.toRealPath();
    Path targetDir = Files.createDirectories(root.resolve("target-dir"));
    Path link = root.resolve("link-dir");
    try {
      Files.createSymbolicLink(link, targetDir);
    } catch (UnsupportedOperationException | IOException | SecurityException error) {
      abort("symbolic links are not supported in this environment: " + error.getMessage());
    }
    InvocationRequestNormalizer normalizer = new InvocationRequestNormalizer(root, DEFAULT_TIMEOUT);

    Path resolved = normalizer.canonicalWorkspace("link-dir");

    assertEquals(targetDir.toRealPath(), resolved);
  }

  /** 验证不存在的 workspace 路径会抛出包含原路径名且包装 IOException 的 IllegalArgumentException。 */
  @Test
  void rejectsNonExistentWorkspace(@TempDir Path tempDir) throws IOException {
    Path root = tempDir.toRealPath();
    InvocationRequestNormalizer normalizer = new InvocationRequestNormalizer(root, DEFAULT_TIMEOUT);

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> normalizer.canonicalWorkspace("non-existent-dir"));

    assertTrue(
        exception
            .getMessage()
            .contains("workspace does not resolve to an existing directory: non-existent-dir"),
        exception.getMessage());
    assertNotNull(exception.getCause());
    assertTrue(exception.getCause() instanceof IOException);
  }

  /** 验证指向普通文件的 workspace 路径会抛出 IllegalArgumentException 并明确指示不是目录。 */
  @Test
  void rejectsFileWorkspace(@TempDir Path tempDir) throws IOException {
    Path root = tempDir.toRealPath();
    Files.writeString(root.resolve("file.txt"), "hello");
    InvocationRequestNormalizer normalizer = new InvocationRequestNormalizer(root, DEFAULT_TIMEOUT);

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class, () -> normalizer.canonicalWorkspace("file.txt"));

    assertEquals("workspace is not a directory: file.txt", exception.getMessage());
  }

  /** 验证通过符号链接逃逸到 Environment Root 外部的 workspace 路径会被拒绝。 */
  @Test
  void rejectsEscapingSymlinkWorkspace(@TempDir Path tempDir, @TempDir Path outsideTempDir)
      throws IOException {
    Path root = tempDir.toRealPath();
    Path outside = outsideTempDir.toRealPath();
    Path link = root.resolve("escape");
    try {
      Files.createSymbolicLink(link, outside);
    } catch (UnsupportedOperationException | IOException | SecurityException error) {
      abort("symbolic links are not supported in this environment: " + error.getMessage());
    }
    InvocationRequestNormalizer normalizer = new InvocationRequestNormalizer(root, DEFAULT_TIMEOUT);

    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> normalizer.canonicalWorkspace("escape"));

    assertEquals("workspace escapes environment root: escape", exception.getMessage());
  }

  /** 验证非规范形状（绝对路径、Windows 盘符、反斜杠、.. 路径段、控制字符、空串等）在 wire 校验层直接被拒绝。 */
  @Test
  void rejectsInvalidWorkspaceShapes(@TempDir Path tempDir) throws IOException {
    Path root = tempDir.toRealPath();
    InvocationRequestNormalizer normalizer = new InvocationRequestNormalizer(root, DEFAULT_TIMEOUT);

    String[] invalidShapes = {
      "/abs", "C:/web", "C:\\web", "a\\b", "a/../b", "a/./b", "a//b", "", "   ", "a\u0007b"
    };

    for (String shape : invalidShapes) {
      assertThrows(
          IllegalArgumentException.class,
          () -> normalizer.canonicalWorkspace(shape),
          "expected rejection for shape: " + shape);
    }
  }

  /** 验证超时解析第一层 fallback：显式 requested timeout（非 0）优先级最高。 */
  @Test
  void resolvesExplicitRequestedTimeout(@TempDir Path tempDir) throws IOException {
    Path root = tempDir.toRealPath();
    InvocationRequestNormalizer normalizer =
        new InvocationRequestNormalizer(root, Duration.ofSeconds(30));

    EnvironmentCapabilityDescriptor descriptorWithTimeout =
        createDescriptor(Duration.ofSeconds(10));
    EnvironmentCapabilityDescriptor descriptorWithZeroTimeout = createDescriptor(Duration.ZERO);

    assertEquals(
        Duration.ofSeconds(5),
        normalizer.resolveTimeout(Duration.ofSeconds(5), descriptorWithTimeout));
    assertEquals(
        Duration.ofSeconds(5),
        normalizer.resolveTimeout(Duration.ofSeconds(5), descriptorWithZeroTimeout));
  }

  /** 验证超时解析第二层 fallback：requested 为 0 时回退至 capability descriptor 声明的超时。 */
  @Test
  void fallsBackToDescriptorTimeoutWhenRequestTimeoutIsZero(@TempDir Path tempDir)
      throws IOException {
    Path root = tempDir.toRealPath();
    InvocationRequestNormalizer normalizer =
        new InvocationRequestNormalizer(root, Duration.ofSeconds(30));

    EnvironmentCapabilityDescriptor descriptor = createDescriptor(Duration.ofSeconds(15));

    assertEquals(Duration.ofSeconds(15), normalizer.resolveTimeout(Duration.ZERO, descriptor));
  }

  /** 验证超时解析第三层 fallback：requested 与 descriptor 均为 0 时回退至 daemon default timeout。 */
  @Test
  void fallsBackToDaemonDefaultTimeoutWhenRequestAndDescriptorAreZero(@TempDir Path tempDir)
      throws IOException {
    Path root = tempDir.toRealPath();
    InvocationRequestNormalizer normalizer =
        new InvocationRequestNormalizer(root, Duration.ofSeconds(45));

    EnvironmentCapabilityDescriptor descriptor = createDescriptor(Duration.ZERO);

    assertEquals(Duration.ofSeconds(45), normalizer.resolveTimeout(Duration.ZERO, descriptor));
  }

  /** 验证构造函数与各方法对 null 参数的防御性检查。 */
  @Test
  void constructorAndMethodNullChecks(@TempDir Path tempDir) throws IOException {
    Path root = tempDir.toRealPath();
    assertThrows(
        NullPointerException.class, () -> new InvocationRequestNormalizer(null, DEFAULT_TIMEOUT));
    assertThrows(NullPointerException.class, () -> new InvocationRequestNormalizer(root, null));

    InvocationRequestNormalizer normalizer = new InvocationRequestNormalizer(root, DEFAULT_TIMEOUT);
    assertThrows(IllegalArgumentException.class, () -> normalizer.canonicalWorkspace(null));

    EnvironmentCapabilityDescriptor descriptor = createDescriptor(Duration.ofSeconds(10));
    assertThrows(NullPointerException.class, () -> normalizer.resolveTimeout(null, descriptor));
    assertThrows(
        NullPointerException.class, () -> normalizer.resolveTimeout(Duration.ofSeconds(5), null));
  }

  private static EnvironmentCapabilityDescriptor createDescriptor(Duration timeout) {
    return new EnvironmentCapabilityDescriptor(
        new EnvironmentCapabilityId("test"),
        "1.0.0",
        new InputSchema("test description", Map.of(), Set.of(), false),
        timeout);
  }
}
