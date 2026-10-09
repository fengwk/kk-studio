/**
 * 人工终端的启动规格、headless VT 内核与数值画面投影。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.daemon.terminal.TerminalKernel} 是唯一实现（JediTerm 3.76）的单一
 * owner 内核：它只接受调用方注入的 executor 与非阻塞出站回调，在有界 FIFO 上完成 UTF-8 解码、解释、resize、key 编码与捕获，不创建线程池或 static
 * executor，也不连接 PTY、WS 或前端面板。{@link
 * fun.fengwk.kkstudio.harness.daemon.terminal.HeadlessTerminalDisplay} 显式实现 JediTerm Display；
 * {@link fun.fengwk.kkstudio.harness.daemon.terminal.TerminalSnapshotProjector} 把公开缓冲投影为 {@code
 * harness-environment} 的 {@link fun.fengwk.kkstudio.harness.environment.terminal.TerminalView}。
 * 每个内核独占投影器，以观察到的行对象身份分配单调正数 id；捕获后只保留活动历史与屏幕行引用，尺寸或主/备用屏切换时清空引用但不重用 id。 旧 view
 * 仅保留不可变数值数据，不持有模拟器引用，不以重复文本猜测滚动。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.daemon.terminal.TerminalLaunchSpec} 保存已解析的可执行程序、按原样组成的不可变
 * argv 与绝对规范化工作目录。 解析只在配置读取时发生一次：显式 executable/workdir 立即只读校验并失败关闭，缺省时按宿主 OS 选择 shell 并以 {@code
 * user.home} 为工作目录； 运行失败后不换 shell，也不把参数拼接成 shell 字符串。argv 可能包含秘密，因此不进入 {@code toString} 或诊断输出。
 *
 * <p>{@link fun.fengwk.kkstudio.harness.daemon.terminal.TerminalRuntime} 把唯一 scoped
 * PTY、唯一内核与唯一有界写队列合成一次调用的资源边界：用户写入、尺寸调整与内核查询应答走同一队列，操作都先建立截止时间再推进内核与 native，并在进入 native
 * 前后明确区分「确定未执行」与「结果不确定」，native 截止时间与关闭仲裁同一 gate；收敛只由一个预留的 lifecycle 任务完成，read/kernel
 * 失败、超时、自然退出、被调用方提前中断与显式关闭都只发出停止信号，不在 reader/writer/VT owner/scheduler
 * 上就地收敛。它使用调用方注入的执行器，不创建也不关闭执行器；因为预留的 lifecycle 任务与读、写任务都会阻塞，注入的阻塞 I/O executor 必须能并发运行这三者。
 *
 * <p>本包与模型工具的 {@code bashExecutable} 语义互相独立，不拥有业务表、日志或持久化出口。DA/DSR/OSC 回应只交给注入的回调，调用方负责接唯一 PTY
 * writer。
 */
package fun.fengwk.kkstudio.harness.daemon.terminal;
