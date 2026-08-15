package fun.fengwk.kkstudio.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * {@link EnvironmentWorkspacePath} canonical 相对 wire 路径契约测试（与 DaemonDirectoryCodec 共用同一 validator）。
 */
class EnvironmentWorkspacePathTest {

  @Test
  void acceptsCanonicalRelativePathsAndRoot() {
    assertEquals(".", EnvironmentWorkspacePath.requireCanonicalRelativePath("."));
    assertEquals("a", EnvironmentWorkspacePath.requireCanonicalRelativePath("a"));
    assertEquals("a/b", EnvironmentWorkspacePath.requireCanonicalRelativePath("a/b"));
    assertEquals(
        "projects/web", EnvironmentWorkspacePath.requireCanonicalRelativePath("projects/web"));
    assertEquals(
        "a-b/c_d/e.f", EnvironmentWorkspacePath.requireCanonicalRelativePath("a-b/c_d/e.f"));
    assertEquals(
        "a".repeat(EnvironmentWorkspacePath.MAX_LENGTH),
        EnvironmentWorkspacePath.requireCanonicalRelativePath(
            "a".repeat(EnvironmentWorkspacePath.MAX_LENGTH)));
  }

  @Test
  void rejectsBlankNullAndOversized() {
    assertThrows(
        IllegalArgumentException.class,
        () -> EnvironmentWorkspacePath.requireCanonicalRelativePath(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> EnvironmentWorkspacePath.requireCanonicalRelativePath(""));
    assertThrows(
        IllegalArgumentException.class,
        () -> EnvironmentWorkspacePath.requireCanonicalRelativePath(" "));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            EnvironmentWorkspacePath.requireCanonicalRelativePath(
                "a".repeat(EnvironmentWorkspacePath.MAX_LENGTH + 1)));
  }

  /** absolute / Windows drive / 反斜杠 / 空段 / dot 段 / 控制字符全部拒绝，且 '.' 只在单独出现时表示 root。 */
  @Test
  void rejectsNonCanonicalWireShapes() {
    assertThrows(
        IllegalArgumentException.class,
        () -> EnvironmentWorkspacePath.requireCanonicalRelativePath("/abs"));
    assertThrows(
        IllegalArgumentException.class,
        () -> EnvironmentWorkspacePath.requireCanonicalRelativePath("C:/x"));
    assertThrows(
        IllegalArgumentException.class,
        () -> EnvironmentWorkspacePath.requireCanonicalRelativePath("C:x"));
    assertThrows(
        IllegalArgumentException.class,
        () -> EnvironmentWorkspacePath.requireCanonicalRelativePath("a\\b"));
    assertThrows(
        IllegalArgumentException.class,
        () -> EnvironmentWorkspacePath.requireCanonicalRelativePath("a//b"));
    assertThrows(
        IllegalArgumentException.class,
        () -> EnvironmentWorkspacePath.requireCanonicalRelativePath("a/./b"));
    assertThrows(
        IllegalArgumentException.class,
        () -> EnvironmentWorkspacePath.requireCanonicalRelativePath("a/../b"));
    assertThrows(
        IllegalArgumentException.class,
        () -> EnvironmentWorkspacePath.requireCanonicalRelativePath("a/."));
    assertThrows(
        IllegalArgumentException.class,
        () -> EnvironmentWorkspacePath.requireCanonicalRelativePath("../a"));
    assertThrows(
        IllegalArgumentException.class,
        () -> EnvironmentWorkspacePath.requireCanonicalRelativePath("a\u0007b"));
    assertThrows(
        IllegalArgumentException.class,
        () -> EnvironmentWorkspacePath.requireCanonicalRelativePath("a\nb"));
  }
}
