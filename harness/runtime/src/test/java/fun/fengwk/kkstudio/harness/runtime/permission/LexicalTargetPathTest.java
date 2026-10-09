package fun.fengwk.kkstudio.harness.runtime.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * filesystem-root 坐标契约：Unix 去掉 root 前缀、Windows drive/UNC 保留 root-qualified，折叠 {@code .}/{@code ..}
 * 且不越出 root；坐标交给 JGit gitignore 语义匹配时，锚定 pattern 与 basename/directory pattern 的命中范围必须确定。
 */
class LexicalTargetPathTest {

  @Test
  void detectsSupportedAbsoluteFormsOnly() {
    assertTrue(LexicalTargetPath.isAbsolute("/srv/proj/a.txt"));
    assertTrue(LexicalTargetPath.isAbsolute("/"));
    assertTrue(LexicalTargetPath.isAbsolute("C:/proj/a.txt"));
    assertTrue(LexicalTargetPath.isAbsolute("c:\\proj\\a.txt"));
    assertTrue(LexicalTargetPath.isAbsolute("//server/share/proj/a.txt"));
    assertTrue(LexicalTargetPath.isAbsolute("\\\\server\\share\\proj\\a.txt"));

    assertFalse(LexicalTargetPath.isAbsolute(null));
    assertFalse(LexicalTargetPath.isAbsolute(""));
    assertFalse(LexicalTargetPath.isAbsolute("   "));
    assertFalse(LexicalTargetPath.isAbsolute("src/a.txt"));
    assertFalse(LexicalTargetPath.isAbsolute("./a.txt"));
    assertFalse(LexicalTargetPath.isAbsolute("C:a.txt"));
    assertFalse(LexicalTargetPath.isAbsolute("kkstudio:/resources/abc"));
    assertFalse(LexicalTargetPath.isAbsolute("file:///srv/a.txt"));
    assertFalse(LexicalTargetPath.isAbsolute("https://example.com/a.txt"));
  }

  @Test
  void computesFilesystemRootCoordinates() {
    assertEquals("srv/proj/a.txt", LexicalTargetPath.permissionCoordinate("/srv/proj/a.txt"));
    assertEquals(
        "srv/proj/a.txt", LexicalTargetPath.permissionCoordinate("/srv/proj/./b/../a.txt"));
    assertEquals("", LexicalTargetPath.permissionCoordinate("/"));
    // 折叠不越出 root。
    assertEquals("a.txt", LexicalTargetPath.permissionCoordinate("/../a.txt"));
    assertEquals("a.txt", LexicalTargetPath.permissionCoordinate("/../../a.txt"));
    // Windows drive/UNC 保留 root-qualified 前缀，避免丢失盘符/主机。
    assertEquals("C:/proj/a.txt", LexicalTargetPath.permissionCoordinate("C:\\proj\\a.txt"));
    // drive letter 归一为大写，避免 C:/ 与 c:/ 被当作两个 root。
    assertEquals("C:/proj/a.txt", LexicalTargetPath.permissionCoordinate("c:/proj/a.txt"));
    assertEquals(
        "//server/share/proj/a.txt",
        LexicalTargetPath.permissionCoordinate("\\\\server\\share\\proj\\a.txt"));
  }

  @Test
  void rejectsNonAbsoluteCoordinates() {
    assertThrows(
        IllegalArgumentException.class, () -> LexicalTargetPath.permissionCoordinate("src/a.txt"));
    assertThrows(IllegalArgumentException.class, () -> LexicalTargetPath.permissionCoordinate(""));
    assertThrows(
        IllegalArgumentException.class, () -> LexicalTargetPath.permissionCoordinate("C:a.txt"));
  }

  @Test
  void matchesJGitPatternsAgainstRootCoordinates() {
    String unix = LexicalTargetPath.permissionCoordinate("/srv/proj/a.txt");
    assertTrue(PermissionPathPattern.of("srv/proj/**").matches(unix, false));
    assertTrue(PermissionPathPattern.of("/srv/proj/**").matches(unix, false));
    assertTrue(PermissionPathPattern.of("**/a.txt").matches(unix, false));
    assertTrue(PermissionPathPattern.of("a.txt").matches(unix, false));
    // 带内部分隔符的 pattern 锚定 filesystem root，`proj/**` 不命中 `srv/proj/a.txt`。
    assertFalse(PermissionPathPattern.of("proj/**").matches(unix, false));

    String windows = LexicalTargetPath.permissionCoordinate("C:/repo/a.txt");
    assertTrue(PermissionPathPattern.of("C:/repo/**").matches(windows, false));
    assertTrue(PermissionPathPattern.of("**/a.txt").matches(windows, false));
    assertFalse(PermissionPathPattern.of("repo/**").matches(windows, false));

    String unc = LexicalTargetPath.permissionCoordinate("//server/share/repo/a.txt");
    assertTrue(PermissionPathPattern.of("//server/share/**").matches(unc, false));
  }
}
