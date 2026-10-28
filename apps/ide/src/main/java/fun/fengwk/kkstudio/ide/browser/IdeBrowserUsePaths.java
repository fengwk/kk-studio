package fun.fengwk.kkstudio.ide.browser;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 事件节点 IDE 使用查询的内部 REST 路径与请求上限。 */
public final class IdeBrowserUsePaths {

  /** 认证单跳端点；不经过 owner 路由，eventNodeId 由客户端用 cluster_node 现在时解析。 */
  public static final String INTERNAL_PATH = "/internal/environment-ide/browser-use";

  /** 请求与回执的硬上限；超出即 fail-closed。 */
  public static final int MAX_BODY_BYTES = 16 * 1024;

  private IdeBrowserUsePaths() {}

  /** 精确意图 POST 的 canonical path。 */
  public static String intentPath() {
    return INTERNAL_PATH;
  }

  /** 已绑定使用证明 GET 的 canonical path（含固定顺序的 query）。 */
  public static String activeProofsPath(ConnectionIdentity identity, UUID documentId) {
    return INTERNAL_PATH
        + "?eventNodeId="
        + encode(identity.eventNodeId())
        + "&connectionId="
        + encode(identity.connectionId())
        + "&documentId="
        + encode(documentId);
  }

  private static String encode(UUID value) {
    return URLEncoder.encode(value.toString(), StandardCharsets.UTF_8);
  }
}
