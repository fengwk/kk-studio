package fun.fengwk.kkstudio.platform.environment.cluster;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

/**
 * 内部 IDE 元数据 REST 的路径契约，供跨节点客户端与内部 controller 共用，避免两侧路径漂移。
 *
 * <p>反向数据面（HTTP/WS 逐 exchange）的 {@code /http} 与 {@code /ws} 子路径由 gateway 宿主实现，并复用同一认证端口；本类只声明
 * 元数据命令路径。
 */
public final class ClusterInternalPaths {

  /** 内部 IDE 元数据前缀。 */
  public static final String INTERNAL_IDE_PREFIX = "/internal/environment-ide";

  private ClusterInternalPaths() {}

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

  public static String viewPath(EnvironmentId environmentId, String viewId) {
    return viewsPath(environmentId) + "/" + viewId;
  }
}
