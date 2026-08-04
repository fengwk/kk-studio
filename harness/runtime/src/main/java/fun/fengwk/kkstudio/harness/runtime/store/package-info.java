/**
 * 单一 durable 存储边界。
 *
 * <p>{@link HarnessStore} 是唯一的 Store root，{@link HarnessStore.Transaction} 是唯一的 typed transaction
 * handle。这里不是 Repository / Specification / generic save / UnitOfWork 框架：没有业务 use-case 方法；Session 与
 * Entry 是 append-only 不可变记录，更新只允许修改 Thread / Command / Invocation / Work 的 current
 * state。事务、锁定与唯一性约定见 {@link HarnessStore} 接口 javadoc。
 *
 * <p>多实体锁顺序（所有多行事务必须遵守，防止死锁）：Thread -&gt; Commands -&gt; ModelInvocation -&gt; 同 Assistant Entry 的
 * ToolInvocation siblings（按 ordinal 升序）-&gt; Work；单实体 heartbeat 类事务只锁 Work。
 *
 * <p>Thread / ModelInvocation / ToolInvocation 的更新必须通过 aggregate 共享 transition validation （{@code
 * ThreadState.validateTransition} / {@code ModelInvocation.validateTransition} / {@code
 * ToolInvocation.validateTransition}）：直接 record 构造只允许用于 persistence decode，非法状态机跳跃与 terminal
 * 回退/改写不能进入 store。
 */
package fun.fengwk.kkstudio.harness.runtime.store;
