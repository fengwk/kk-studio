/**
 * Durable AgentThread 执行面板、有序 ThreadInput 队列、ThreadEvent journal 与事件触发的 ThreadProcessor。
 *
 * <p>Session 只是共享 append-only Entry Tree；Thread 持有 head 游标、冻结配置、YOLO 与 processor fencing。 同一 Thread
 * 跨节点单飞；不同 Thread 可并行并自然分叉。Turn 仅为运行时概念，不持久化。
 */
package fun.fengwk.kkstudio.harness.runtime.thread;
