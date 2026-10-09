package fun.fengwk.kkstudio.platform.environment.update;

/**
 * Platform 构建版本事实源：由打包期写入的 JAR manifest {@code Implementation-Version} 提供。
 *
 * <p>受管更新目标必须与运行中的 Platform 版本一致，因此这里只暴露真实打包版本；直接运行 classes / 测试时 manifest 缺失，返回空字符串， 由 {@link
 * OfficialDaemonReleaseProvider} 判定为「发布不可用」而不是猜测目标版本。
 */
public final class PlatformBuildInfo {

  private PlatformBuildInfo() {}

  /** 打包版本；未打包运行时为空字符串。 */
  public static String version() {
    String version = PlatformBuildInfo.class.getPackage().getImplementationVersion();
    return version == null ? "" : version.trim();
  }
}
