/**
 * 终端画面的数值投影模型。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.environment.terminal.TerminalView} 是 Daemon
 * 内核与渲染/传输之间唯一的结构化画面契约：它只描述 cols/rows、0-based 光标、alternate/history、恰好 cols
 * 个槽的逐行拓扑、逐槽样式与输入模式，不包含终端模拟器实现、宽度表或字形合并。
 *
 * <p>本包保持 JDK-only、深不可变，不读取配置、不建立连接、不持有任何 PTY/进程资源。
 */
package fun.fengwk.kkstudio.harness.environment.terminal;
