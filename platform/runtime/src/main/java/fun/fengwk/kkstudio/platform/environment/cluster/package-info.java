/**
 * Environment IDE 的跨节点 owner 单跳路由与内部认证。
 *
 * <p>职责边界：
 *
 * <ul>
 *   <li>{@link fun.fengwk.kkstudio.platform.environment.cluster.ClusterNodeRegistry} 依据 {@code
 *       environment_connection} 的 owner/lease 快照与轻量 {@code cluster_node} 注册表决定 IDE view
 *       元数据命令应本地交付还是单跳转发到 owner 节点；任何 陈旧 owner、无 READY/租约或缺失注册行都不会被路由（fail-closed）。
 *   <li>{@link fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthentication} 为内部
 *       HTTP/WS 请求提供 HMAC 头签名与校验（绑定 method、canonical requestPath、目标实例 UUID、timestamp、nonce 与 30s
 *       重放窗口）。
 *   <li>{@link fun.fengwk.kkstudio.ide.cluster.ClusterEnvironmentIdeService} 是既有 {@code
 *       EnvironmentIdeService} 的组合实现：本地 owner 直接委托本地 delegate，远端 owner 最多一次认证 HTTP 转发，绝不自动重放。
 *   <li>{@link fun.fengwk.kkstudio.ide.cluster.IdeClusterClient} 只是小 metadata JSON 的简洁客户端，
 *       不承载大字节数据；反向数据面（HTTP/WS 逐 exchange）由 gateway 宿主自行流式实现并复用本包的头签名。
 * </ul>
 *
 * <p>约定：内部 HTTP raw path 作为 URL query（{@code /internal/environment-ide/{env}/http?path=<URLEncoded
 * RAW>}）放入 canonical {@code requestPath}，因此不额外使用 {@code X-KK-IDE-Path} 头；签名的 canonical requestPath
 * 已覆盖原始 query 语义。字节永不进入 SQL/NOTIFY/outbox，本包只操作控制元数据。
 */
package fun.fengwk.kkstudio.platform.environment.cluster;
