package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * 针对 {@link OwnerOnlyFiles} 的 owner-only 原语断言：POSIX 创建即 0700/0600，且可信根之下的符号链接、路径逃逸与非目录分量全部失败关闭。
 *
 * <p>受控临时资源的安全性依赖这些原语：权限必须与创建一起提交，链接/逃逸必须在创建前拒绝而不是创建后再修补。
 */
class OwnerOnlyFilesTest {

  @TempDir Path trustedRoot;

  /** 目录必须以 0700 创建并在重复调用时保持 owner-only，不产生“先建后改权限”的窗口。 */
  @Test
  void ensureOwnerOnlyDirectoryCreatesAndConvergesToOwnerOnly() throws IOException {
    assumeTrue(isPosixSupported(), "需要 POSIX 文件系统验证权限位");
    Path directory = trustedRoot.resolve("data").resolve("tmp").resolve("workspaces");

    Path first = OwnerOnlyFiles.ensureOwnerOnlyDirectory(trustedRoot, directory);
    assertEquals(directory, first);

    // 放宽权限后再确保一次：收敛回 0700。
    Files.setPosixFilePermissions(directory, Set.of(PosixFilePermission.OWNER_READ));
    Path second = OwnerOnlyFiles.ensureOwnerOnlyDirectory(trustedRoot, directory);
    assertEquals(directory, second);
    assertEquals(
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE),
        Files.getPosixFilePermissions(directory));
  }

  /** 新建中转文件必须以 owner-only 0600 直接创建。 */
  @Test
  void createOwnerOnlyFileIsOwnerOnly() throws IOException {
    assumeTrue(isPosixSupported(), "需要 POSIX 文件系统验证权限位");
    Path candidate = trustedRoot.resolve("staging.part");

    OwnerOnlyFiles.createOwnerOnlyFile(trustedRoot, candidate);

    assertTrue(Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS));
    assertEquals(
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        Files.getPosixFilePermissions(candidate));
  }

  /** 可信根之下出现符号链接分量时必须失败关闭，绝不跟随链接创建受控产物。 */
  @Test
  void rejectsSymlinkedComponentBelowTrustedRoot() throws IOException {
    assumeTrue(supportsSymlinks(), "需要支持符号链接的文件系统");
    Path outside = Files.createDirectories(trustedRoot.resolve("outside"));
    Path link = trustedRoot.resolve("linked");
    Files.createSymbolicLink(link, outside);

    assertThrows(
        IOException.class,
        () -> OwnerOnlyFiles.createOwnerOnlyDirectory(trustedRoot, link.resolve("child")));
    assertThrows(
        IOException.class,
        () -> OwnerOnlyFiles.createOwnerOnlyFile(trustedRoot, link.resolve("file.part")));
  }

  /** 目标逃出可信根时必须失败关闭，绝不把受控产物写到根外。 */
  @Test
  void rejectsPathEscapingTrustedRoot() {
    Path escaped = trustedRoot.getParent().resolve("outside-the-root");

    assertThrows(
        IOException.class, () -> OwnerOnlyFiles.createOwnerOnlyDirectory(trustedRoot, escaped));
  }

  /** 中间分量已存在但不是目录时必须失败关闭，而不是顺着普通文件继续下探。 */
  @Test
  void rejectsNonDirectoryIntermediateComponent() throws IOException {
    Path file = Files.writeString(trustedRoot.resolve("not-a-dir"), "x");

    assertThrows(
        IOException.class,
        () -> OwnerOnlyFiles.createOwnerOnlyDirectory(trustedRoot, file.resolve("child")));
  }

  /** ensure 目标已存在但不是目录时必须失败关闭，绝不改写已有普通文件。 */
  @Test
  void ensureOwnerOnlyDirectoryRejectsExistingNonDirectory() throws IOException {
    Path file = Files.writeString(trustedRoot.resolve("afile"), "payload");

    assertThrows(
        IOException.class, () -> OwnerOnlyFiles.ensureOwnerOnlyDirectory(trustedRoot, file));
    assertEquals("payload", Files.readString(file), "被拒绝的输入不得被改写");
  }

  private static boolean isPosixSupported() {
    return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
  }

  private static boolean supportsSymlinks() {
    Path probe = null;
    try {
      Path base = Files.createTempDirectory("symlink-probe");
      Path target = Files.createDirectories(base.resolve("target"));
      probe = base.resolve("link");
      Files.createSymbolicLink(probe, target);
      return true;
    } catch (IOException | UnsupportedOperationException error) {
      return false;
    } finally {
      if (probe != null) {
        try {
          Files.deleteIfExists(probe);
          Files.deleteIfExists(probe.getParent());
        } catch (IOException ignored) {
          // 探测目录残留不影响测试结论。
        }
      }
    }
  }
}
