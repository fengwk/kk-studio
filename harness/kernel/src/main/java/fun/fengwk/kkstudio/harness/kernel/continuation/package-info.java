/**
 * Durable continuation 的最小引用类型。
 *
 * <p>引用只标识 owner 与 blocker。恢复时重新读取事实，不保留线程、调用栈或回调。
 */
package fun.fengwk.kkstudio.harness.kernel.continuation;
