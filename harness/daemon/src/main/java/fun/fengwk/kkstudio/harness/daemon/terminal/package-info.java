/**
 * 人工终端的唯一启动配置。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.daemon.terminal.TerminalLaunchSpec} 保存已解析的可执行程序、按原样组成的不可变
 * argv 与绝对规范化工作目录。 解析只在配置读取时发生一次：显式 executable/workdir 立即只读校验并失败关闭，缺省时按宿主 OS 选择 shell 并以 {@code
 * user.home} 为工作目录； 运行失败后不换 shell，也不把参数拼接成 shell 字符串。argv 可能包含秘密，因此不进入 {@code toString} 或诊断输出。
 *
 * <p>本包与模型工具的 {@code bashExecutable} 语义互相独立。
 */
package fun.fengwk.kkstudio.harness.daemon.terminal;
