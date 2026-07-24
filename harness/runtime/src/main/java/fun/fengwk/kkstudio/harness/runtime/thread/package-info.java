/**
 * Durable AgentThread 执行面板、有序 ThreadInput 队列与 ThreadKick 激活契约。
 *
 * <p>Session 只是共享 append-only Entry Tree；Thread 持有 head 游标、冻结配置与 YOLO。同一 Thread 跨节点单飞；不同 Thread
 * 可并行并自然分叉。Turn 仅为运行时概念，不持久化。执行由 ThreadKick → ThreadActivationDispatcher / ThreadReconciler 推进。
 */
package fun.fengwk.kkstudio.harness.runtime.thread;
