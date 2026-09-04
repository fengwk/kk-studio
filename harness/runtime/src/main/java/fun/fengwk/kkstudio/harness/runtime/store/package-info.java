/**
 * 单一持久化存储边界。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.runtime.store.HarnessStore} 是唯一的 Store 根，{@link
 * fun.fengwk.kkstudio.harness.runtime.store.HarnessStore.Transaction} 是唯一的 typed transaction
 * handle。这里不是 Repository / Specification / generic save / UnitOfWork 框架：没有业务 use-case 方法；Session 与
 * Entry 是 append-only 不可变记录，更新只允许修改 Thread / Command / Invocation / Work 的 current
 * state。事务、锁定与唯一性约定见 {@link fun.fengwk.kkstudio.harness.runtime.store.HarnessStore} 接口 javadoc。
 *
 * <p>多实体锁顺序（所有多行事务必须遵守，防止死锁）：Session -&gt; Thread -&gt; Commands -&gt; ModelInvocation -&gt; 同
 * Assistant Entry 的 ToolInvocation siblings（按 callIndex 升序）-&gt; Work；同一事务锁多行 Work 时，同层 Work 必须按
 * (type, id) 升序（例如先 THREAD Work 再 MODEL Work）；单实体 heartbeat 类调度事务只锁 Work。
 *
 * <p>Thread / ModelInvocation / ToolInvocation 的更新必须通过 aggregate 共享 transition validation （{@code
 * ThreadState.validateTransition} / {@code ModelInvocation.validateTransition} / {@code
 * ToolInvocation.validateTransition}）：直接 record 构造只允许用于 persistence decode，非法状态机跳跃与 terminal
 * 回退/改写不能进入 store。Work 的 {@code requiredEnvironmentId} 在首次创建时冻结并在调度状态跃迁中完整保留，claim 阶段由存储实现按节点 READY
 * 租约实施路由围栏。
 */
package fun.fengwk.kkstudio.harness.runtime.store;
