/**
 * 单一 durable 存储边界。
 *
 * <p>{@link HarnessStore} 是唯一的 Store root，{@link HarnessStore.Transaction} 是唯一的 typed transaction
 * handle。这里不是 Repository / Specification / generic save / UnitOfWork 框架：没有业务 use-case 方法；Session 与
 * Entry 是 append-only 不可变记录，更新只允许修改 Thread / Command / Invocation / Work 的 current
 * state。事务、锁定与唯一性约定见 {@link HarnessStore} 接口 javadoc。
 */
package fun.fengwk.kkstudio.harness.runtime.store;
