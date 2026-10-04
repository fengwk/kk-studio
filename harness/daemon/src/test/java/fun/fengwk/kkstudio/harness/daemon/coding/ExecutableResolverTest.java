package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
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
 * <p>解析只做文件系统探测；这里用真实文件与注入的 PATH/PATHEXT 验证，不启动任何进程。 Windows 语义通过包内注入入口确定性模拟，不依赖宿主真实平台。
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
        List.of(".EXE", ".CMD", ".BAT", ".COM"),
        ExecutableResolver.windowsExecutableSuffixes("  "));
    assertEquals(
        List.of(".exe", ".CMD"), ExecutableResolver.windowsExecutableSuffixes(".exe;CMD;;"));
  }

  /** 意图：{@code ~}/{@code ~\}/{@code $HOME}/{@code ${HOME}} 前缀展开，其它输入原样保留。 */
  @Test
  void expandHomeCoversAllPrefixes() {
    String previousHome = System.getProperty("user.home");
    try {
      System.setProperty("user.home", "/acme-home");
      assertEquals("/acme-home", ExecutableResolver.expandHome("~"));
      assertEquals("/acme-home/bin", ExecutableResolver.expandHome("~/bin"));
      assertEquals("/acme-home\\bin", ExecutableResolver.expandHome("~\\bin"));
      assertEquals("/acme-home/bin", ExecutableResolver.expandHome("$HOME/bin"));
      assertEquals("/acme-home\\bin", ExecutableResolver.expandHome("$HOME\\bin"));
      assertEquals("/acme-home/bin", ExecutableResolver.expandHome("${HOME}/bin"));
      assertEquals("/acme-home\\bin", ExecutableResolver.expandHome("${HOME}\\bin"));
      assertEquals("plain", ExecutableResolver.expandHome("plain"));
    } finally {
      System.setProperty("user.home", previousHome);
    }
  }

  /** 意图：PATH 为空/全空白时失败关闭；空 PATH 条目被跳过但后续有效条目仍可命中。 */
  @Test
  void blankPathAndElementsFailClosed() throws IOException {
    assertTrue(ExecutableResolver.resolve("tool", false, null, null).isEmpty());
    assertTrue(ExecutableResolver.resolve("tool", false, "   ", null).isEmpty());
    Path directory = root.toAbsolutePath().normalize();
    Path tool = executable(directory, "tool");
    String withBlankElements = File.pathSeparator + File.pathSeparator + directory;
    assertEquals(tool.toString(), resolve("tool", false, withBlankElements, null));
  }

  /** 意图：POSIX PATH 查找中普通文本文件不是可执行程序。 */
  @Test
  void posixPathLookupRejectsNonExecutableFixture() throws IOException {
    assumeTrue(
        !ExecutableResolver.isWindows()
            && FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        "需要 POSIX 权限位");
    Path directory = root.toAbsolutePath().normalize();
    Path plain = Files.writeString(directory.resolve("plain"), "not executable");
    Files.setPosixFilePermissions(
        plain, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    assertTrue(ExecutableResolver.resolve("plain", false, directory.toString(), null).isEmpty());
  }

  /**
   * 意图：Windows 上已带被识别后缀（大小写不敏感）的命令按原样解析，绝不追加 PATHEXT；未知后缀才追加查找。
   *
   * <p>用注入的 PATH/PATHEXT 与临时夹具确定性模拟，避免依赖宿主平台。
   */
  @Test
  void windowsResolvesExplicitSuffixWithoutAppendingPathext() throws IOException {
    Files.writeString(root.resolve("bash.exe"), "stub");
    Files.writeString(root.resolve("bash.EXE"), "stub");
    Files.writeString(root.resolve("tool.exe"), "stub");
    String path = root.toString();
    String pathext = ".EXE;.CMD";
    // 已识别后缀（.exe/.EXE）按原样解析：若错误追加后缀会查成 bash.exe.EXE 而落空。
    assertEquals(root.resolve("bash.exe").toString(), resolve("bash.exe", true, path, pathext));
    assertEquals(root.resolve("bash.EXE").toString(), resolve("bash.EXE", true, path, pathext));
    assertEquals(root.resolve("tool.exe").toString(), resolve("tool.exe", true, path, pathext));
    // 裸命令按 PATHEXT 顺序追加后缀，命中第一个存在项（.EXE 先于 .CMD）；未配置时用默认后缀表。
    assertEquals(root.resolve("bash.EXE").toString(), resolve("bash", true, path, pathext));
    assertEquals(root.resolve("bash.EXE").toString(), resolve("bash", true, path, null));
    // 未知后缀不算已识别，按追加语义也找不到文件。
    assertTrue(ExecutableResolver.resolve("bash.ps1", true, path, pathext).isEmpty());
    assertTrue(ExecutableResolver.resolve("missing", true, path, pathext).isEmpty());
    // 已识别后缀但文件缺失：按原样解析失败，不回退到追加后缀。
    assertTrue(ExecutableResolver.resolve("absent.exe", true, path, pathext).isEmpty());
    // 比任一后缀都短的命令不构成已识别后缀。
    assertTrue(ExecutableResolver.resolve("a", true, path, pathext).isEmpty());
  }

  /** 意图：相对 PATH 条目必须解析为绝对候选，避免子进程按自身 workdir 误解析相对程序。 */
  @Test
  void relativePathEntriesResolveToAbsoluteCandidate() throws IOException {
    Path directory = root.toAbsolutePath().normalize();
    Path tool = executable(directory, "rel-tool");
    Path cwd = Path.of("").toAbsolutePath().normalize();
    assumeTrue(
        cwd.getRoot() != null && cwd.getRoot().equals(directory.getRoot()),
        "需要同一文件系统根以构造相对 PATH 条目");
    String relative = cwd.relativize(directory).toString();
    Path resolved = Path.of(resolve("rel-tool", false, relative, null));
    assertTrue(resolved.isAbsolute(), "相对 PATH 条目必须解析为绝对路径");
    assertEquals(tool.toAbsolutePath().normalize().toString(), resolved.toString());
  }

  /** 意图：宿主不允许的路径来源（NUL）收敛为空，不抛出携带原始取值的异常。 */
  @Test
  void invalidPathSourcesFailClosedWithoutLeaking() {
    assertTrue(ExecutableResolver.resolve("bad\u0000/name").isEmpty(), "非法命令路径应为空");
    assertTrue(
        ExecutableResolver.resolve("bad-tool", false, "\u0000invalid", null).isEmpty(),
        "非法 PATH 条目应被跳过而非抛出");
    assertTrue(
        ExecutableResolver.resolve("bad-tool", true, "\u0000invalid", null).isEmpty(),
        "Windows 语义下非法 PATH 条目同样跳过");
  }

  private static String resolve(String command, boolean windows, String path, String pathext) {
    return ExecutableResolver.resolve(command, windows, path, pathext)
        .orElseThrow(() -> new AssertionError("expected resolution for " + command));
  }

  private static Path executable(Path directory, String name) throws IOException {
    Path executable = Files.writeString(directory.resolve(name), "#!/bin/sh\nexit 0\n");
    if (!executable.toFile().setExecutable(true)) {
      throw new IllegalStateException("cannot mark executable: " + executable);
    }
    return executable;
  }
}
