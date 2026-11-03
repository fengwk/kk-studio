/**
 * Harness Runtime 的平台适配层。
 *
 * <p>本包实现 {@link HarnessStore}、Work 分发与实时传输，但不拥有 Thread next-step 选择、Turn protocol、retry、 Tool
 * sibling aggregation 或其他 Agent Loop 业务规则。
 *
 * <p>各子包按职责隔离：
 *
 * <ul>
 *   <li>{@code dispatch}：Work 调度循环，实现 claim-only 短事务、round-robin 轮询、wake 合并、bounded handoff、
 *       executor rejection 归还 claim、stop 生命周期与 Environment {@code nodeInstanceId} 传递；
 *   <li>{@code store}：PostgreSQL 持久化实现，提供 Harness durable protocol、严格事务锁序防御、首个数据库故障 poisoning、 事务内
 *       EntryPath 局部缓存、Work claim/lease/wake、Environment route 路由围栏与 NOTIFY 编解码；
 *   <li>{@code builtin.store}：Builtin Driver 的私有存储与行映射；
 *   <li>{@code realtime}：实时事件传输抽象端口，规范 live projection overlay 语义与 snapshot recovery 契约；
 * </ul>
 */
package fun.fengwk.kkstudio.platform.harness;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
