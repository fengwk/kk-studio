/**
 * 终端画面与控制的 transport-free wire。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.environment.terminal.TerminalView} 是 Daemon
 * 内核与渲染/传输之间唯一的结构化画面契约：它只描述 cols/rows、0-based 光标、alternate/history、恰好 cols
 * 个槽的逐行拓扑、逐槽样式与输入模式，不包含终端模拟器实现、宽度表或字形合并。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.environment.terminal.TerminalViewUpdate} 把该画面一次性投影表达为一次
 * RESET 或基于旧基线的 PATCH 增量，{@link
 * fun.fengwk.kkstudio.harness.environment.terminal.TerminalViewUpdateCodec} 定义唯一 canonical JSON 与逐槽
 * 样式压缩，{@link fun.fengwk.kkstudio.harness.environment.terminal.TerminalLimits} 固定尺寸与字节预算。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.environment.terminal.TerminalControlCodec} 是同一包内唯一的控制命令/事件
 * canonical codec：{@link fun.fengwk.kkstudio.harness.environment.terminal.TerminalCommand}、{@link
 * fun.fengwk.kkstudio.harness.environment.terminal.TerminalEvent}、仅服务端使用的 {@link
 * fun.fengwk.kkstudio.harness.environment.terminal.TerminalRoute} 与 request/response
 * 包装都经这一个入口解析，VIEW_UPDATE 直接嵌入既有画面 codec。Daemon 控制 reducer 与 wire 共用 {@link
 * fun.fengwk.kkstudio.harness.environment.terminal.WriterGrant}、{@link
 * fun.fengwk.kkstudio.harness.environment.terminal.WriterState}、{@link
 * fun.fengwk.kkstudio.harness.environment.terminal.OperationDigest}、{@link
 * fun.fengwk.kkstudio.harness.environment.terminal.OperationOutcome}、{@link
 * fun.fengwk.kkstudio.harness.environment.terminal.ControlResult} 与 {@link
 * fun.fengwk.kkstudio.harness.environment.terminal.AdmissionResult} 这些不可变值。
 *
 * <p>模型保持 JDK-only、深不可变，不读取配置、不建立连接、不持有任何 PTY/进程资源；codec 只使用现有 Jackson 与 harness-common
 * 能力在边界严格校验，不装配 WS/gateway、也不改变 DaemonEnvelope 版本。reducer 与 WriterOwner 仍留在 Daemon 包，本包只承载共享值与
 * wire。
 */
package fun.fengwk.kkstudio.harness.environment.terminal;
