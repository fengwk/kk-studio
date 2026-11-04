/**
 * Thread 级公开值类型：控制请求，以及一致性加锁下的 Thread 快照投影。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.api.thread.StopCommand}、{@link
 * fun.fengwk.kkstudio.harness.api.thread.SetThreadYoloCommand} 与 {@link
 * fun.fengwk.kkstudio.harness.api.thread.CompactThreadCommand} 就是 HarnessRuntime 对应控制方法的入参形状，只携带目标
 * Thread、 客户端幂等键、expected version 与开关取值。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.api.thread.ThreadSnapshot} 由 Runtime
 * 在同一事务内加锁读取，对外只给公共值类型：durable {@link fun.fengwk.kkstudio.harness.api.thread.ThreadState}、当前 Entry
 * 状态、读取切片、排队命令、中立 generation 与 live tool 事实。{@link
 * fun.fengwk.kkstudio.harness.api.thread.ThreadRuntimeStatus}、{@code outcome()} 与 {@link
 * fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionFacts} 都由快照自身的纯值算法给出，消费方不必也不应重新推导。
 *
 * <p>不引用 Store、Driver、Runtime 内部状态机（如 Thread 上下文分类）等内部类型；必填字段与非负 version 在构造期校验。
 */
package fun.fengwk.kkstudio.harness.api.thread;
