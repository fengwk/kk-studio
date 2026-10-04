package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

/**
 * 宿主可执行程序解析的只读语义：绝对路径、PATH、HOME 前缀与 Windows 后缀。
 *
 * <p>解析只做文件系统探测；这里用真实文件与真实 PATH 验证，不启动任何进程。
 */
class ExecutableResolverTest {

  @TempDir Path root;

  /** 意图：绝对可执行文件被解析为同一路径；缺失、非绝对或不可执行的候选一律为空。 */
  @Test
  void resolvesAbsoluteExecutableAndRejectsInvalid() throws IOException {
    Path tool = executable(root, "run-tool");
    assertEquals(tool.toString(), ExecutableResolver.resolve(tool.toString()).orElseThrow());
    assertTrue(ExecutableResolver.resolve(root.resolve("absent-tool").toString()).isEmpty());
    assertTrue(ExecutableResolver.resolve("relative/tool").isEmpty(), "相对路径不是可执行程序");
    assertTrue(ExecutableResolver.resolve("dir\\tool").isEmpty(), "含分隔符的相对路径也被拒绝");
  }

  /** 意图：POSIX 上普通文本文件即使存在也不是可执行程序。 */
  @Test
  void rejectsNonExecutableFileOnPosix() throws IOException {
    assumeTrue(
        !ExecutableResolver.isWindows()
            && FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        "需要 POSIX 权限位");
    Path plain = Files.writeString(root.resolve("plain-tool"), "not executable");
    Files.setPosixFilePermissions(
        plain, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    assertTrue(ExecutableResolver.resolve(plain.toString()).isEmpty());
  }

  /** 意图：裸命令按 PATH 解析为宿主绝对路径；缺失命令为空。bash 是 Daemon 的既有前置条件。 */
  @Test
  void resolvesBareCommandOnPathAndRejectsAbsentCommand() {
    Path bash =
        Path.of(
            ExecutableResolver.resolve("bash")
                .orElseThrow(() -> new AssertionError("测试环境必须具备 bash 前置条件")));
    assertTrue(bash.isAbsolute(), "PATH 命中必须给出绝对路径");
    assertTrue(Files.isRegularFile(bash));
    assertTrue(
        ExecutableResolver.resolve("kk-studio-absent-command-" + System.nanoTime()).isEmpty());
  }

  /** 意图：HOME 前缀先展开再解析，{@code ~}/{@code $HOME}/{@code ${HOME}} 三种写法等价。 */
  @Test
  void resolvesHomePrefixedExecutable() throws IOException {
    Path home = Files.createDirectories(root.resolve("home"));
    Path tool = executable(Files.createDirectories(home.resolve("bin")), "home-tool");
    String previousHome = System.getProperty("user.home");
    try {
      System.setProperty("user.home", home.toString());
      assertEquals(tool.toString(), ExecutableResolver.resolve("~/bin/home-tool").orElseThrow());
      assertEquals(
          tool.toString(), ExecutableResolver.resolve("$HOME/bin/home-tool").orElseThrow());
      assertEquals(
          tool.toString(), ExecutableResolver.resolve("${HOME}/bin/home-tool").orElseThrow());
    } finally {
      System.setProperty("user.home", previousHome);
    }
  }

  /** 意图：Windows 裸命令只在 PATHEXT 后缀上匹配，未配置时使用平台默认后缀表。 */
  @Test
  void windowsExecutableSuffixesFollowPathext() {
    assertEquals(
        List.of(".EXE", ".CMD", ".BAT", ".COM"),
        ExecutableResolver.windowsExecutableSuffixes(null));
    assertEquals(
        List.of(".exe", ".CMD"), ExecutableResolver.windowsExecutableSuffixes(".exe;CMD;;"));
  }

  private static Path executable(Path directory, String name) throws IOException {
    Path executable = Files.writeString(directory.resolve(name), "#!/bin/sh\nexit 0\n");
    if (!executable.toFile().setExecutable(true)) {
      throw new IllegalStateException("cannot mark executable: " + executable);
    }
    return executable;
  }
}
