/**
 * Prompt 任务与媒体接受的窄端口：Plugin 用受信 system 文本与有序内容启动一次独立 Agent 调用并观察终态，媒体则先暂存再随接受事务接收。
 *
 * <p>{@link fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptTasks} 与 {@link
 * fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptResources} 是两条独立能力：前者只暴露可持久化任务句柄与观察语义，
 * 后者只暴露暂存句柄。宿主内部如何派生 join invocation、物化 durable 消息、维护 Session 引用与释放 upload owner 都不进入本包。
 *
 * <p>本包只用 JDK 类型，不引用 Harness runtime、Storage、Spring、Canvas 或任何宿主实体；部署未绑定端口时，使用方必须确定性失败。
 */
package fun.fengwk.kkstudio.platform.plugin.prompt;
