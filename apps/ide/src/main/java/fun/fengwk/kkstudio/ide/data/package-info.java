/**
 * Server 侧 IDE 复用数据连接的应用边界。
 *
 * <p>每个 Environment 在当前 READY 控制身份下最多一条数据 WebSocket。首次有逻辑流需求时由 {@link
 * fun.fengwk.kkstudio.ide.data.IdeDataConnectionRegistry} 生成一次性绑定票据并经控制通道下发 {@code
 * DATA_BIND}；Daemon 回连后，同一物理连接承载多条 HTTP/WS 逻辑流。
 */
package fun.fengwk.kkstudio.ide.data;
