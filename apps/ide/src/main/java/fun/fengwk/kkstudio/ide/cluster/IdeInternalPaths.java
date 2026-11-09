package fun.fengwk.kkstudio.ide.cluster;

import fun.fengwk.kkstudio.common.uri.UrlEncoding;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.dto.EnvironmentIdeReadViewDTO;

/**
 * 内部 IDE REST 的路径契约，供跨节点客户端与内部 controller 共用，避免两侧路径漂移。
 *
 * <p>元数据命令走 {@code /views} 子路径；复用数据面的内部路径由 Web gateway 定义并复用平台集群认证。
 */
public final class IdeInternalPaths {

  /** 内部 IDE 元数据前缀。 */
  public static final String INTERNAL_IDE_PREFIX = "/internal/ide/environments";

  /** 内部 IDE 元数据请求/回执体的硬上限（字节）；请求与响应都必须严格按此上限读取，超出即 fail-closed。 */
  public static final int MAX_METADATA_BODY_BYTES = 16 * 1024;

  private IdeInternalPaths() {}

  public static String environmentPrefix(EnvironmentId environmentId) {
    return INTERNAL_IDE_PREFIX + "/" + environmentId;
  }

  public static String viewsPath(EnvironmentId environmentId) {
    return environmentPrefix(environmentId) + "/views";
  }

  public static String viewPath(
      EnvironmentId environmentId, String viewId, EnvironmentIdeReadViewDTO request) {
    StringBuilder path =
        new StringBuilder(viewsPath(environmentId))
            .append('/')
            .append(viewId)
            .append("?browserSessionId=")
            .append(UrlEncoding.encode(request.getBrowserSessionId()))
            .append("&eventNodeId=")
            .append(UrlEncoding.encode(request.getEventNodeId()))
            .append("&connectionId=")
            .append(UrlEncoding.encode(request.getConnectionId()))
            .append("&claimId=")
            .append(UrlEncoding.encode(request.getClaimId()));
    if (request.getExpectedDaemonInstanceId() != null) {
      path.append("&expectedDaemonInstanceId=")
          .append(UrlEncoding.encode(request.getExpectedDaemonInstanceId()));
    }
    return path.toString();
  }

  public static String directoryPath(EnvironmentId environmentId, String viewId) {
    return viewsPath(environmentId) + "/" + viewId + "/directory";
  }

  public static String terminalPath(EnvironmentId environmentId, String viewId) {
    return viewsPath(environmentId) + "/" + viewId + "/focus-terminal";
  }

  public static String closePath(EnvironmentId environmentId, String viewId) {
    return viewsPath(environmentId) + "/" + viewId + "/close";
  }

  public static String mobileLayoutPath(EnvironmentId environmentId, String viewId) {
    return viewsPath(environmentId) + "/" + viewId + "/apply-mobile-layout";
  }
}
