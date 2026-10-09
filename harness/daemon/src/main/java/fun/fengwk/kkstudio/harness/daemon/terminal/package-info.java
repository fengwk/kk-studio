/**
 * Daemon 的 headless VT 内核与画面投影。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.daemon.terminal.TerminalKernel} 是唯一实现（JediTerm 3.76）的单一
 * owner 内核：它只接受调用方注入的 executor 与非阻塞出站回调，在有界 FIFO 上完成 UTF-8 解码、解释、resize、key 编码与捕获，不创建线程池或 static
 * executor，也不连接 PTY、WS 或前端面板。{@link
 * fun.fengwk.kkstudio.harness.daemon.terminal.HeadlessTerminalDisplay} 显式实现 JediTerm Display；
 * {@link fun.fengwk.kkstudio.harness.daemon.terminal.TerminalSnapshotProjector} 把公开缓冲投影为 {@code
 * harness-environment} 的 {@link fun.fengwk.kkstudio.harness.environment.terminal.TerminalView}。
 *
 * <p>本包不拥有业务表、日志出口或持久化：DA/DSR/OSC 回应只交给构造时注入的回调，调用方负责接唯一 PTY writer。当前内核尚未接入运行时、进程范围或用户 shell。
 */
package fun.fengwk.kkstudio.harness.daemon.terminal;
