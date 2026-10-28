package fun.fengwk.kkstudio.ide.cluster;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

/**
 * 内部 IDE REST 的路径契约，供跨节点客户端与内部 controller 共用，避免两侧路径漂移。
 *
 * <p>元数据命令走 {@code /views} 子路径；复用数据面的内部路径由 Web gateway 定义并复用平台集群认证。
 */
public final class IdeInternalPaths {

  /** 内部 IDE 元数据前缀。 */
  public static final String INTERNAL_IDE_PREFIX = "/internal/environment-ide";

  /** 内部 IDE 元数据请求/回执体的硬上限（字节）；请求与响应都必须严格按此上限读取，超出即 fail-closed。 */
  public static final int MAX_METADATA_BODY_BYTES = 16 * 1024;

  private IdeInternalPaths() {}

  public static String environmentPrefix(EnvironmentId environmentId) {
    return INTERNAL_IDE_PREFIX + "/" + environmentId;
  }

  public static String viewsPath(EnvironmentId environmentId) {
    return environmentPrefix(environmentId) + "/views";
  }

  public static String directoryPath(EnvironmentId environmentId, String viewId) {
    return viewsPath(environmentId) + "/" + viewId + "/directory";
  }

  public static String terminalPath(EnvironmentId environmentId, String viewId) {
    return viewsPath(environmentId) + "/" + viewId + "/terminal";
  }

  public static String detachPath(EnvironmentId environmentId, String viewId) {
    return viewsPath(environmentId) + "/" + viewId + "/detach";
  }

  public static String viewPath(EnvironmentId environmentId, String viewId) {
    return viewsPath(environmentId) + "/" + viewId;
  }
}
