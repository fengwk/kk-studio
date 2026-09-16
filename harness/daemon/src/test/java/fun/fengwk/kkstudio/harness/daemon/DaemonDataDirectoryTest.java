package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 针对 {@link DaemonDataDirectory} 的行为断言测试。
 *
 * <p>覆盖目录结构创建、POSIX 0700 权限收敛、进程内与跨 JVM 进程排他锁、重启后锁释放、启动期孤儿 .part 文件清理与已发布持久化数据保留、以及 defaultRoot
 * 纯路径计算等语义。暂存文件的 owner-only 创建语义由 {@code TextOutputStoreTest} 覆盖（生产路径只经 {@code TextOutputStore}
 * 落盘）。
 */
class DaemonDataDirectoryTest {

  @TempDir Path dataDir;

  /** 验证 open 创建完整的固定目录布局，各 accessor 指向正确路径，且在 POSIX 系统上所有目录权限显式收敛为 0700 (owner-only)。 */
  @Test
  void openCreatesFullLayoutAndOwnerOnlyDirectories() throws IOException {
    try (DaemonDataDirectory dir = DaemonDataDirectory.open(dataDir)) {
      Path lockFile = dataDir.resolve(DaemonDataDirectory.LOCK_FILE_NAME);
      Path resourcesDir = dataDir.resolve("resources");
      Path textDir = resourcesDir.resolve("text");
      Path stagingDir = resourcesDir.resolve("staging");
      Path blobsDir = resourcesDir.resolve("blobs");

      assertEquals(dataDir.toAbsolutePath().normalize(), dir.root());
      assertEquals(resourcesDir, dir.resources());
      assertEquals(textDir, dir.text());
      assertEquals(stagingDir, dir.staging());
      assertEquals(blobsDir, dir.blobs());

      assertTrue(Files.isRegularFile(lockFile, LinkOption.NOFOLLOW_LINKS), "daemon.lock 必须为普通文件");
      assertTrue(Files.isDirectory(dir.root(), LinkOption.NOFOLLOW_LINKS), "root 必须为目录");
      assertTrue(Files.isDirectory(dir.resources(), LinkOption.NOFOLLOW_LINKS), "resources 必须为目录");
      assertTrue(Files.isDirectory(dir.text(), LinkOption.NOFOLLOW_LINKS), "text 必须为目录");
      assertTrue(Files.isDirectory(dir.staging(), LinkOption.NOFOLLOW_LINKS), "staging 必须为目录");
      assertTrue(Files.isDirectory(dir.blobs(), LinkOption.NOFOLLOW_LINKS), "blobs 必须为目录");

      if (isPosixSupported()) {
        Set<PosixFilePermission> ownerOnlyDirPerms =
            Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE);
        assertEquals(ownerOnlyDirPerms, Files.getPosixFilePermissions(dir.root()));
        assertEquals(ownerOnlyDirPerms, Files.getPosixFilePermissions(dir.resources()));
        assertEquals(ownerOnlyDirPerms, Files.getPosixFilePermissions(dir.text()));
        assertEquals(ownerOnlyDirPerms, Files.getPosixFilePermissions(dir.staging()));
        assertEquals(ownerOnlyDirPerms, Files.getPosixFilePermissions(dir.blobs()));
      }
    }
  }

  /** 验证同进程内对同一数据目录的二次 open 必定失败抛出 IllegalStateException，且在原句柄关闭后新 open 可以重新成功加锁。 */
  @Test
  void mutualExclusionInProcessAndReacquireOnClose() {
    DaemonDataDirectory firstHandle = DaemonDataDirectory.open(dataDir);
    assertNotNull(firstHandle);

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> DaemonDataDirectory.open(dataDir));
    assertTrue(
        error.getMessage().contains("already in use"), "二次打开同一目录应提示已被占用: " + error.getMessage());

    firstHandle.close();

    // 释放后重新打开必须成功（模拟 daemon 重启）
    try (DaemonDataDirectory secondHandle = DaemonDataDirectory.open(dataDir)) {
      assertEquals(dataDir.toAbsolutePath().normalize(), secondHandle.root());
    }
  }

  /** 验证 DaemonDataDirectory 具备真正的跨 JVM 进程互斥性：父进程持有期间子进程打开失败退出非零；父进程释放后子进程打开成功退出 0。 */
  @Test
  void mutualExclusionCrossProcess() throws Exception {
    String javaBin =
        ProcessHandle.current()
            .info()
            .command()
            .orElseGet(() -> Path.of(System.getProperty("java.home"), "bin", "java").toString());
    String classPath = System.getProperty("java.class.path");

    try (DaemonDataDirectory parentHandle = DaemonDataDirectory.open(dataDir)) {
      Process childWhileLocked =
          new ProcessBuilder(
                  javaBin, "-cp", classPath, LockProbeMain.class.getName(), dataDir.toString())
              .redirectErrorStream(true)
              .start();

      boolean finishedWhileLocked = childWhileLocked.waitFor(5, TimeUnit.SECONDS);
      assertTrue(finishedWhileLocked, "子进程在父进程持有锁期间应在 5 秒内退出");
      assertEquals(1, childWhileLocked.exitValue(), "父进程持有锁时子进程必须获取失败退出非零");
    }

    // 父进程关闭并释放锁后，子进程重新尝试应成功获取锁退出 0
    Process childAfterUnlock =
        new ProcessBuilder(
                javaBin, "-cp", classPath, LockProbeMain.class.getName(), dataDir.toString())
            .redirectErrorStream(true)
            .start();

    boolean finishedAfterUnlock = childAfterUnlock.waitFor(5, TimeUnit.SECONDS);
    assertTrue(finishedAfterUnlock, "子进程在锁释放后应在 5 秒内退出");
    assertEquals(0, childAfterUnlock.exitValue(), "父进程释放锁后子进程必须成功获取锁退出 0");
  }

  /** 验证 open 启动时仅清理 staging 目录下的残留 *.part 暂存文件，已发布的 text 日志与 blobs 数据内容完整保留。 */
  @Test
  void staleStagingPartCleanupPreservesPublishedData() throws IOException {
    Path stagingDir = dataDir.resolve("resources").resolve("staging");
    Path textDir = dataDir.resolve("resources").resolve("text");
    Path blobsDir = dataDir.resolve("resources").resolve("blobs");
    Files.createDirectories(stagingDir);
    Files.createDirectories(textDir);
    Files.createDirectories(blobsDir);

    Path stalePartFile = stagingDir.resolve("leftover-abc123.part");
    Path publishedLogFile = textDir.resolve("existing.log");
    Path publishedBlobFile = blobsDir.resolve("blob-hash-123456");

    Files.writeString(stalePartFile, "uncommitted partial stream data");
    Files.writeString(publishedLogFile, "durable published log line 1\nline 2\n");
    Files.writeString(publishedBlobFile, "immutable blob binary payload");

    try (DaemonDataDirectory dir = DaemonDataDirectory.open(dataDir)) {
      assertFalse(Files.exists(stalePartFile), "未完成的 staging .part 文件必须在 open 时被清理");
      assertTrue(Files.exists(publishedLogFile), "已发布的 text 文件不得被删除");
      assertEquals(
          "durable published log line 1\nline 2\n",
          Files.readString(publishedLogFile),
          "已发布的 text 文件内容必须保持不变");
      assertTrue(Files.exists(publishedBlobFile), "已发布的 blobs 数据不得被删除");
      assertEquals(
          "immutable blob binary payload",
          Files.readString(publishedBlobFile),
          "已发布的 blobs 数据内容必须保持不变");
    }
  }

  /** 验证 defaultRoot 纯计算规范化绝对路径而不产生任何磁盘副作用（调用后目标路径在磁盘上不存在）。 */
  @Test
  void defaultRootComputesNormalizedPathWithoutCreatingDiskSideEffects() {
    String originalHome = System.getProperty("user.home");
    try {
      System.setProperty("user.home", dataDir.toString());
      Path defaultRoot = DaemonDataDirectory.defaultRoot();
      Path expected = dataDir.resolve(".kk-studio").toAbsolutePath().normalize();

      assertEquals(expected, defaultRoot);
      assertTrue(defaultRoot.isAbsolute(), "defaultRoot 必须是绝对路径");
      assertEquals(defaultRoot, defaultRoot.normalize(), "defaultRoot 必须是规范化路径");
      assertFalse(Files.exists(defaultRoot), "defaultRoot 纯计算路径，不得在磁盘上创建任何目录或文件");
    } finally {
      if (originalHome != null) {
        System.setProperty("user.home", originalHome);
      } else {
        System.clearProperty("user.home");
      }
    }
  }

  private static boolean isPosixSupported() {
    return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
  }

  /** 供跨进程互斥排他性测试调用的子进程入口。 */
  public static final class LockProbeMain {
    public static void main(String[] args) {
      if (args.length == 0) {
        System.exit(2);
      }
      try (DaemonDataDirectory ignored = DaemonDataDirectory.open(Path.of(args[0]))) {
        System.exit(0);
      } catch (Exception error) {
        System.exit(1);
      }
    }
  }
}
