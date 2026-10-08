/**
 * Daemon 的唯一 OS 级执行范围基座：父进程侧的 {@link fun.fengwk.kkstudio.harness.daemon.process.ProcessScope} 与
 * helper 侧的 {@link fun.fengwk.kkstudio.harness.daemon.process.ProcessScopeHelper}。
 *
 * <p>{@code ProcessScope} 先让 helper 取得 OS 所有权（POSIX 新 session 与进程组、Windows 命名 Job），父进程校验并登记自己的收敛手段后
 * 才放行用户命令；因此「许可之前失败」永远等价于「用户命令没有产生任何副作用」，收敛也不依赖枚举后代。
 *
 * <p>标准流有三种明确模式：{@link fun.fengwk.kkstudio.harness.daemon.process.ProcessScope.Stdio#CAPTURE}（命令
 * stdin 是空管道、 stderr 合并进 stdout，父进程按捕获管道排空）、{@link
 * fun.fengwk.kkstudio.harness.daemon.process.ProcessScope.Stdio#DUPLEX} （命令 stdin/stdout/stderr 继承
 * helper 的三条流，供常驻双向协议）与 {@link
 * fun.fengwk.kkstudio.harness.daemon.process.ProcessScope.Stdio#PTY}（命令在 pty4j 伪终端中运行，父进程持有 {@code
 * PtyProcess} 主端并可 resize）。三者共用同一条启动准入、退出、终止与收敛链路，不是第二套生命周期实现。
 *
 * <p>helper 命令行只携带固定入口与调用私有的状态目录；启动规格（argv 与 workdir）通过状态目录里的私有控制文件交接、读取后立即删除，
 * 因此命令行与诊断不会带上启动参数。状态目录由父进程独占创建，仅承载握手文件，不保存运行字节、输入或屏幕内容，调用结束时删除。
 *
 * <p>此类只作为能力基座存在：它不注册工具、不读取新配置，也不建立第二个进程生命周期。
 */
package fun.fengwk.kkstudio.harness.daemon.process;
