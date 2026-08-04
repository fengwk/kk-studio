/**
 * Test-only in-memory reference store and fixtures。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore} 是 {@link
 * fun.fengwk.kkstudio.harness.runtime.store.HarnessStore} 的确定性参考实现，供后续 Processor 与契约测试 复用；{@link
 * fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport} 提供小粒度 fixture builder 与
 * baseline 种子。本包只存在于 test sources，不建立任何生产框架。
 */
package fun.fengwk.kkstudio.harness.runtime.store.testing;
