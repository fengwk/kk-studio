/**
 * 持久化 Agent Runtime 的领域边界与同步控制面。
 *
 * <p>本模块承载 Session Entry Tree、Thread/Command 邮箱、Invocation 与 Work 调度邮箱的持久化协议；root 包内的 {@link
 * HarnessRuntime} 是唯一同步 command/control/query 入口（acceptCommands / stop / decideToolApproval /
 * setThreadYolo / manualCompactionAvailability / compactThread / getThreadSnapshot /
 * listThreadsBySession / getSessionEntries）。普通持久化写入在一个 {@code HarnessStore} 事务内按 Session -&gt;
 * Thread -&gt; Commands -&gt; Model -&gt; Tool siblings -&gt; Work 规范锁序执行；手动压缩采用短事务 plan、事务外
 * resolve、第二短事务 CAS commit。Stop 持久化提交后才 best-effort 取消本 JVM execution。冲突以 {@link
 * HarnessRuntimeConflictException} typed reason 表达。Subagent 派生与恢复通过 Subagent Session
 * 契约复用同一命令与持久化循环；Work 环境亲和性仅在 TOOL Work 携带路由要求，由基础设施层完成所有权围栏。Model/Provider 契约位于 {@code
 * harness.runtime.model} 包， Tool Invocation / 权限策略位于 {@code harness.runtime.invocation} / {@code
 * harness.runtime.tool} 包；通过 typed HarnessStore transaction 与 feature-local port 反转外部能力。模块依赖
 * harness-common、harness-tool、harness-environment、Jackson、 SLF4J 与 JGit，不依赖 Provider
 * SDK、Spring、MyBatis 或 HTTP adapter。
 */
package fun.fengwk.kkstudio.harness.runtime;
