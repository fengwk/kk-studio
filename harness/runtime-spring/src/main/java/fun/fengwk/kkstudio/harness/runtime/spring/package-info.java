/**
 * 纯 Java Harness Runtime 的 Spring / PostgreSQL / Redis 基础设施适配层。
 *
 * <p>本模块实现 {@link HarnessStore} 与进程 wiring，但不拥有 Thread next-step 选择、Turn protocol、retry、Tool
 * sibling aggregation 或任何其他 Agent Loop business rule；技术依赖只由直接使用它们的具体 adapter slice 引入。
 */
package fun.fengwk.kkstudio.harness.runtime.spring;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
