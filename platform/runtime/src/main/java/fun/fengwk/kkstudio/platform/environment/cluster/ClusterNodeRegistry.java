package fun.fengwk.kkstudio.platform.environment.cluster;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

/**
 * 跨节点 IDE owner 路由查询端口。
 *
 * <p>实现必须依据 {@code environment_connection} 的 owner/lease 快照与 {@code cluster_node} 注册表当前事实判定：无
 * READY、租约过期、 owner 注册行缺失或过期、或本节点缺少内部认证秘密时都返回 {@link Optional#empty()}，绝不从请求 Host 推断内部地址，也绝不缓存存活字节。
 */
public interface ClusterNodeRegistry {

  /** 返回当前可单跳交付该 Environment IDE 元数据的 owner 路由；不可路由时返回空。 */
  Optional<ClusterOwnerRoute> route(EnvironmentId environmentId);

  /**
   * 返回指定节点实例当前未过期租约注册的可达内部 origin。
   *
   * <p>只用于节点到节点的内部回拉（例如 oversize realtime payload）。来源必须是数据库现在时仍有效的 {@code cluster_node} 行，绝不接受通知
   * payload、请求 Host 或其它外部输入提供的 URL。
   */
  Optional<URI> liveInternalOrigin(UUID nodeInstanceId);
}
