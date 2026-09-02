/**
 * 纯 Thread command mailbox 协议与确定性 harvest reducer。
 *
 * <p>本包拥有 typed command 值、入队 batch CAS 事实、派生的 command state 以及 branch/Thread policy 归约。 {@code
 * SET_WORKSPACE_PATH} 携带 Environment Root 下 canonical 相对 workspace 路径（可空表示清除）。 本包不负责持久化
 * command，也不执行第二个 Thread loop。
 */
package fun.fengwk.kkstudio.harness.runtime.thread.command;
