/**
 * Harness 对外公开的消费契约：Thread version、执行树与待处理交互的变更订阅。
 *
 * <p>契约只暴露不可变事件与幂等释放句柄；持久化、LISTEN/NOTIFY channel 与进程内分发都在平台实现，消费方（浏览器事件通道、内部观察者）
 * 不需要知道数据库协议。订阅语义统一为「先注册再读权威当前值」：返回的 cursor 之后的变化保证送达（断线由 resync 覆盖），未知 Thread
 * 直接失败且不遗留注册。提示型事件只表示需要回读，权威事实始终来自快照查询。
 *
 * <p>控制面入参的公开值类型在同一边界的子包按职责细分，见 {@code api.thread}。
 */
package fun.fengwk.kkstudio.harness.api;
