package fun.fengwk.kkstudio.harness.environment.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

/**
 * {@link DaemonWorkdirSyntax} 的目标 OS 词法校验契约。
 *
 * <p>workdir 必须是目标 Daemon 文件系统上的显式绝对目录文本。校验只做纯词法判断，不解析 Backend 本机路径，也不做 {@code ~}/环境变量展开。本测试按 OS
 * family 给出接受/拒绝矩阵，并单独证明分隔符归一、周边空白、占位符、长度与控制字符行为。
 */
class DaemonWorkdirSyntaxTest {

  private static final DaemonOperatingSystem[] UNIX_FAMILIES = {
    DaemonOperatingSystem.LINUX, DaemonOperatingSystem.MACOS, DaemonOperatingSystem.WSL
  };

  /** Unix family 接受任何以 {@code /} 开头的绝对路径，并原样返回（不做分隔符归一）。 */
  @ParameterizedTest(name = "[{index}] {0} 接受 {1}")
  @MethodSource("acceptedUnixCases")
  void acceptsUnixAbsolutePaths(DaemonOperatingSystem operatingSystem, String workdir) {
    assertEquals(workdir, DaemonWorkdirSyntax.requireAbsolute(workdir, operatingSystem));
  }

  private static Stream<Arguments> acceptedUnixCases() {
    String[] accepted = {
      "/",
      "//",
      "///",
      "/tmp/work",
      "/a b/c",
      "/srv/a$/b",
      "/srv/$1",
      "/srv/100%",
      "/srv/a%-b",
      "/srv/%a-b%/dir"
    };
    return Stream.of(UNIX_FAMILIES)
        .flatMap(os -> Stream.of(accepted).map(path -> Arguments.of(os, path)));
  }

  /** Windows 接受带根目录的 drive path 与带 server+share 的 UNC，两种分隔符都可；返回值统一为 {@code '/'}。 */
  @ParameterizedTest(name = "[{index}] Windows 接受 {0} -> {1}")
  @MethodSource("acceptedWindowsCases")
  void acceptsAndNormalizesWindowsAbsolutePaths(String workdir, String expected) {
    assertEquals(
        expected, DaemonWorkdirSyntax.requireAbsolute(workdir, DaemonOperatingSystem.WINDOWS));
  }

  private static Stream<Arguments> acceptedWindowsCases() {
    return Stream.of(
        Arguments.of("C:/dir", "C:/dir"),
        Arguments.of("C:/", "C:/"),
        Arguments.of("c:/dir", "c:/dir"),
        Arguments.of("C:\\dir", "C:/dir"),
        Arguments.of("C:\\", "C:/"),
        Arguments.of("c:\\dir", "c:/dir"),
        Arguments.of("C:\\dir\\nested", "C:/dir/nested"),
        Arguments.of("C:\\dir/nested", "C:/dir/nested"),
        Arguments.of("\\\\server\\share", "//server/share"),
        Arguments.of("\\\\server\\share\\dir", "//server/share/dir"),
        Arguments.of("//server/share", "//server/share"),
        Arguments.of("//server/share/dir", "//server/share/dir"),
        // 普通含裸 % 的名称不是占位符，Windows 同样接受。
        Arguments.of("C:\\100%", "C:/100%"));
  }

  /** Windows 拒绝 drive-relative、root-relative 与缺少 server/share 的 UNC。 */
  @ParameterizedTest(name = "[{index}] Windows 拒绝 {0}")
  @MethodSource("rejectedWindowsCases")
  void rejectsNonAbsoluteWindowsShapes(String workdir) {
    assertThrows(
        IllegalArgumentException.class,
        () -> DaemonWorkdirSyntax.requireAbsolute(workdir, DaemonOperatingSystem.WINDOWS),
        "Windows 必须拒绝非绝对形状: " + workdir);
  }

  private static Stream<String> rejectedWindowsCases() {
    return Stream.of(
        "C:",
        "C:dir",
        "c:dir",
        "\\dir",
        "/dir",
        "dir",
        "./dir",
        "../dir",
        "\\\\server",
        "\\\\server\\",
        "//server",
        "//server/",
        "~",
        "~\\dir",
        "\\dir\\x");
  }

  /** 跨 OS 形态必须被拒绝：Unix 拒绝 Windows drive/UNC，Windows 拒绝 Unix root。 */
  @ParameterizedTest(name = "[{index}] {0} 拒绝 {1}")
  @MethodSource("crossOsRejections")
  void rejectsShapesOfTheOtherOs(DaemonOperatingSystem operatingSystem, String workdir) {
    assertThrows(
        IllegalArgumentException.class,
        () -> DaemonWorkdirSyntax.requireAbsolute(workdir, operatingSystem),
        operatingSystem + " 必须拒绝另一 OS 的形态: " + workdir);
  }

  private static Stream<Arguments> crossOsRejections() {
    String[] windowsOnly = {"C:/dir", "C:\\dir", "C:\\", "\\\\server\\share", "C:dir", "\\dir"};
    String[] unixOnly = {"/", "//", "/dir", "/tmp/work"};
    return Stream.concat(
        Stream.of(UNIX_FAMILIES)
            .flatMap(os -> Stream.of(windowsOnly).map(path -> Arguments.of(os, path))),
        Stream.of(unixOnly).map(path -> Arguments.of(DaemonOperatingSystem.WINDOWS, path)));
  }

  /**
   * 未展开占位符与相对形态在所有目标 OS 上一律拒绝：{@code ~}、{@code $VAR}、{@code ${VAR}}、{@code %VAR%}。
   *
   * <p>绝对前缀之后拼接的占位符同样拒绝，避免把字面量当作真实目录。
   */
  @ParameterizedTest(name = "[{index}] {0} 拒绝 {1}")
  @MethodSource("rejectedPlaceholdersAndRelativeForms")
  void rejectsUnexpandedPlaceholdersAndRelativeForms(
      DaemonOperatingSystem operatingSystem, String workdir) {
    assertThrows(
        IllegalArgumentException.class,
        () -> DaemonWorkdirSyntax.requireAbsolute(workdir, operatingSystem),
        "必须拒绝未展开占位符/相对形态: " + workdir);
  }

  private static Stream<Arguments> rejectedPlaceholdersAndRelativeForms() {
    String[] rejected = {
      "~/dir",
      "~\\dir",
      "~",
      "$VAR/dir",
      "${VAR}/dir",
      "%VAR%\\dir",
      "/srv/$VAR",
      "/srv/${VAR}",
      "/srv/%VAR%",
      "dir",
      "./dir",
      "../dir"
    };
    return Stream.of(DaemonOperatingSystem.LINUX, DaemonOperatingSystem.WINDOWS)
        .flatMap(os -> Stream.of(rejected).map(path -> Arguments.of(os, path)));
  }

  /** 周边空白是未展开或误粘贴的信号，必须拒绝而不是 strip 后接受。 */
  @ParameterizedTest(name = "[{index}] {0} 拒绝带周边空白的 {1}")
  @MethodSource("surroundingWhitespaceCases")
  void rejectsSurroundingWhitespace(DaemonOperatingSystem operatingSystem, String workdir) {
    assertThrows(
        IllegalArgumentException.class,
        () -> DaemonWorkdirSyntax.requireAbsolute(workdir, operatingSystem),
        "必须拒绝周边空白: " + workdir);
  }

  private static Stream<Arguments> surroundingWhitespaceCases() {
    String[] rejected = {" /dir", "/dir ", "\t/dir", "/dir\n", "  ", "\t", " C:\\dir"};
    return Stream.of(DaemonOperatingSystem.LINUX, DaemonOperatingSystem.WINDOWS)
        .flatMap(os -> Stream.of(rejected).map(path -> Arguments.of(os, path)));
  }

  /** null 与空白 workdir 在任何目标 OS 上都不可接受。 */
  @ParameterizedTest
  @EnumSource(DaemonOperatingSystem.class)
  void rejectsNullAndBlankForEveryOs(DaemonOperatingSystem operatingSystem) {
    assertThrows(
        IllegalArgumentException.class,
        () -> DaemonWorkdirSyntax.requireAbsolute(null, operatingSystem));
    assertThrows(
        IllegalArgumentException.class,
        () -> DaemonWorkdirSyntax.requireAbsolute("", operatingSystem));
    assertThrows(
        IllegalArgumentException.class,
        () -> DaemonWorkdirSyntax.requireAbsolute("   ", operatingSystem));
  }

  /** operatingSystem 是必需的显式输入：缺失即编程错误（而非词法拒绝）。 */
  @Test
  void rejectsNullOperatingSystem() {
    assertThrows(
        NullPointerException.class, () -> DaemonWorkdirSyntax.requireAbsolute("/srv", null));
  }

  /** read path 分类只判断目标 OS 的绝对形状，不误用 workdir 的占位符和周边空白规则。 */
  @Test
  void classifiesAbsolutePathShapeForReadRouting() {
    assertTrue(
        DaemonWorkdirSyntax.isAbsolutePath("/srv/$LITERAL/file", DaemonOperatingSystem.LINUX));
    assertFalse(DaemonWorkdirSyntax.isAbsolutePath("README.md", DaemonOperatingSystem.LINUX));
    assertFalse(DaemonWorkdirSyntax.isAbsolutePath("C:\\repo", DaemonOperatingSystem.LINUX));

    assertTrue(DaemonWorkdirSyntax.isAbsolutePath("C:\\repo\\file", DaemonOperatingSystem.WINDOWS));
    assertTrue(
        DaemonWorkdirSyntax.isAbsolutePath(
            "\\\\server\\share\\file", DaemonOperatingSystem.WINDOWS));
    assertFalse(
        DaemonWorkdirSyntax.isAbsolutePath("\\root-relative", DaemonOperatingSystem.WINDOWS));
    assertFalse(DaemonWorkdirSyntax.isAbsolutePath("C:relative", DaemonOperatingSystem.WINDOWS));
    assertFalse(DaemonWorkdirSyntax.isAbsolutePath(null, DaemonOperatingSystem.WINDOWS));
    assertThrows(
        NullPointerException.class, () -> DaemonWorkdirSyntax.isAbsolutePath("/srv/file", null));
  }

  /** 长度上界含边界：{@code MAX_LENGTH} 合法，多一字符即拒绝。 */
  @Test
  void enforcesLengthBound() {
    String atLimit = "/" + "a".repeat(DaemonWorkdirSyntax.MAX_LENGTH - 1);
    assertEquals(
        atLimit, DaemonWorkdirSyntax.requireAbsolute(atLimit, DaemonOperatingSystem.LINUX));

    String overLimit = "/" + "a".repeat(DaemonWorkdirSyntax.MAX_LENGTH);
    assertThrows(
        IllegalArgumentException.class,
        () -> DaemonWorkdirSyntax.requireAbsolute(overLimit, DaemonOperatingSystem.LINUX));
    assertThrows(
        IllegalArgumentException.class,
        () -> DaemonWorkdirSyntax.requireAbsolute(overLimit, DaemonOperatingSystem.WINDOWS));
  }

  /** 控制字符在两种形态下都拒绝：远端路径不接受不可见字符。 */
  @Test
  void rejectsControlCharacters() {
    assertThrows(
        IllegalArgumentException.class,
        () -> DaemonWorkdirSyntax.requireAbsolute("/srv/a\u0007b", DaemonOperatingSystem.LINUX));
    assertThrows(
        IllegalArgumentException.class,
        () -> DaemonWorkdirSyntax.requireAbsolute("C:\\a\u0007b", DaemonOperatingSystem.WINDOWS));
  }
}
