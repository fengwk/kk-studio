/**
 * 浏览器应用事件连接上的 IDE 使用意图与已确认持有证明。
 *
 * <p>事件节点只在本机内存按物理连接身份（{@code eventNodeId,connectionId}）维护一个 document 的完整使用列表：每次 {@code ide-use}
 * 原子替换或幂等确认，断线立即撤销该连接的全部证明。没有 {@code bindingEpoch} 的条目只是打开/恢复意图，不构成续租证明。
 *
 * <p>{@link fun.fengwk.kkstudio.ide.browser.IdeBrowserUseClient} 供 owner 在 claim 前精确查询意图、在心跳中读取已确认
 * epoch： 本节点直接读本地登记，跨节点只经 {@code cluster_node} 当前租约解析出的单一事件节点并用内部 HMAC 认证；连接与 document 标识都不是凭据，
 * 查询失败不会被当成活跃。
 */
package fun.fengwk.kkstudio.ide.browser;
