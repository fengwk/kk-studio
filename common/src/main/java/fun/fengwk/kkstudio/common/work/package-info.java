/**
 * Durable Work 的纯调度状态机：只表达 due、wake 版本、lease 与环境亲和性这些跨领域共享事实。
 *
 * <p>{@link fun.fengwk.kkstudio.common.work.WorkState} 不依赖任何领域 target 类型、Harness 枚举或 Environment
 * 值对象；所有跃迁都是接收 {@code now} 的纯函数。内存 Store 与 PostgreSQL 实现必须调用同一组方法，调用方只负责提供各自权威时间域中的 {@code
 * now}（生产实现为数据库时间）。
 */
package fun.fengwk.kkstudio.common.work;
