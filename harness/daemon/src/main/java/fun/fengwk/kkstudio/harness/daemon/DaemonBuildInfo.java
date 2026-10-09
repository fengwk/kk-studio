package fun.fengwk.kkstudio.harness.daemon;

/**
 * Daemon 构建版本事实源：shaded JAR 的 manifest {@code Implementation-Version}。
 *
 * <p>未打包（直接运行 classes 或测试）时 manifest 版本缺失，返回固定标记 {@code development}。该标记明确表示“不是发布版本”， 因此不能作为受管更新目标。
 */
public final class DaemonBuildInfo {

  /** 未打包运行时的版本标记；明确表示不是官方 Release。 */
  public static final String DEVELOPMENT_VERSION = "development";

  private DaemonBuildInfo() {}

  /** 当前进程的构建版本：manifest {@code Implementation-Version}，缺失时为 {@link #DEVELOPMENT_VERSION}。 */
  public static String version() {
    String version = DaemonBuildInfo.class.getPackage().getImplementationVersion();
    return version == null || version.isBlank() ? DEVELOPMENT_VERSION : version;
  }
}
