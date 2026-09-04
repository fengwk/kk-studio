/**
 * THREAD / MODEL / TOOL 调度共享的单一 Work 调度邮箱。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.runtime.work.Work} 是一个 target 的持久化当前调度状态，并独占拥有 scheduling
 * lease 与所有权围栏： {@code wake_version} 防止 wake 丢失，{@code lease_token} / {@code lease_until}
 * 形成所有权围栏拦截过期的 worker。 它不是事件日志、不是任务队列、也不是 repository 聚合；其中不存放 target 业务状态、attempt、result 或
 * approval。
 *
 * <p>环境亲和性（Environment affinity）：{@code requiredEnvironmentId} 仅允许 TOOL Work 非空；在 Work 首次创建时冻结，
 * 并在后续 request / claim / renew / complete / reschedule 的纯函数跃迁与持久化中完整保留；THREAD 与 MODEL Work 必须为空，
 * server-side TOOL 可为空。Runtime 只在 Work 行上携带该路由要求，具体节点分发由基础设施层按节点匹配 READY 且租约未过期的连接进行路由围栏。
 */
package fun.fengwk.kkstudio.harness.runtime.work;
