/**
 * 七表 Harness Runtime durable protocol、lossy Work wake hints 与 realtime notification overlay 的
 * PostgreSQL 适配。
 *
 * <p>Store 只实现 {@link HarnessStore} primitives；LISTEN/NOTIFY 只降低 dispatch 延迟，periodic polling 仍是
 * recovery path；realtime notification 遗漏或损坏时由 durable snapshot 恢复。Agent Loop 决策与 aggregate
 * transitions 仍归纯 Java runtime 所有。
 */
package fun.fengwk.kkstudio.harness.infra.postgresql;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
