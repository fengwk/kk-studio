/**
 * 进程内并发准入控制。
 *
 * <p>{@link fun.fengwk.kkstudio.platform.harness.admission.ConcurrencyAdmission}
 * 提供线程安全、非阻塞且无等待队列的信号量准入， 返回幂等释放的 {@link
 * fun.fengwk.kkstudio.platform.harness.admission.ConcurrencyAdmission.Lease} 句柄，供 Model 与 Tool
 * 两个执行网关分别限制本进程的并发执行槽位。本包纯内存运行，不持久化状态，不参与数据库事务，亦不跨进程协调。
 */
package fun.fengwk.kkstudio.platform.harness.admission;
