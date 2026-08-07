/**
 * 七表 Harness Runtime durable protocol 与 lossy Work wake hints 的 PostgreSQL 适配。
 *
 * <p>Store 只实现 {@link HarnessStore} primitives；LISTEN/NOTIFY 只降低 dispatch 延迟，periodic polling 仍是
 * recovery path。Agent Loop 决策与 aggregate transitions 仍归纯 Java runtime 所有。
 */
package fun.fengwk.kkstudio.harness.runtime.spring.postgresql;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
