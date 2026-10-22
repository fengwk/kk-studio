package fun.fengwk.kkstudio.platform.environment.cluster;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

/**
 * 一个 Environment 当前 READY owner 的路由快照：目标节点实例 UUID、可达内部 origin 与本节点标志。
 *
 * <p>即使 {@code local} 为 true，该快照也必须来自「数据库现在时确认 READY 且租约未过期」的连接行；{@code local} 只表示无需跨节点 转发。非 local
 * 路由一定携带可达的内部 origin；本地路由的 {@code internalUrl} 允许为 null。
 */
public record ClusterOwnerRoute(UUID nodeInstanceId, URI internalUrl, boolean local) {

  public ClusterOwnerRoute {
    nodeInstanceId = Objects.requireNonNull(nodeInstanceId, "nodeInstanceId");
    if (!local) {
      internalUrl = Objects.requireNonNull(internalUrl, "internalUrl");
    }
  }

  /** 本节点即 owner 的本地路由。 */
  public static ClusterOwnerRoute local(UUID nodeInstanceId) {
    return new ClusterOwnerRoute(nodeInstanceId, null, true);
  }

  /** 远端 owner 的单跳路由。 */
  public static ClusterOwnerRoute remote(UUID nodeInstanceId, URI internalUrl) {
    return new ClusterOwnerRoute(nodeInstanceId, internalUrl, false);
  }
}
