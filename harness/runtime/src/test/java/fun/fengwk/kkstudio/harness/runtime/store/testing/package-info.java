/**
 * 仅用于测试的内存版参考 store 与 fixture。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore} 是 {@link
 * fun.fengwk.kkstudio.harness.runtime.store.HarnessStore} 的确定性参考实现。事务核心 {@link
 * fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessTransaction} 只持有唯一复制快照 {@link
 * fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryState}、事务时钟、id 分配与锁阶梯；九个聚合原语分别在 {@code
 * InMemorySessionStore}、{@code InMemoryEntryStore}、{@code InMemoryThreadStore}、{@code
 * InMemoryCommandStore}、{@code InMemoryToolStore}、{@code InMemoryWorkStore}、{@code
 * InMemoryJoinStore}、{@code InMemoryStopReceiptStore} 与 {@code InMemoryExecutionExitStore}
 * 中实现，Driver 私有视图经 {@link
 * fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessTransaction} 的公开测试 hook
 * 在同一快照内挂载。{@link fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport} 提供小粒度 fixture
 * builder 与 baseline 种子。 本包只存在于 test sources，不建立任何生产框架。
 */
package fun.fengwk.kkstudio.harness.runtime.store.testing;
