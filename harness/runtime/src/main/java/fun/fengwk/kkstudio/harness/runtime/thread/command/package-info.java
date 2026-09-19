/**
 * 纯 Thread command mailbox 协议与确定性 harvest reducer。
 *
 * <p>本包拥有 typed command 值、入队 batch CAS 事实、派生的 command state 以及 branch/Thread policy 归约。 Branch
 * settings 是精确的 agent 引用、model selection 与可空 Environment name 快照；setting 命令前缀固定为 `SET_AGENT ->
 * SET_MODEL -> SET_ENVIRONMENT`，目录只存在于具体工具 arguments 中。 本包不负责持久化 command，也不执行第二个 Thread loop。
 */
package fun.fengwk.kkstudio.harness.runtime.thread.command;
