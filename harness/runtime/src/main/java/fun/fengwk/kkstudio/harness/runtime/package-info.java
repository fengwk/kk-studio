/**
 * Durable Agent Runtime 的领域边界与同步控制面。
 *
 * <p>本模块承载 Session Entry Tree、Thread/Command mailbox、Invocation 与 Work 的 durable 协议；root 包内的 {@link
 * HarnessRuntime} 是唯一同步 command/control/query 入口（createThread / enqueueCommands / moveHead / stop /
 * decideToolApproval / getThreadSnapshot），durable 操作各在一个 {@code HarnessStore} 事务内按 Thread -&gt;
 * Commands -&gt; Model -&gt; Tool siblings -&gt; Work 锁序执行；Stop 提交后才 best-effort 取消本 JVM
 * execution。冲突以 {@link HarnessRuntimeConflictException} typed reason 表达。Model/Provider 契约位于 {@code
 * harness.runtime.model} 包，Tool Invocation / 权限策略位于 {@code harness.runtime.invocation} / {@code
 * harness.runtime.tool} 包；通过 typed HarnessStore transaction 与 feature-local port 反转 外部能力。只依赖
 * harness-tool 与 Jackson，不依赖 Provider SDK、Spring、MyBatis 或 HTTP adapter。
 */
package fun.fengwk.kkstudio.harness.runtime;
