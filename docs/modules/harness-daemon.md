# Harness Daemon

Environment Daemon 是目标宿主上的独立 JVM 进程，把 Platform 的原子能力调用落到文件系统、进程与本地工具链。网络连接只是消息管道：同一 Daemon 进程断线重连，不会重启已受理的执行。安装与三平台管理见 [Environment Daemon 安装与运行](../operations/environment-daemon.md)；共享契约见 [Harness Environment](harness-environment.md)，服务端会话见 [Harness Environment Server](harness-environment-server.md)。

模块依赖 `harness-common`、`harness-environment` 及 Jackson、OkHttp、JGit、RE2/J、LSP4J、JNA、pty4j 和 JediTerm。官方发布物将依赖、Kotlin 与 PTY 原生资源打成 shaded JAR；语言服务器与 Bash 由宿主提供。

## 启动与本地状态

[`DaemonMain`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonMain.java) 先解码参数，再处理信息命令或解析配置，随后打开数据目录、装配能力和运行时，注册 shutdown hook，连接 gateway 并等待结束。

单独 `--help` / `-h` 或 `--version` 不打开数据目录。机器入口 `--base64-args` 必须在首位，后续每个 token 是一个原始应用参数的 UTF-8 Base64；[`DaemonArguments`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonArguments.java) 严格解码一次，拒绝非法 Base64、UTF-8、null 与 NUL，且不回显原值。它不是加密或第二套配置来源。Windows 安装器用该入口传应用参数，并通过任务的 Unicode 字段设置 Java 路径和工作目录，以 ASCII 相对 JAR 名规避 JDK 21 launcher 的 ANSI argv 转换损失。

[`DaemonConfig`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonConfig.java) 只消费唯一的 `--config <绝对路径>`：配置文件是 `daemon.json`，其父目录就是运行数据目录，同目录的 `daemon.token` 是 owner-only 注册凭证。gateway 由配置里的 `studioUrl` 派生，心跳/重连固定 `PT15S`/`PT1S`/`PT30S`。进程不接受 token 文本，也不接受 gateway、数据目录或 LSP 文件路径等第二配置来源；完整参数见安装指南。配置里的 `terminal`（可执行程序、argv、工作目录）在读取配置时由 [`TerminalLaunchSpec`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/terminal/TerminalLaunchSpec.java) 只解析一次：显式值立即只读校验，缺省时按宿主 OS 选择 shell 并以 `user.home` 为工作目录；运行失败不换 shell，argv 不进入 `toString` 或诊断，且与模型工具的 `bashExecutable` 互相独立。

[`DaemonTokenFile`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonTokenFile.java) 校验绝对路径、普通文件和非符号链接，在 POSIX 上要求属主可读且无 group/other 权限位。它不核对 Unix UID，也不复核 Windows DACL；当前用户所有权和 Windows ACL 由安装器检查。配置对象只保存路径，每次 HELLO 前读取 UTF-8 内容并去除外围空白，凭证不进入配置对象的 `toString` 或日志。

[`DaemonDataDirectory`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonDataDirectory.java) 持有 `daemon.lock` 独占文件锁，同一目录的第二个进程立即失败。布局为：

```text
<data-dir>/
  daemon.lock
  resources/text/           # 已发布大文本
  resources/staging/        # 上传/输出中转 *.part
  skills/<package>/         # 已安装技能包
  skill-work/cache/         # bare Git 缓存
  skill-work/staging/       # 安装暂存
  skill-work/backup/        # 替换备份
```

POSIX 目录为 0700、文件为 0600；非 POSIX 文件系统退回 Java `File` 的 owner-only 设置，不等同于安装器对 token 的显式 Windows DACL 校验。数据目录启动时只清理遗留资源 `.part`；技能安装器另行恢复或清理 staging/backup。已发布全文、已安装包与 Git 缓存不会随服务卸载自动删除。

## 能力注册与调度

[`DaemonCapabilityRegistry`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonCapabilityRegistry.java) 在运行时构造结束前冻结，生产装配必须与共享 catalog 完全一致：9 项编码能力，以及内部 `skill.sync`。LSP 没配置时仍注册对应能力，但查询返回明确的不可用错误。MCP 由 Platform 在 Backend 内承载，不进入 Daemon。

[`DaemonRuntime`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonRuntime.java) 拥有两类彼此分离的执行资源。能力（capability）侧：

| 资源 | 用途 |
| --- | --- |
| 单线程 scheduler | 心跳、重连、超时与 LSP 定时任务 |
| virtual-thread-per-task executor | 编码能力的阻塞任务 |
| 平台线程缓存池 | LSP stdio 阻塞 I/O |

人工 shell 侧另有一组由唯一 `TerminalCoordinator` 独占的资源：串行 owner、VT executor、可并发阻塞的 I/O executor 与专用 scheduler，再加一个预启动 worker 的生命周期 executor 承担整体 shutdown 的阻塞汇合。这些资源与能力侧分离，避免有限资源阻塞终端生命周期、reader 或 writer；生命周期 executor 保证唯一一次 shutdown 入队不会被拒绝，且任何回调线程都不内联阻塞。

构造失败释放已创建资源。`start()` 幂等；断开后指数退避，上限由配置决定，连接成功后重置。每次连接有新代际，旧连接回调不再推进当前状态。注册被拒进入 FAILED、停止重连并使进程非零退出；`RETRY_LATER` 则退避重连。

### 握手闸门与 READY

HELLO 只声明协议版本、注册凭证、能力目录版本与 `daemonInstanceId`，不带 Environment scope。收到 WELCOME 后先在生命周期锁内复核仍是当前连接、代际未过期且运行时未停止/关闭，才提交 Environment 绑定与资源字节预算；绑定在协调器 owner 上串行完成，完成前绝不 READY。只有 READY 成功进入传输且连接仍是当前代际时才放行上传控制帧；READY 同步或异步递交失败都关闭该握手并由既有重连恢复，不悬挂也不保留假 READY。迟到的旧连接 WELCOME 不能覆盖新连接的绑定。断线只重置绑定、预算与等待票据，`disconnect(generation)` 清观察 route 但保留 shell 与 writer 租期；同 instance 重连后 ATTACH 保持 `terminalId`。

入站 `SHELL_COMMAND` 要求连接已 READY 且 envelope scope 与内层 `command.environmentId` 一致，再按当前代际递交协调器；只有 mailbox 满与协调器已关闭这两类「确定未受理」失败映射为固定 NOT_EXECUTED 回执，受理后异常走明确生命周期失败。协调器事件按连接的认证绑定编码为 `SHELL_EVENT` 回传，作用域取自事件本身而非可并发变更的全局绑定。画面事件只承载结构化单元格，不传输原始 PTY 字节。

INVOKE 先严格解码，再以 `journal.start(invocationId)` 原子去重。已有条目直接重放；新调用检查能力标识、版本和 arguments schema，使用 wire 的有效超时，不回落 descriptor 默认值。运行时预检通过才发送 STARTED，然后进入能力执行。STARTED 不保证宿主命令已经启动，能力自己的路径或进程启动预检仍可能失败。

### 超时与取消收尾

有效超时为 0 时不登记 execution deadline。超时与取消分别请求能力按 `TIMED_OUT`、`CANCELLED` 收尾，wire 终态仍为 FAILED、CANCELLED，不因迟到成功结果改成 COMPLETED。副作用已经发生时不回滚。

能力可通过 `terminationGrace()` 声明有界收尾预算。运行时先开启窗口和兜底定时器，再请求收尾；预算内返回的能力文本追加到裁决正文，保留终态类型，便于失败时带回已捕获输出。超大文本不注入；兜底到期或调度器不可用则直接提交裁决。journal 的 RUNNING → terminal 原子跃迁只允许一次终态。

关闭运行时先关闭连接与传输，逐项取消在途调用并尝试写入 CANCELLED（单项失败隔离，不跳过其余项），清空运行集合，再汇合唯一终端协调器（有界等待），随后关闭 LSP 等能力资源，最后停止全部执行资源（各最多等待 5 秒）。整个清理在专用生命周期 executor 上执行，owner/VT/IO/scheduler 与 capability 回调线程都不内联阻塞。任一能力项收尾失败、终端协调器未收敛，或终端执行资源未在预算内停止，都以固定去敏失败显式收敛为 FAILED：`failureReason()` 给出该事实，`close()` 在异常边界抛出，`awaitTermination()` 返回 FAILED，绝不假装干净关闭。**不会清空 journal 的已有条目**，但 journal 是内存数据，进程结束后丢失。

### 实例身份与重连恢复

`daemonInstanceId` 在构造时随机生成，同进程所有 HELLO 复用。连接断开只重置绑定、资源预算与等待票据，不改执行事实。相同 invocationId 的重复 INVOKE：RUNNING 重放 `STARTED(replayed=true)`，已终结重放冻结终态，副作用不再执行。

恢复范围是同一 Daemon 进程与仍持有在途目录的 Gateway。Daemon 重启生成新 instanceId，Gateway 将此前在途调用收敛为结果不确定，等待调用层处理；journal 的去重事实随进程生命周期保留。

[`OkHttpWebSocketTransport`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/transport/OkHttpWebSocketTransport.java) 强制握手协商 `permessage-deflate`，缺失以 1010 关闭；只接收文本帧，二进制帧或单条文本超过 16 MiB 字符以 1008 关闭，不退化为另一种传输。

## 本地存储与文本输出

[`TextOutputStore`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/TextOutputStore.java) 把超阈值命令与检索输出先写 staging，再发布为 `resources/text/*.log`。全文是本地 durable 事实，不是内容寻址 Resource；历史可能仍引用绝对路径，不隐式删除。发布优先原子 move，文件系统不支持时使用普通 move。

小输出内联，超过 50 KiB 或 2000 行时返回有界 head/tail、绝对路径、字节与行数，以及 read/grep 指引。预览不截断多字节字符，不重复重叠窗口；CR、LF、CRLF 的行数与文件读取一致。默认捕获预算为 1 GiB，达到预算只停止文件捕获，继续排空与统计；磁盘失败降级为无路径预览，不因输出量或磁盘错误杀死命令。结果明确区分完整捕获、截断与捕获失败。

自然退出、非零退出、超时、取消都先保留可发布输出。收尾说明不写入全文、不计入全文统计；只有自然退出报告 exitCode。`detailsJson.process.outcome` 区分 EXITED / TIMED_OUT / CANCELLED，运行时强制失败或取消的 wire 正文通过前述收尾窗口携带文本。

[`SkillPackageInstaller`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/skill/SkillPackageInstaller.java) 以请求指定的 exact commit 安装，不以 branch HEAD 替代。先拉取 bare cache、物化 staging、检查整棵 tree，再替换 `skills/<package>`；拒绝符号链接、submodule、路径穿越与非普通文件，失败保留或恢复旧包。包内 `.kkstudio-commit` 记录 commit，模型路径稳定为 `<data-dir>/skills/<package>/<skill>/SKILL.md`。origin URL 变化时重建缓存；marker 相同也重新校验物化结果。

## 编码能力

### workdir 与权限

[`EnvironmentPaths`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/EnvironmentPaths.java) 用本次 arguments 定位路径。相对 path 要求绝对、现存、可读 workdir；绝对 path 可直接定位，process.exec 始终要求 workdir。目录校验失败即拒绝本次调用，每次调用独立解析目录。

workdir **不是沙箱**：目标可在其外，符号链接照常跟随；宿主权限来自运行用户，业务授权由 Platform 判定。READY 的进程用户、时区、OS、HOME 和可信 note 仅为宿主展示事实，HOME canonical 化失败时退回绝对规范路径，不是执行默认值。

### 进程执行范围

[`ProcessScope`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/process/ProcessScope.java) 与 [`ProcessScopeHelper`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/process/ProcessScopeHelper.java) 先建立 OS 范围，再经父进程校验和 permit 放行用户命令。helper 冷启动也计入有效超时；放行前取消或失败不会执行用户命令。无法建立范围时明确失败，不退化为无范围直接执行。

- Linux/WSL/macOS：管道模式下 helper 用 JNA `setsid` 建立会话，PTY 模式由原生 `login_tty` 建立；普通后代从创建起归属同一会话。交互式 shell 的前台与后台作业可各有进程组，收敛覆盖整会话。结束时先温和信号、宽限后强杀，再向内核确认没有活成员；僵尸不算活成员。成员枚举走真实内核查询（Linux `/proc` 的会话字段，macOS libproc）；身份读不到时按「不可判定」返回，绝不谎报已收敛。
- Windows：命名 Job Object 设置 kill-on-close，不开放 breakaway；首进程在创建时进入 Job，父进程另持句柄。只有 Job 的 ActiveProcesses 为 0 才确认范围结束，helper 退出或根进程退出都不能代替此检查。
- 伪终端（PTY）模式：`ProcessScope.startPty` 用 `pty4j` 0.14.0 让 helper 在伪终端中运行用户命令（POSIX 走原生 `login_tty` 建立会话与控制终端，Windows 必须是 ConPTY，拒绝 WinPTY 回退），并提供 `resize`。`capture`/`duplex`/`pty` 三种标准流共用同一条启动准入、退出、终止与会话收敛链路。

自然退出也收敛遗留后代，确认后才通知终态；无法确认收敛时报告失败。process.exec 的 stdin 立即 EOF，stdout/stderr 合并捕获；helper 诊断单独保存，不污染命令输出。Bash 用 `-lc` 执行；Windows 应明确配置所需 Bash，避免裸名解析到 WSL launcher。

helper 命令行只携带固定入口与私有状态目录；工作目录和 argv 以私有、原子发布的 JSON 启动文件交接，读取后立即删除。命令启动失败不回显 argv 或工作目录；PTY 输入、输出和屏幕内容不写入该状态目录。关闭 PTY 范围时显式释放主端流与原生资源。

范围管理不是恶意命令隔离：POSIX 命令主动重新建立 session 可离开边界，不承诺阻止逃逸；仅改变进程组不会脱离会话收敛。LSP 使用同一范围的双向 stdio 模式，stderr 独立，客户端关闭先发送 shutdown/exit，宽限后收敛后代。跨平台测试见 [`ProcessScopeCrossPlatformTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/process/ProcessScopeCrossPlatformTest.java)，其它命令行为见[内置 Bash 测试映射](../operations/builtin-bash-tests.md)。

### Headless 终端内核

[`TerminalKernel`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/terminal/TerminalKernel.java) 以 JediTerm 3.76 为唯一 VT 解释器。调用方注入执行器与非阻塞应答出口；一个 owner 完成 UTF-8 解码、解释、输入模式编码、resize 和一致画面捕获。它不创建 GUI，也没有第二个解析器或原始输出回放。

输入与控制共用 128 项 FIFO，每次输入最多 4096 字节；空读点处理控制，半截 CSI、OSC、UTF-8 不阻止捕获、resize 或关闭。单次解释最多消费 65536 个 UTF-16 单位，超限明确失败。尺寸采用 JediTerm 的公开下限 5 列、2 行，低于下限直接拒绝，避免初始化与 resize 的实际尺寸不一致。关闭优先于待决事件，有界等待 owner 退出；`termination()` 与未决操作明确报告失败。DA/DSR/OSC 应答只进入注入的出口，外部回调异常不会暴露终端内容。

画面使用 [`TerminalView`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/terminal/TerminalView.java) 深不可变投影：每槽为数字 UTF-16 单位、UNIT/EMPTY/DWC 拓扑和独立样式；不拼接码点、规范化或重新推理宽度。只投影活动缓冲与有界历史；输入模式版本按实际模式和尺寸比较递增，不随普通输出改变。内核没有日志或持久化出口，也不拥有 PTY 或网络连接。

每个内核拥有一个投影器，按实际观察到的 JediTerm 行对象身份分配从 1 开始的单调正数 `id`。同一行滚入历史时保留身份，文本相同的新行获得新号，不以文本重叠猜测滚动。捕获成功后引用映射只保留当前活动历史与屏幕行，最多为 `history + rows`；真实尺寸或主/备用屏切换时清空引用但不重用行号，切换期间不捕获也会重置。旧画面只保存不可变 id 与数值槽，不持有模拟器行引用；身份变化本身不改变输入模式版本。

单个观察流由 [`TerminalViewStream`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/terminal/TerminalViewStream.java) 归约：单个 Runtime 状态 owner 串行提供捕获画面（JediTerm 仍是唯一 VT owner），流内只保留一份已确认基线、一个在途更新与其版本，不缓存 delta 链或「最新待发」字段。新流首条是 `version=1` 的 RESET，只有精确匹配 `streamId + 在途版本` 的 ACK 才提升基线并归还额度，陈旧、重复或错流 ACK 一律不推进也不清理。增量只使用实际行身份：尺寸或主/备用屏变化、两版画面没有任何共享行 id、历史无法用 id + 整行相等证明连续时退回 RESET；仅 cursor/mode 变化产生 metadata-only PATCH，完全相同画面返回空且不推进版本，历史裁剪与追加由 `historyTrim/historyAppend` 有界描述。

数值投影、样式、历史与缓冲切换由 [`TerminalKernelTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/terminal/TerminalKernelTest.java) 和 [`TerminalSnapshotProjectorTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/terminal/TerminalSnapshotProjectorTest.java) 验证；分块调度、预算、满队列、关闭与异常传播由 [`TerminalKernelSchedulingTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/terminal/TerminalKernelSchedulingTest.java) 验证；RESET/PATCH 归约、单在途额度与 ACK 围栏由 [`TerminalViewStreamTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/terminal/TerminalViewStreamTest.java) 验证。

### 终端运行时

[`TerminalRuntime`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/terminal/TerminalRuntime.java) 把唯一 scoped PTY、唯一内核与唯一有界写队列合成一次调用的资源边界。它只使用调用方注入的 VT executor、阻塞 I/O executor 与 scheduler，既不创建也不关闭执行器，并按原样 argv 启动、声明 `TERM=xterm-256color`/`COLORTERM=truecolor`。因为预留的 lifecycle 任务与读、写任务都会阻塞，注入的阻塞 I/O executor 必须能并发运行这三者（缓存线程或等价的非绑定阻塞 executor）；单线程 executor 无法在读写进行中收敛。用户写入、尺寸调整与内核查询应答共用同一 128 项 / 512 KiB 写队列与唯一写任务：用户帧在落笔前用内核 FIFO 快照核对编码所用的输入模式版本，不匹配即明确未写；只有完整 `write` + `flush` 成功才完成 future，入队本身不代表已写。

每个操作都先建立 native 截止时间，再推进内核与 native 阶段：截止时间不可用时，操作在内核 resize 与窗口调整之前就确定未执行。进入 native 之前失败（模式过期、核验失败、截止时间不可用、会话结束）一律确定未执行；一旦进入 native 写或窗口调整，异常、超时或关闭都可能已产生前缀或部分变更，因此以固定的结果不确定异常通知调用方并终止整个范围。native 截止时间与关闭仲裁同一 gate：只有截止时间在 native 进行中获胜才致结果不确定，已完成的操作不会被迟到的截止时间终止。

收敛只有一个执行者：构造时先在阻塞 I/O executor 上预留 lifecycle 任务，read failure、kernel failure、native 超时、自然退出与显式 `close()` 都只设置失败原因并唤醒它，绝不在 reader/writer/VT owner/scheduler 上就地收敛；读写任务被调用方提前中断时同样只发停止信号，避免 termination 悬挂与 interrupt 忙循环。所有任务先停在启动闸门上，任务与 kernel 终止钩子登记完成后才放行；任一 executor 拒绝都会先收敛已预留的任务与 native 范围再失败。自然退出时排空 PTY 到真正 EOF、有界读取退出码、捕获一份有界末屏，再释放 PTY 与内核；退出后 `snapshot()` 仍返回该末屏。`close()` 幂等、统一有界，收敛失败或等待超时都显式报告，绝不静默宣称资源已释放。

真实跨平台 PTY 的输入回传、终端环境变量与自然退出末屏由 [`TerminalRuntimeRealPtyTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/terminal/TerminalRuntimeRealPtyTest.java) 验证；查询应答与用户输入共用唯一 writer、过期模式、队列与字节预算、排队取消、整帧复制与不交叉、分块 UTF-8/CSI 调度、native 前后失败、截止时间先于 native 获胜或不可用、迟到截止时间、读写任务被提前中断、无法收尾任务的释放失败、无法解析的可执行程序与执行器拒绝等确定性边界由 [`TerminalRuntimeDeterministicTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/terminal/TerminalRuntimeDeterministicTest.java) 验证。

[`TerminalWriter`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/terminal/TerminalWriter.java) 是单个 terminal 的纯内存 writer reducer，由调用方单 owner 串行访问，类似 [`TerminalViewStream`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/terminal/TerminalViewStream.java)：它不写 PTY、不创建 executor 或定时器、不维护观察流或 transport，生产时钟为 `System::nanoTime`。它按 `terminalId` 维护固定 15 秒租期、公开 epoch 与私有 token secret 的控制权，`viewerId` 不能单独授权；每个 epoch 从 seq=1 起只允许一个在途 INPUT/RESIZE，操作摘要为 SHA-256，只有唯一 `ACCEPTED` 决议才交由调用方调用 Runtime，真实 future 决议通过 `complete` 一次性回填。已有在途操作时所有控制权轮换（takeover、跨连接恢复、释放、过期后重新授权）保守返回 `BUSY`；跨连接恢复必须携带旧 epoch/token 与待核对 seq/摘要（租期失效只禁用旧输入/续租/释放，长断连后仍可凭旧 secret 核对并恢复），无法核对的旧操作视为结果不确定并冻结 writer；每次真实轮换到新 epoch 都重置去重水位，使新 epoch 从 seq=1 重新开始。重复控制 requestId 只在授权仍有效时重放，失效、转移或冻结后一律明确拒绝，冲突 requestId 不覆盖首个请求的结果。公开状态与 `toString` 不回显 token 或输入字节，也不保留输入日志或历史 journal。租期/seq 去重/跨连接恢复/fencing/secret 去敏由 [`TerminalWriterTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/terminal/TerminalWriterTest.java) 验证。reducer 的公开值类型 `WriterGrant`/`WriterState`/`OperationDigest`/`OperationOutcome`/`ControlResult`/`AdmissionResult` 已归一到 `harness-environment` 的 terminal 包，与[终端控制 wire](harness-environment.md)共用同一组不可变定义；reducer 与 `WriterOwner` 仍留在 Daemon，状态机不变。

### 终端协调器

[`TerminalCoordinator`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/terminal/TerminalCoordinator.java) 是每 Daemon 一份的单例终端协调器：持有随机 `daemonInstanceId`、唯一启动规格、原 Environment 身份与当前 Backend generation、一份 `TerminalRuntime`/`TerminalWriter` 与最多 8 个 Observer。公开边界是异步 `bind(environmentId, generation)`、`receive(generation, request)`、`disconnect(generation)`、`shutdown()` 与 `termination()`（shutdown 幂等）；这些 future 只表示命令已被受理/状态处理完成，不代表 INPUT 已在 PTY 执行，真实操作结果以事件为准。全部 reducer/stream/状态变更在注入的单 owner executor 上串行执行；PTY 启动不在 owner、scheduler 或 VT executor 上执行，而是投给注入的阻塞 I/O executor。它不创建 executor，不装配连接、持久化或 UI。

外部受理进入 128 项 / 512 KiB 的有界 mailbox（输入字节加固定 metadata 计费），满队列或已关闭时立即以固定异常拒绝，不丢已收请求；runtime 写决议、启动与停止等内部完成信号使用独立保留槽，外部满队列不会挤掉它们。同一时刻最多一份 snapshot、一次启动/收敛与一次 writer 操作，ticker 最多一份排队：调度间隔 34ms（上限 30Hz），只发有界 owner 信号，仅在存在观察者且有 credit 时捕获一次画面。每个 Observer 保留一条 `TerminalViewStream`、route+viewerId、streamId、最后 attach/open 请求签名与结果的有界槽，以及至多一份在途更新；同 requestId 且同签名重放现存流而不旋转，冲突 requestId 明确拒绝。KEEPALIVE 期望 5s、闲置 15s 撤销；`VIEW_APPLIED` 等待 10s 后只撤销该流而不杀 shell；判时只用单调 clock 差值。emitter 必须非阻塞，抛错时只移除该收件人的 Observer，不重试也不杀 shell。

`bind` 同 Environment 新 generation 清旧观察 route、保留 shell 与 writer 租期；同代际换 Environment 不是合法重绑，一律 no-op 而不复用或倒退绑定；`disconnect` 只影响匹配 generation。`bind` 不同 Environment 先围住旧操作入口、停止旧 runtime 并等待唯一收敛，再清 session 绑定新身份；重绑等待期间任何新 `bind` 都以固定 REBINDING 拒绝，不让调用方提前认为已绑定；启动 gate 只检查未关闭且原 Environment 仍匹配，过期 generation 的 receive/disconnect/迟到完成回调都无副作用。OPEN 首次才以 80x24/history 512 启动，并发或重复 OPEN 只启动一个 native；没有旧 session 时带 `expectedExited` 固定 REQUEST_CONFLICT；RUNNING 复用同一 instance 并 attach，已 EXITED/FAILED 且无 `expectedExited` 只 attach 末屏不自动新开，只有精确匹配旧 identity 且旧 session 已结束的 `expectedExited` 才新建 terminalId，迟到或重复的 `expectedExited` 不能再次启动，错误不回显 launch executable/argv。ATTACH 只指向现存 identity，与 OPEN 共用同一 attach 路径，新流先发不含 grant 的 ATTACHED 与 RESET。命令的 environmentId 必须匹配绑定，带 identity 必须同时匹配 daemonInstanceId 与 terminalId，带 stream 必须来自匹配 route+viewer 的真实 Observer。CLAIM/TAKEOVER/INPUT/RESIZE 仅在 RUNNING 且该流已有 applied 基线后准入，CLAIM 恢复直接复用既有 `TerminalWriter.recover`。`WRITER_CHANGED` 公开广播不含 `ControlResult`（尤其不含 grant secret），发起者另行收到自己的 result。

INPUT/RESIZE 只在 `AdmissionResult` 为 ACCEPTED 时调用 Runtime 一次，PENDING/CONFIRMED/REJECTED 直接返回既有决议。真实 future 成功记 WRITTEN，`StaleModeException` 记 NOT_WRITTEN+STALE_MODE，native 之前异常记 NOT_WRITTEN，`OutcomeUnknownException` 记 OUTCOME_UNKNOWN 并冻结/停止会话；先 `writer.complete(epoch, seq, digest, outcome)` 再发 OP_ACK，迟到完成不能推进新 session/epoch。Runtime 自然 termination 记录 EXITED/FAILED、exitCode 与末屏、expire writer 入口且不清末屏，仍可 attach 查看；CLOSE 要求精确 identity 与 `expectedWriterEpoch` CAS，有未决操作时返回 BUSY，通过后按自然 termination 路径停止并记录末屏。画面捕获失败以固定 ERROR RUNTIME_FAILED 上报并按 FAILED 收敛、不再读取失败内核；已保留末屏时之后仍可 attach 读末屏，没有末屏时之后的 ATTACH 立即拿固定错误而不是永远 pending。

失败关闭可从任意线程触发：先围栏 drain（置位 `drainFailed` 并清空 control），再以异常终结全部已受理的外部 future（含唯一在途重绑）、停止并汇合全部 runtime、释放全部观察流与重放引用；被放弃或迟到的启动一律先停止已返回的 runtime，其 cleanup 失败传入 session 与重绑失败边界，不让新 Environment 虚假绑定成功。每次启动决议离开 pending 集合后独立触发终止汇合，因此 owner 回调被 fatal 丢弃也不会让 `termination()` 悬空；shutdown 与收敛失败都以异常 future 显式报告，不吞掉。

单例 OPEN/重复与迟到 restart、不同 Environment 重绑与 generation fence、重绑期间 bind 拒绝与同代际 no-op、RESET credit 与慢观察者只撤流、viewer 不能索取 writer secret、操作 pending 不重复、mode stale、未知 write 冻结、CLOSE CAS/pending、画面捕获失败、启动/收敛/emitter 失败与 fatal 资源释放等确定性边界，以及真实 PTY 创建/输入/自然退出路径，由 [`TerminalCoordinatorTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/terminal/TerminalCoordinatorTest.java) 验证。

### 文件与检索

文件修改保留编码、BOM、行尾，通过进程内分段锁串行化同文件修改。文本读窗口以 1-based 行/列定位，limit 默认及最大 2000，正文预算 60000 Unicode 码点；扫描到 EOF 得到总行数与 ends_with_newline，内存只驻留窗口。超时/中断不返回半个窗口；续读由 next 指向首个未返回字符。支持的图片以二进制结果直传对象存储，设备、FIFO、socket 等特殊节点在 I/O 前拒绝。详细读写契约见[内置 Read 测试映射](../operations/builtin-read-tests.md)与[文件修改测试映射](../operations/builtin-mutation-tests.md)。

grep/find 用 Java NIO 遍历，不依赖外部检索二进制。忽略规则从目标路径的祖先读取，支持分层 `.gitignore` 与 `.git/info/exclude`（含 worktree 的 gitdir/commondir），不依赖调用 workdir，始终排除 `.git`。单行检索流式扫描，超长行（1 MiB）显式报告无法完整搜索；multiline 整文件视图上限 64 MiB。UTF-8 与 BOM 指明的 UTF-16 严格解码，不把不支持编码静默替换成乱码。失败、限制与忽略行为见[内置检索测试映射](../operations/builtin-search-tests.md)。

### LSP

[`LspDiscovery`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/LspDiscovery.java) 在 CLI 解析时从共享 `DaemonConfiguration` 的 `lsp.servers` 构建；缺省禁用，结构与取值非法则启动失败。配置声明外部语言服务器命令、扩展名、项目根标记，完整可复制例子见安装指南；Daemon 不自动安装语言服务器，也不接受独立 `--lsp-config` 文件。read header 只探测配置与可执行程序，不启动服务器，也不证明其能初始化。

客户端按项目根与配置复用 stdio 连接，闲置 5 分钟且无在途请求后回收，关闭宽限 1 秒。查询前同步文件，位置编码按服务器声明协商；协议流持续解析，stderr 仅留有界诊断尾部。请求超时/取消只发 `$/cancelRequest`，不终止共享客户端。jdtls 才支持 Java class 源码请求，无 javap 回退。行为证据见[内置 LSP 测试映射](../operations/builtin-lsp-tests.md)。

## 二进制结果直传

结果编码器先检查内容条目、资源预算和最终 JSON 大小，全部通过才交给 [`DaemonResourceTransferClient`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonResourceTransferClient.java)：

```text
REQUEST -> TICKET READY                 # 去重命中，直接引用
        -> TICKET PENDING -> PUT bytes -> COMMIT -> TICKET READY
        -> TICKET FAILED                # 在调用预算内处理重试
COMPLETED(uploadId 与权威元数据)
```

字节不进入 WebSocket。一次 transfer 用 `(invocationId, transferId)` 关联，同进程重连复用 transferId；已上传字节只重新提交，不重复 PUT。控制帧发送与终态抢占互斥，终态之后不再发控制帧。PUT 遵守票据方法、原始签名 headers 与调用剩余 deadline；预签名 URL、headers、字节不进入日志、终态或 history。wire 字段与预算以 [Harness Environment 的载荷编解码器](harness-environment.md#载荷编解码器)为准。

## 包架构

| 包路径 | 维护边界 |
| --- | --- |
| `daemon` | CLI、数据目录、能力注册、执行器所有权、握手与调用运行时 |
| `daemon.coding` | 文件、命令、检索、文本输出与 LSP；只消费显式调用目录 |
| `daemon.process` | 唯一 OS 执行范围基座：父进程侧 `ProcessScope`、helper 侧 `ProcessScopeHelper`、POSIX/Windows 原生原语；不注册工具 |
| `daemon.terminal` | 唯一启动规格解析、单 owner JediTerm 内核、headless Display、数值投影、纯内存 writer reducer、单例终端协调器与一次调用的终端运行时资源边界；不注册工具、不拥有连接或业务持久化 |
| `daemon.journal` | 进程内原子去重与冻结终态，不持久化跨进程执行状态 |
| `daemon.skill` | exact commit 的技能包拉取、校验、替换与启动恢复 |
| `daemon.transport` | WebSocket 文本传输、压缩协商与帧边界 |

包名前缀为 `fun.fengwk.kkstudio.harness`。依赖、import 与执行器所有权由 [`DaemonModuleArchitectureTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/DaemonModuleArchitectureTest.java)守卫（含 environment-server 只允许 test scope 的精确检查）。启动与凭证测试见 [`DaemonConfigTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/DaemonConfigTest.java)、[`DaemonTokenFileTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/DaemonTokenFileTest.java)；重连、journal、收尾窗口与上传恢复见 [`DaemonRuntimeTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/DaemonRuntimeTest.java)；v3 绑定闸门、迟到 WELCOME、READY 递交失败、mailbox 满 NOT_EXECUTED、关闭失败面与并发关闭见 [`DaemonRuntimeShellTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/DaemonRuntimeShellTest.java)；Daemon 与真实 [`EnvironmentDaemonServer`](../../harness/environment-server/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServer.java) 的协议桥接加真实 PTY 端到端见 [`DaemonTerminalServerIntegrationTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/DaemonTerminalServerIntegrationTest.java)；目录锁与启动清理见 [`DaemonDataDirectoryTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/DaemonDataDirectoryTest.java)。

上级：[系统设计](../system-design.md)。相关文档：[Harness Environment](harness-environment.md)、[Harness Environment Server](harness-environment-server.md)、[Harness MCP](harness-mcp.md)、[Environment Daemon 安装与运行](../operations/environment-daemon.md)。
