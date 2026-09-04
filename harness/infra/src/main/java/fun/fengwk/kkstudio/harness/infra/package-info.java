/**
 * 纯 Java Harness Runtime 的 Spring / PostgreSQL 基础设施适配层。
 *
 * <p>本模块实现 {@link HarnessStore} 与进程 wiring，但不拥有 Thread next-step 选择、Turn protocol、retry、Tool
 * sibling aggregation 或任何其他 Agent Loop 业务规则；技术依赖只由直接使用它们的具体 adapter slice 引入。
 *
 * <p>模块内包含四大生产包：
 *
 * <ul>
 *   <li>{@code dispatch}：Work 调度循环，实现 claim-only 短事务、round-robin 轮询、wake 合并、bounded handoff、
 *       executor rejection 归还 claim、stop 生命周期与 Environment {@code nodeInstanceId} 传递；
 *   <li>{@code postgresql}：PostgreSQL 持久化实现，提供七表 durable protocol、严格事务锁序防御、首个数据库故障 poisoning、 事务内
 *       EntryPath 局部缓存、Work claim/lease/wake、Environment route 路由围栏与 NOTIFY 编解码；
 *   <li>{@code realtime}：实时事件传输抽象端口，规范 live projection overlay 语义与 snapshot recovery 契约；
 *   <li>{@code resource}：本地文件内容寻址对象存储，以固定根目录、SHA-256 十六进制为文件名，通过原子硬链接实现 create-only 发布与最终校验。
 * </ul>
 */
package fun.fengwk.kkstudio.harness.infra;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
