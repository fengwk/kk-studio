package fun.fengwk.kkstudio.ide.cluster;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 内部 IDE REST 的路径契约，供跨节点客户端与内部 controller 共用，避免两侧路径漂移。
 *
 * <p>元数据命令走 {@code /views} 子路径；反向数据面（HTTP/WS 逐 exchange）的 {@code /http}、{@code /ws} 与 {@code
 * /reverse} 子路径由 gateway 宿主实现，并复用平台的集群认证。
 */
public final class IdeInternalPaths {

  /** 内部 IDE 元数据前缀。 */
  public static final String INTERNAL_IDE_PREFIX = "/internal/environment-ide";

  /** 内部 IDE 元数据请求/回执体的硬上限（字节）；请求与响应都必须严格按此上限读取，超出即 fail-closed。 */
  public static final int MAX_METADATA_BODY_BYTES = 16 * 1024;

  /** 内部数据面入口的子路径。 */
  public static final String HTTP_SUFFIX = "/http";

  public static final String WS_SUFFIX = "/ws";

  /** 内部数据面入口承载浏览器原始 path/query 的查询参数名；原始值整体参与 HMAC 签名。 */
  public static final String PATH_PARAMETER = "path";

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

  /**
   * 非 owner → owner 的浏览器 HTTP 数据面单跳入口：{@code
   * /internal/environment-ide/{env}/http?path=<URLencoded原始path/query>}。
   *
   * <p>原始 path/query 只出现在签名覆盖的 query 里，绝不放进未签名的额外 header。
   */
  public static String dataHttpPath(EnvironmentId environmentId, String rawPathAndQuery) {
    return environmentPrefix(environmentId)
        + HTTP_SUFFIX
        + "?"
        + encodePathParameter(rawPathAndQuery);
  }

  /** 非 owner → owner 的浏览器 WS 数据面单跳入口，形如 {@link #dataHttpPath} 但落在 {@code /ws}。 */
  public static String dataWsPath(EnvironmentId environmentId, String rawPathAndQuery) {
    return environmentPrefix(environmentId)
        + WS_SUFFIX
        + "?"
        + encodePathParameter(rawPathAndQuery);
  }

  private static String encodePathParameter(String rawPathAndQuery) {
    return PATH_PARAMETER + "=" + URLEncoder.encode(rawPathAndQuery, StandardCharsets.UTF_8);
  }

  /**
   * 非 owner 节点转发 Daemon 反向数据连接的单跳入口：{@code
   * /internal/environment-ide/{env}/reverse?ticket=<opaque>}。
   *
   * <p>ticket 是不透明的 256-bit 一次性凭据，由 owner 铸造并绑定其代际；转发方只做有界帧透传，不解释也不重放。canonical path（含 query）整体参与
   * HMAC 签名，因此这里返回的字符串必须与发送端逐字一致。
   */
  public static String reversePath(EnvironmentId environmentId, String ticket) {
    return environmentPrefix(environmentId) + "/reverse?ticket=" + ticket;
  }
}
