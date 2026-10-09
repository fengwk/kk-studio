package fun.fengwk.kkstudio.harness.daemon.process;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
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

  /** 目录里存在删不掉的成员时，清理必须无副作用地放弃，而不是把异常抛给调用方。 */
  @Test
  void deleteQuietlyToleratesMembersThatCannotBeRemoved() throws Exception {
    Path directory = unwritableDirectory();
    Path member = directory.resolve(MEMBER_FILE);
    try {
      assertDoesNotThrow(() -> ProcessScopeState.deleteQuietly(directory));
      assertTrue(Files.exists(member), "删不掉的成员必须原样留下，而不是被报告为已清理");
    } finally {
      restoreWritable(directory);
      ProcessScopeState.deleteQuietly(directory);
      assertFalse(Files.exists(directory));
    }
  }

  /** 清理是幂等的：没有目录、目录已经消失都不算失败。 */
  @Test
  void deleteQuietlyIsIdempotentForMissingDirectories() throws Exception {
    Path directory = stateDir.resolve("already-gone");
    assertDoesNotThrow(() -> ProcessScopeState.deleteQuietly(null));
    assertDoesNotThrow(() -> ProcessScopeState.deleteQuietly(directory));
    Files.createDirectories(directory.resolve("nested"));
    Files.writeString(directory.resolve("nested").resolve("file"), "x", StandardCharsets.UTF_8);
    ProcessScopeState.deleteQuietly(directory);
    assertFalse(Files.exists(directory));
    assertDoesNotThrow(() -> ProcessScopeState.deleteQuietly(directory));
  }

  private static final String MEMBER_FILE = "member";

  /**
   * 启动规格必须对命令参数里的换行、回车、引号、反斜杠与 Unicode 无损，并保留空参数。
   *
   * <p>这些字符正是「用分隔符编码」会出错的输入：按行分隔会把含换行的参数拆成两个，空参数会被吞掉。JSON 编码对这些输入无损。
   */
  @Test
  void launchSpecRoundTripsHostileArguments() {
    List<String> command =
        List.of(
            "sh",
            "-c",
            "printf 'a\\nb'\n",
            "carriage\rreturn",
            "quote'\"",
            "back\\slash",
            "中文🙂",
            "",
            "tab\t");
    ProcessScopeState.publishLaunch(stateDir, stateDir.toAbsolutePath(), command);
    ProcessScopeState.LaunchSpec spec = ProcessScopeState.readLaunch(stateDir);
    assertNotNull(spec);
    assertEquals(stateDir.toAbsolutePath().toString(), spec.workdir());
    assertEquals(command, spec.command(), "argv 必须逐元素无损还原，包括空参数");
  }

  /** 读取启动规格必须删除它：规格承载用户命令的 argv，不能留在磁盘上。 */
  @Test
  void readLaunchDeletesTheSpecification() {
    ProcessScopeState.publishLaunch(
        stateDir, stateDir.toAbsolutePath(), List.of("sh", "-c", "true"));
    assertNotNull(ProcessScopeState.readLaunch(stateDir));
    assertFalse(Files.exists(stateDir.resolve(ProcessScopeState.LAUNCH_FILE)));
    assertNull(ProcessScopeState.readLaunch(stateDir), "删除之后必须按「还没有发布」处理");
  }

  /** argv 中出现 NUL 时必须显式拒绝，绝不静默改写。 */
  @Test
  void launchSpecRejectsNulArguments() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ProcessScopeState.publishLaunch(stateDir, stateDir, List.of("sh", "-c", "a\0b")));
  }

  /** 不可解析的载荷必须显式失败，且失败原因不回显载荷内容（里面是用户命令的 argv）。 */
  @Test
  void readLaunchDoesNotEchoAnUnreadablePayload() throws Exception {
    Path file = stateDir.resolve(ProcessScopeState.LAUNCH_FILE);
    Files.writeString(file, "secret-argv-token", StandardCharsets.UTF_8);
    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> ProcessScopeState.readLaunch(stateDir));
    assertFalse(failure.getMessage().contains("secret-argv-token"), failure.getMessage());
    assertFalse(Files.exists(file), "解析失败也必须立即删除携带 argv 的载荷");
  }

  /** 启动规格不能发布时必须显式失败，且不能留下目标文件。 */
  @Test
  void launchSpecPublicationFailsClosedWhenTheStateDirectoryIsMissing() {
    Path missing = stateDir.resolve("missing");
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> ProcessScopeState.publishLaunch(missing, stateDir, List.of("secret-argv-token")));
    assertTrue(failure.getMessage().contains("cannot publish the helper launch spec"));
    assertFalse(failure.getMessage().contains("secret-argv-token"));
    assertFalse(Files.exists(missing.resolve(ProcessScopeState.LAUNCH_FILE)));
  }

  /** 可解析但不完整的 JSON 不能成为启动命令的依据，读取后同样删除。 */
  @Test
  void readLaunchRejectsAnIncompleteSpecification() throws Exception {
    Path file = stateDir.resolve(ProcessScopeState.LAUNCH_FILE);
    Files.writeString(file, "{\"command\":[\"secret-argv-token\"]}", StandardCharsets.UTF_8);
    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> ProcessScopeState.readLaunch(stateDir));
    assertEquals("the helper launch spec is incomplete", failure.getMessage());
    assertFalse(Files.exists(file));
  }

  /** POSIX 上启动规格必须是属主可读写：它承载用户命令的 argv，不能暴露给同机其它用户。 */
  @Test
  void launchSpecIsOwnerOnlyOnPosix() throws Exception {
    assumeTrue(supportsPosixPermissions(), "需要 POSIX 权限才能表达属主可读写");
    ProcessScopeState.publishLaunch(stateDir, stateDir, List.of("sh", "-c", "true"));
    Path file = stateDir.resolve(ProcessScopeState.LAUNCH_FILE);
    assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
  }

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
