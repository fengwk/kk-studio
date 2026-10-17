package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.daemon.DaemonOperatingSystemDetector;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * {@link ProcessScopeState} 真实失败形态的回归：握手面只承载调用私有的握手文件，但它的每次失败要么显式、要么无副作用。
 *
 * <p>这里注入的是文件系统本身拒绝操作（目录不可写、文件不可读、成员删不掉），不是测试专用的生产分支：发布失败必须让调用方
 * 显式失败且不留半个文件，读取失败必须按「还没有发布」处理，清理失败必须不影响调用结果。权限表达不出这些失败的环境（例如 root 身份）会让用例跳过，而不是伪造结论。
 */
class ProcessScopeStateTest {

  @TempDir Path stateDir;

  /** 目标目录不可写时：显式失败、指名目标文件，并且不留下中转文件。 */
  @Test
  void publishReportsAnUnwritableTargetInsteadOfHalfWritingIt() throws Exception {
    Path directory = unwritableDirectory();
    try {
      IllegalStateException failure =
          assertThrows(
              IllegalStateException.class,
              () -> ProcessScopeState.publish(directory, ProcessScopeState.EXIT_FILE, "0"));
      assertTrue(failure.getMessage().contains(ProcessScopeState.EXIT_FILE), failure.getMessage());
      assertFalse(Files.exists(directory.resolve(ProcessScopeState.EXIT_FILE)));
      assertFalse(Files.exists(directory.resolve(ProcessScopeState.EXIT_FILE + ".tmp")));
    } finally {
      restoreWritable(directory);
    }
  }

  /** 失败报告本身的发布失败没有第二个渠道，只能被吞掉；它绝不能盖过真正的失败原因。 */
  @Test
  void publishQuietlySwallowsAnUnwritableDirectory() throws Exception {
    Path directory = unwritableDirectory();
    try {
      assertDoesNotThrow(
          () ->
              ProcessScopeState.publishQuietly(
                  directory, ProcessScopeState.ERROR_FILE, "command-start-failure"));
      assertFalse(Files.exists(directory.resolve(ProcessScopeState.ERROR_FILE)));
    } finally {
      restoreWritable(directory);
    }
  }

  /** 发布成功时读者只能看到完整文件：中转文件已经被改名，而不是留在目录里。 */
  @Test
  void publishLeavesNoTemporaryFileBehind() throws Exception {
    ProcessScopeState.publish(stateDir, ProcessScopeState.CLEANUP_FILE, "true");
    assertEquals("true", ProcessScopeState.read(stateDir, ProcessScopeState.CLEANUP_FILE));
    try (Stream<Path> entries = Files.list(stateDir)) {
      assertEquals(
          List.of(ProcessScopeState.CLEANUP_FILE),
          entries.map(path -> path.getFileName().toString()).toList());
    }
  }

  /** 文件存在但读不到时按「还没有发布」处理：读取失败绝不抛出，判定权留给调用方的启动预算。 */
  @Test
  void readTreatsAnUnreadableFileAsNotPublished() throws Exception {
    Path file = stateDir.resolve(ProcessScopeState.PERMIT_FILE);
    Files.writeString(file, "present", StandardCharsets.UTF_8);
    assumeTrue(supportsPosixPermissions(), "需要 POSIX 权限才能表达「文件存在但读不到」");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
    try {
      assumeTrue(!Files.isReadable(file), "当前身份可以绕过文件权限，无法表达这条失败");
      assertNull(ProcessScopeState.read(stateDir, ProcessScopeState.PERMIT_FILE));
    } finally {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }
  }

  /** 目录里存在删不掉的成员时，清理必须无副作用地放弃并如实报告未删干净，而不是把异常抛给调用方。 */
  @Test
  void deleteQuietlyToleratesMembersThatCannotBeRemoved() throws Exception {
    Path directory = unwritableDirectory();
    Path member = directory.resolve(MEMBER_FILE);
    try {
      assertFalse(ProcessScopeState.deleteQuietly(directory), "还有成员没删掉时必须如实返回未清理完成");
      assertTrue(Files.exists(member), "删不掉的成员必须原样留下，而不是被报告为已清理");
    } finally {
      restoreWritable(directory);
      assertTrue(ProcessScopeState.deleteQuietly(directory), "恢复可写后清理必须完成");
      assertFalse(Files.exists(directory));
    }
  }

  /** 清理是幂等的：没有目录、目录已经消失都不算失败，并如实返回已清理完成。 */
  @Test
  void deleteQuietlyIsIdempotentForMissingDirectories() throws Exception {
    Path directory = stateDir.resolve("already-gone");
    assertTrue(ProcessScopeState.deleteQuietly(null), "null 目录视为已经清理完成");
    assertTrue(ProcessScopeState.deleteQuietly(directory), "不存在的目录视为已经清理完成");
    Files.createDirectories(directory.resolve("nested"));
    Files.writeString(directory.resolve("nested").resolve("file"), "x", StandardCharsets.UTF_8);
    assertTrue(ProcessScopeState.deleteQuietly(directory), "现有目录必须被完整清理");
    assertFalse(Files.exists(directory));
    assertTrue(ProcessScopeState.deleteQuietly(directory), "重复清理仍然报告已经清理完成");
  }

  /** Windows 的真实共享句柄释放后，已经等待中的清理必须完成，而不是留下状态目录。 */
  @Test
  void deleteWaitsForAReleasedWindowsFileHandle() throws Exception {
    assumeWindows();
    Path directory = Files.createDirectory(stateDir.resolve("released-handle"));
    Path file = directory.resolve(ProcessScopeState.DIAGNOSTICS_FILE);
    AtomicReference<Boolean> deleted = new AtomicReference<>();
    Thread cleanup = new Thread(() -> deleted.set(ProcessScopeState.deleteQuietly(directory)));
    try (FileOutputStream held = new FileOutputStream(file.toFile())) {
      held.write(1);
      assertThrows(IOException.class, () -> Files.delete(file), "必须先证明真实文件句柄阻止删除");
      cleanup.start();
      awaitDeletionWait(cleanup);
      assertNull(deleted.get(), "句柄未释放时不得报告清理完成");
    } finally {
      cleanup.join(TimeUnit.SECONDS.toMillis(2));
    }
    assertFalse(cleanup.isAlive(), "清理线程必须有界结束");
    assertEquals(Boolean.TRUE, deleted.get());
    assertFalse(Files.exists(directory));
  }

  /** 持续占用必须有界失败，保留中断标记，释放后的重复清理仍可成功。 */
  @Test
  void deleteReportsPersistentWindowsSharingFailureAndPreservesInterruption() throws Exception {
    assumeWindows();
    Path directory = Files.createDirectory(stateDir.resolve("held-handle"));
    Path file = directory.resolve(ProcessScopeState.DIAGNOSTICS_FILE);
    try (FileOutputStream held = new FileOutputStream(file.toFile())) {
      held.write(1);
      assertThrows(IOException.class, () -> Files.delete(file));
      Thread.currentThread().interrupt();
      long started = System.nanoTime();
      try {
        assertFalse(ProcessScopeState.deleteQuietly(directory), "持续共享冲突不得报告成功");
        assertTrue(Thread.currentThread().isInterrupted(), "收尾必须恢复调用方的中断标记");
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2), "失败必须有界");
      } finally {
        Thread.interrupted();
      }
      assertTrue(Files.exists(file), "仍被占用的文件必须保留");
    }
    assertTrue(ProcessScopeState.deleteQuietly(directory));
    assertFalse(Files.exists(directory));
  }

  private static void assumeWindows() {
    assumeTrue(
        DaemonOperatingSystemDetector.detectCurrent() == DaemonOperatingSystem.WINDOWS,
        "需要 Windows 共享句柄语义");
  }

  /** 只在清理线程真正进入文件释放等待后才释放句柄，不依赖睡眠时长猜测同步点。 */
  private static void awaitDeletionWait(Thread cleanup) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (System.nanoTime() < deadline) {
      if (cleanup.getState() == Thread.State.TIMED_WAITING) {
        for (StackTraceElement frame : cleanup.getStackTrace()) {
          if (frame.getClassName().equals(ProcessScopeState.class.getName())
              && frame.getMethodName().equals("deleteQuietly")) {
            return;
          }
        }
      }
      Thread.sleep(1);
    }
    throw new AssertionError("清理线程未进入文件释放等待");
  }

  private static final String MEMBER_FILE = "member";

  /** 建一个带成员的目录再去掉写权限；权限表达不了「不可写」时用例被跳过。 */
  private Path unwritableDirectory() throws IOException {
    Path directory = stateDir.resolve("unwritable");
    Files.createDirectories(directory);
    Files.writeString(directory.resolve(MEMBER_FILE), MEMBER_FILE, StandardCharsets.UTF_8);
    assumeTrue(supportsPosixPermissions(), "需要 POSIX 权限才能表达「目录不可写」");
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-xr-xr-x"));
    Path probe = directory.resolve("probe");
    try {
      Files.writeString(probe, "probe", StandardCharsets.UTF_8);
    } catch (IOException expected) {
      return directory;
    } finally {
      Files.deleteIfExists(probe);
    }
    restoreWritable(directory);
    assumeTrue(false, "当前身份可以绕过目录权限，无法表达这条失败");
    return directory;
  }

  private boolean supportsPosixPermissions() throws IOException {
    return Files.getFileStore(stateDir).supportsFileAttributeView(PosixFileAttributeView.class);
  }

  private static void restoreWritable(Path directory) throws IOException {
    if (Files.getFileStore(directory).supportsFileAttributeView(PosixFileAttributeView.class)) {
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    }
  }
}
