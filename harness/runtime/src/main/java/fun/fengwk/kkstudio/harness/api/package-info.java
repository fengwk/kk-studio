/**
 * Harness 对外公开的控制、查询和变更订阅契约。
 *
 * <p>变更订阅暴露不可变事件与幂等释放句柄；持久化、LISTEN/NOTIFY channel 与进程内分发都在平台实现，消费方（浏览器事件通道、内部观察者）
 * 不需要知道数据库协议。订阅语义统一为「先注册再读权威当前值」：返回的 cursor 之后的变化保证送达（断线由 resync 覆盖），未知 Thread
 * 直接失败且不遗留注册。提示型事件只表示需要回读，权威事实始终来自快照查询。
 *
 * <p>命令、会话、历史、线程、工具、压缩与交互等公开值按职责放在本包及其子包；Thread 快照中的 generation 中立投影位于 {@code
 * api.generation}。存储、驱动和运行状态机不属于本包。
 */
package fun.fengwk.kkstudio.harness.api;
