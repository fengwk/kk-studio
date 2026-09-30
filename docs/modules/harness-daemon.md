# Harness Daemon

Environment Daemon 是运行在目标宿主上的独立 JVM 进程。它把 Platform 下发的原子能力调用落到真实文件系统、真实进程与本地工具链上，并把执行事实保留在自身进程内：网络连接只是消息管道，连接中断与重建不会改变已经开始的执行。整个环境链路的契约在 [Harness Environment](harness-environment.md)，Platform 侧的会话与租约协调在 [Harness Environment Server](harness-environment-server.md)，安装、systemd 常驻与升级流程见 [Environment Daemon 安装与运行](../operations/environment-daemon.md)。

模块依赖只有 `harness-common` 与 `harness-environment`，第三方依赖是 Jackson、OkHttp、JGit、RE2/J、LSP4J 与 JNA；它不依赖 `harness-mcp`、`harness-tool`、`harness-runtime`、`harness-infra`、`platform` 或 `web`。调用侧传下来的每个调用都自带完整参数，Daemon 不从模型、会话或历史中推断任何执行事实。

## 启动、CLI 与本地数据目录

[`DaemonMain`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonMain.java) 的装配顺序固定：

```text
CLI -> DaemonConfig
   -> DaemonDataDirectory.open(dataDir)          # owner-only 布局 + daemon.lock
   -> CodingToolsConfig.fromCli(dataDir/resources, ...)
   -> DaemonRuntime.create
   -> shutdown hook -> start -> awaitTermination
```

单个 `--help`/`-h` 或 `--version` 是纯信息命令，在打开数据目录之前输出并直接返回；混用或多余参数一律交给配置解析并失败关闭。注册被拒时进程进入 FAILED，向 stderr 输出原因并以非零状态码退出。

[`DaemonConfig`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonConfig.java)
的完整选项、默认值与安装方式只在
[Environment Daemon 安装与运行](../operations/environment-daemon.md) 维护；进程侧只需要
知道三条约束：`--gateway-uri` 与 `--registration-token-file` 必填，
`--reconnect-initial` / `--reconnect-max` / `--heartbeat` 决定重连与心跳，
`--data-dir` 决定 owner-only 本地状态位置。READY 的进程用户与 HOME 由 Daemon
直接探测。

注册凭证只以 owner-only 普通文件存在：CLI 只接收路径，配置对象不保存凭证文本，因此 `equals`/`hashCode`/`toString` 与日志都不会扩散秘密，凭证在每次 HELLO 前按需读取。[`DaemonTokenFile`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonTokenFile.java) 要求绝对路径、现存普通文件（拒绝符号链接与目录）与 owner-only 权限，并忽略两端空白。未知选项（例如 `--registration-token`、`--skill-dir`）一律启动失败，没有兼容回退。

[`DaemonDataDirectory`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonDataDirectory.java)
以 owner-only 权限创建固定布局（POSIX 目录 0700、文件 0600），持有 `daemon.lock` 的
进程独占锁，因此同一目录上的第二个 Daemon 立即失败而不是并发写同一份本地数据。目录
包含 `resources/{text,staging}`、`skills/<package>` 与技能工作目录
`skill-work/{cache,staging,backup}`。启动期只由数据目录本身清理 `resources/staging` 下崩溃残留的 `*.part`
中转文件；`skill-work` 的 staging 残留与残留备份由 `SkillPackageInstaller` 构造时的自愈恢复处理
（见 [本地存储与文本输出](#本地存储与文本输出)）。已发布的 `resources/text` 全文与已安装 Package 永不
隐式删除。HOME 只作为 READY 宿主事实，不参与资源目录、调用 cwd 或路径边界。

## 能力注册表

[`DaemonCapabilityRegistry`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonCapabilityRegistry.java)
在运行时构造完成时冻结，运行期能力集合不可变。注册的能力与
[Harness Environment](harness-environment.md) 的目录逐项对齐，descriptor 的版本与
schema 都从 catalog 取用：

- [`CodingCapabilities`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/CodingCapabilities.java) 注册 9 项编码能力：`fs.read`、`fs.write`、`fs.edit`、`process.exec`、`fs.grep`、`fs.find`、`lsp.goto-definition`、`lsp.workspace-symbols`、`lsp.java-decompile`。
- 内部 `skill.sync` 安装 Platform 指定的 exact commit；它复用通用 capability
  invocation 协议，但不注册为模型工具。

Daemon 不注册任何 MCP 能力：MCP 是 Platform 在 Backend 进程内的能力（见 [Harness MCP](harness-mcp.md)），不进入宿主执行面。

## 执行调度与连接生命周期

[`DaemonRuntime`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonRuntime.java) 是唯一创建与持有执行资源的地方：

| 资源 | 用途 |
| --- | --- |
| 单线程 `ScheduledThreadPoolExecutor` | 心跳定时、重连调度、能力调用超时与 LSP 定时任务 |
| virtual-thread-per-task executor | 编码能力的阻塞任务执行 |
| 平台线程缓存池 `lspExecutor` | LSP 客户端 stdio 的阻塞 I/O（线程数跟随客户端数，空闲自行回收） |

初始化顺序为调度器、任务执行器、LSP 执行器、传输、能力注册表、运行时实例；任一步失败都会释放已创建的资源。`start()` 幂等，启动后立即尝试连接并周期发送心跳。关闭或致命失败时按固定顺序收敛：关闭当前连接与传输接入，然后把每个在途 invocation 取消并以 `CANCELLED` 终态写入 journal，接着关闭与运行时同生命周期的 capability 资源（当前是 LSP 客户端池），最后对任务、LSP 与调度三类执行资源依次 `shutdownNow`（每个最多等待 5 秒）；单步失败不会跳过后续清理，也不会悬挂 shutdown。

重连使用指数退避：断开后按当前退避值调度下一次连接，退避倍增并以 `--reconnect-max` 封顶，连接成功后退避重置为初值。每次连接尝试递增代际，过期连接的回调与事件被静默丢弃。

### 入站校验与调用调度

入站报文先按代际归属、协议版本、envelope scope 与 `invocationId` 要求依次校验；scope 必须与当前已绑定环境一致。INVOKE 的处理顺序是：

1. `journal.start(invocationId)` 原子去重，已存在则重放既有条目并结束；
2. 能力标识未知、或 `capabilityVersion` 与 descriptor 不匹配，都直接以 `FAILED` 终态结束，不发送 `STARTED`；
3. 采用 wire 的 `timeoutMillis` 作为本次调用的唯一有效超时（Platform 已在执行前解析完成，Daemon 不回落也不截断）；
4. 构造按 schema 校验的执行请求，抢占本地执行资源；
5. 全部预检通过后才发送 `STARTED`，随后执行能力并调度超时。

参数非法、报文超限、资源持久化失败或流式事件包含非法内容，同样以确定性 `FAILED` 终态收敛。非零超时由调度器触发，抢占终态标记并按 `TIMED_OUT` 请求能力收尾；`timeoutMillis` 为 0 表示没有 execution deadline，不调度任何超时终态；收到 `CANCEL` 时，运行中的调用按 `CANCELLED` 请求能力收尾，已终结的调用重放既有终态。协议载荷非法或作用域不匹配时运行时回复 `ERROR` 并保留 journal；`REGISTRATION_REJECTED` 使进程进入 FAILED、停止重连并以非零状态退出，`RETRY_LATER` 触发断线与退避。

### 超时与取消收尾

超时与取消的终态类型由运行时裁决，绝不因为能力返回了结果而变成 `COMPLETED`：`FAILED` 表示超时，`CANCELLED` 表示调用方取消。运行时把裁决原因通过 [`EnvironmentCapabilityExecutionHandle#terminate`](harness-environment.md#执行与传输契约) 显式传给能力，能力据此产出与原因一致的收尾事实（例如进程是超时被杀还是被取消）。

能力可以在 `terminationGrace()` 内自行提交终态。运行为此开启一个有界收尾窗口：抢占终态标记后先登记兜底定时器，再请求能力收尾；能力在预算内提交终态时，运行时保留自己的终态类型与裁决原因，并把能力结果的文本正文追加到该终态正文（`FAILED` 用 `message`、`CANCELLED` 用 `reason`）。因此失败日志无需协议变更即可到达调用方，同时 `FAILED`/`CANCELLED` 的单文本字段约束保持不变。

窗口不会引入无界等待：兜底定时器到期立即提交运行时自己的终态；调度器已停机导致兜底定时器无法登记时同样立即提交；停机流程直接把在途调用写成 `CANCELLED` 并清空 journal，不等待能力配合；窗口内到达的迟到回调被 `DaemonInvocationJournal#complete` 的单向跃迁拦截，绝不产生第二个终态。注入正文只取能力结果的文本内容（与同一次调用的工具结果正文一致），超过 16 MiB 载荷上限时整体放弃注入。

### 实例身份与重连恢复

运行时构造期随机生成一次 `daemonInstanceId`，并在每个 HELLO 中声明；同一进程的所有重连复用该身份，因此 Gateway 能区分「同一 Daemon 重连」与「另一个 Daemon 进程接管」。物理连接失效只重置绑定、资源预算与等待中的上传票据，不改变 [`DaemonInvocationJournal`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/journal/DaemonInvocationJournal.java) 的事实：Gateway 以相同 `invocationId` 重放 INVOKE 时，`RUNNING` 条目重放 `STARTED(replayed=true)`，已终结条目重放对应终态报文，副作用绝不重复执行。

journal 的 `start` 原子去重、`complete` 只允许 `RUNNING` 到终态的单向跃迁，二者共同给出单次终态（terminal-once）保证；执行事实在进程整个生命周期内有效，不受连接波动影响。

### 传输

生产传输由 [`OkHttpWebSocketTransport`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/transport/OkHttpWebSocketTransport.java) 承载，强制本次握手协商 `permessage-deflate`：OkHttp 在 upgrade 请求中声明该扩展，服务端未接受时以 RFC 6455 close code `1010` 关闭且不交付连接，绝不退化为未压缩会话。通道只接收文本帧：单条文本超过 16 MiB 字符上限或收到二进制帧时，确定性发送一次 close code `1008` 策略违规关闭帧并中断连接。

## 本地存储与文本输出

Daemon 不维护本地内容寻址资源库，也不持久化 MCP 目录；本地状态只有命令输出全文与
当前安装的 Skill Packages。

Skill Package 安装目录稳定为 `<data-dir>/skills/<package>/`。安装时在包内写入
`.kkstudio-commit` marker 记录该目录对应的 exact commit，模型可见路径始终是
`<data-dir>/skills/<package>/<skill>/SKILL.md`，不包含 commit。`skill.sync` 每次都把目标 commit
重新物化进 staging 并对整棵 tree 做安全校验（拒绝符号链接、submodule、路径穿越与非普通文件），
校验通过才原子替换 Package，不因 marker 已相同而跳过；任一步失败都保留旧目录并回滚。Branch HEAD
永远不能替代调用参数中的 exact commit。

`process.exec`/`grep`/`find` 的超阈值文本经 [`TextOutputStore`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/TextOutputStore.java) 写入 `<data-dir>/resources/staging/*.part`（0600）后原子发布为 `<data-dir>/resources/text/*.log`（durable，永不隐式删除）。终态无论大小都只返回一个 `TextResultContent`：小输出完整内联，大输出为有界 head/tail 预览加绝对路径、总字节/行数与 read/grep 指引，同一事实写入 `detailsJson.textOutput`（`totalBytes`、`totalLines`、`capturedBytes`、`captureTruncated`、`captureFailed`，可发布时另有 `path` 与 `readHint`）。

预览与行数是模型直接消费的事实，因此有三条硬约束：

- **字符边界**：head/tail 的字节上界可能落在多字节字符内部，裁剪按完整字符边界回退后再解码，预览不产生 U+FFFD 替换字符，`bytes omitted` 始终是精确的原始字节差。
- **不重复**：输出量小于 head 与 tail 缓冲容量之和时，预览扣除两个窗口的重叠，同一批字节不会展示两次。
- **行数一致**：总行数按 CR、LF、CRLF 三种终止符统计（CRLF 只记一次），与 `fs.read` 经 [`TextStreams`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/TextStreams.java) 报告的总行数完全一致，模型不会看到两个互相矛盾的总数。

内联阈值为 50 KiB 或 2000 行；超过阈值转为落盘，达到捕获预算（默认 1 GiB）后只停止文件捕获并继续统计总数。输出体积与本地磁盘状态永远不是终止进程的理由：磁盘写失败只降级为无路径的有界预览，三种情况都让子进程自然退出，退出码始终是权威事实。失败路径在删除中转文件前先关闭文件流，不泄漏文件描述符也不残留幽灵文件。

成功、非零退出、超时与取消四种收尾都先发布已捕获输出，绝不为收尾丢弃唯一副本：中转文件要么被发布为 durable 全文，要么被有界预览替代，`close` 只删除从未发布的中转文件。收尾说明（超时/取消/退出码，以及「已执行的副作用不回滚」的声明）只追加到返回文本，不写入 durable 全文、也不计入 `totalBytes`/`totalLines`。终态同时用 `detailsJson.process.outcome`（`EXITED`/`TIMED_OUT`/`CANCELLED`）与文本说明区分三种失败，且只有自然退出才报告 `exitCode`。

## 编码能力

### workdir 语义

`workdir` 是每次调用自己的执行目录，不是文件系统沙箱，也没有默认值。
[`EnvironmentPaths`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/EnvironmentPaths.java)
只解析本次调用 arguments 中的路径：

- 相对 `path` 必须携带显式 `workdir`；`workdir` 先经
  [`DaemonWorkdirSyntax`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonWorkdirSyntax.java)
  按本机 OS 做词法校验，再要求自身 `Path` 视角下绝对、现存、为目录且可读；
- 绝对 `path` 自身已经完整定位，不需要 `workdir`；相对 `path` 以本次 `workdir` 为
  基准，允许目标落在 workdir 之外，符号链接照常跟随；
- `process.exec` 没有目标 path，因此始终要求显式 `workdir`；
- 目录不存在时明确失败：不自动 mkdir、不回退 HOME 或任何隐藏目录，也不沿用前一次调用的目录。

命令与文件系统的业务授权由 Platform 的权限判定负责，Daemon 不提供额外的路径沙箱。

### 进程执行范围

`process.exec` 的每一次调用都先由 [`ProcessScope`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/ProcessScope.java)
取得操作系统级的所有权，再启动用户的 `bash -lc`：Daemon 自身无法把 `ProcessBuilder` 放进一个新的 session，因此
[`ProcessScopeHelper`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/ProcessScopeHelper.java)
以独立 JVM 作为范围 keeper。两侧的握手只有调用私有状态目录里的几个原子发布文件（`scope`/`permit`/`exit`/`error`/`cleanup`，
外加 helper 自己的 `diagnostics`），目录在调用结束时删除。

顺序是这条链路唯一的安全性来源：**helper 建立范围 -> 父进程校验范围并登记自己的收敛手段 -> 父进程放行用户命令**。因此
「放行之前失败」永远等价于「用户命令没有产生任何副作用」，父进程可以直接结束 keeper；放行之后一律走完整收敛。启动阶段的取消
与超时同样在放行之前生效，并且 helper 的冷启动也计入调用方的有效 deadline。

- **POSIX（Linux/WSL/macOS）**：helper 先用 JNA 调用 libc `setsid` 建立新 session 与进程组，再启动 `bash -lc`，因此命令与它的
  普通后代从创建那一刻起就属于同一个进程组。命令的 stdin 是一条只有 helper 持有写端的空管道，helper 在启动后立刻关闭写端，所以
  「等待 EOF 的命令自然退出」只取决于这一步，而不取决于调用方何时关闭自己的写端；helper 保留 JVM 默认的 `SIGTERM` 处置（捕获而不是忽略），
  因此 `exec` 会把它复位成默认处置，脚本可以自行注册 `SIGTERM` trap 做优雅收尾；helper 自己在命令 fork 完成之后才忽略 `SIGTERM`
  （忽略状态绝不进入命令），并在发布范围之前注册收敛 hook——它是「温和信号 → 宽限 → 强杀 → 发布收敛结论」的唯一执行点，与
  命令自然退出的主路径用同一次 CAS 收敛竞争（发现收敛已在执行的一方等它结束，绝不提前放走 JVM），因此取消即使落在派发窗口
  里，helper 也会活到收敛完成。派生与收敛在同一把锁上互斥：收敛开始（`stopping`）之后绝不会有新进程诞生，命令 `fork` 的那一刻
  收敛也还没有开始，两个方向都不会穿过对方。keeper 已经退出而组里仍有
  活着的后代时，父进程按「刚刚确认仍然属于本组」的 pid 逐个强杀并重新向内核确认，不向可能被复用的组 id 广播。父进程校验发布的 scope id 必须等于 helper 自己的 pid，
  只对这样一个刚建立的进程组发信号，绝不向状态文件里出现的陌生 id 发信号。收敛 = 组里没有活着的成员：先温和信号，宽限约
  150 ms 后强杀，再向内核确认（僵尸不算活着——它已经不会再写输出或改副作用；Linux/WSL 上 helper 还会用
  `PR_SET_CHILD_SUBREAPER` 与 `waitpid` 立刻回收被收养的孤儿）。命令自然退出时 helper 先把退出码原子发布，再收敛整组并把
  「已经收敛」发布到 `cleanup`，父进程据此恢复精确退出码；只有 Linux/WSL 能区分「只剩 helper 自己」与「还有后代」，其它 POSIX
  平台的收敛结论由父进程的内核检查收口。
- **Windows**：helper 建立带 `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`、不开放 breakaway 的命名 Job，并用
  `STARTUPINFOEX` + `PROC_THREAD_ATTRIBUTE_JOB_LIST` 让首个进程在创建时就直接进入 Job（没有「已经创建但尚未归属」的间隙，
  helper 即使在此之前被打死也不会留下无主进程），同时以 `CREATE_SUSPENDED` 保持挂起；父进程用同一个名字打开第二个 Job 句柄，
  因此「终止整组」与「确认整组结束」都不依赖 helper 自己活着。创建进程时还用 `PROC_THREAD_ATTRIBUTE_HANDLE_LIST` 明确只交出
  命令的 stdin/stdout/stderr 三个句柄，helper JVM 里其它可继承句柄不会漏进用户命令；命令的 stdin 是一条由 helper 立刻关闭写端的
  空管道，因此「等待 EOF 的命令自然退出」在 Windows 上同样不依赖句柄继承是否干净。收敛 = `QueryInformationJobObject(JobObjectBasicAccountingInformation)`
  报告 `ActiveProcesses == 0`：首个进程退出、helper 退出或句柄关闭都不是整组结束的证据，父进程只有拿到这个查询结果才关闭自己
  的句柄。命令行按 MSVC 的 argv 规则拼装（裸可执行名交给 `CreateProcess` 按标准顺序解析），命令的 stderr 与 stdout 指向同一
  个继承句柄从而合并进捕获流。

标准流不经过 `ProcessScope`：用户命令继承 helper 的 stdin/stdout，父进程只是排空同一条管道；helper 自己的诊断（以及 JVM 启动
提示，例如 `JAVA_TOOL_OPTIONS`）只写调用私有的诊断文件，只在解释失败原因时被读取，因此命令输出不会多出任何合成内容。辅助
进程与原生边界只在上述平台上存在：平台无法识别、helper 无法启动、JNA 载入失败或 `setsid` 失败一律让本次调用明确失败，绝不
退化成「没有范围的直接执行」。**收敛没有被内核或 Job 确认时本次调用显式失败**（不会报告成自然退出），调用方据此知道可能仍有
进程在运行。

跨平台的收敛事实不依赖任何平台的 shell：验收测试 [`ProcessScopeCrossPlatformTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/ProcessScopeCrossPlatformTest.java) 用只依赖 JDK 的夹具 [`ProcessScopeFixtureMain`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/ProcessScopeFixtureMain.java) 作为「用户命令」运行，再由它派生子 JVM（含两层嵌套），子进程把自己写下的原生 pid 作为「真的运行过」的事实，自然退出的两种夹具还要等用例写下出口许可（捕获模式用许可文件——命令的 stdin 是一条已关闭的空管道；双向模式用 stdin 上的一个字节）之后才返回，用例因此能在根进程退出之前确认子进程仍然存活，而不是与收敛抢时间：没有许可时，快机器完全可能在用例读到 pid 之前就已经把整组收敛干净，那是正确行为，却会让活前置条件随机失败。因此「根进程自然退出后活着的子进程已在范围完结时消失」「终止覆盖孙进程」「许可之前的取消没有任何副作用」三条事实在 Linux/macOS/Windows 上都由真实进程断言，CI 矩阵（`.github/workflows/process-scope.yml`）还会断言这组用例在每台 runner 上都被真正执行、一个都不跳过。

**Windows 上的两个环境事实**：{@code bash} 的解析遵循 {@code CreateProcess} 的搜索顺序，系统目录先于 `PATH`，因此系统里存在 WSL 时裸名 `bash` 会命中 `System32\bash.exe`（无发行版，直接以退出码 1 结束）——需要 Git Bash 时必须由 operator 用 `--bash-executable` 指向它；命令进程在 Windows 上先创建后归属，命令不存在因此表现为范围建立失败（POSIX 上则表现为范围内的启动失败），两条去向都是失败关闭并带上命令名。

**边界**：范围覆盖的是同组普通后代，不是恶意命令沙箱。命令主动 `setsid`/`set -m` 重新分组、或直接把进程交给其它
session 时，它就不在这条收敛边界内；本能力不承诺阻止命令自我再分组，也不对这类逃逸做任何伪装。LSP 服务器运行在同一个执行
范围里（[`ProcessScope.startDuplex`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/ProcessScope.java)
的双向标准流模式：命令的 stdin/stdout/stderr 直接继承调用方的三条流、stderr 不被合并），因此它与按调用建立的一次性范围共享同一份
收敛语义；常驻语义只体现在生命周期顺序上——关闭时先 `shutdown` 再 `exit`，宽限窗口之后再收敛整组（含服务器自己派生的后代）。

### 文件读写与检索

`fs.read` 与 `fs.write`/`fs.edit` 共享统一的文件编码与修改边界：文本按既有编码、BOM 与行尾表示写回，同一文件的并发修改通过进程内分段锁串行化。

- **流式分页读取**：媒体类型只由文件前缀判定，文本以固定大小字符块流式解码，因此不存在「文本文件超过 N MiB 就拒绝」的上界，`process.exec` 落盘的全文可以直接被分页读取。窗口契约由共享核心 [`common.text.TextReadWindow`](../../harness/common/src/main/java/fun/fengwk/kkstudio/harness/common/text/TextReadWindow.java) 定义（本地侧经 [`LocalTextReadWindow`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/LocalTextReadWindow.java) 适配本地编码与中断）：`offset`（默认 1）、`column_offset`（默认 1）都从 1 开始，`limit` 默认且最大 2000，正文累计最多 60000 个 Unicode 码点，不计行号、元数据与行分隔符。为了给出准确的总行数与文件级 `ends_with_newline`，扫描必须读到 EOF，但只驻留窗口内容，因此内存与文件大小无关；超时、取消或线程中断结束扫描，不返回半个窗口。图片仍是 Resource 语义：探测到受支持的图片签名时整文件字节成为 `BinaryResultContent`，由终态编码阶段直传对象存储。
- **纯净编号正文**：正文不含合成截断标记，`line|` 编号后严格为真实行片段；CRLF、孤立 CR 与 LF 都只作为行边界，不进入正文，因此整个片段可以直接复制为 `fs.edit` 的 `old_string` 做精确比对替换。
- **长行与列起点**：不限制单行长度，超长行按整行内容返回；`column_offset` 是 1-based 码点偏移，只作用于起始行片段，不要求 `limit` 为 1，也不作用于后续行。
- **窗口元数据**：header 依次为 `path`、`ends_with_newline`、`range`；截断时追加 `truncated`、`truncation_reason`（`character_limit` 或 `line_limit`）与 `next`，LSP 可用时 `lsp` 作为最后一行。起点越过 EOF 或文件为空输出 `range: empty`，有效目标行上的越界列报错，未截断时省略全部截断字段。
- **续读位置**：`next` 指向第一个未返回字符，完整行边界归一到下一行第 1 列；正文之后只有一行仅含该位置的 `[TRUNCATED: ...]` 提示，不生成下一次调用教程。
- **目录与特殊文件**：目录清单保留独立的 `kind: directory` 语义与 48 KiB 展示上界，分页 `limit` 默认和上限都是 2000；设备、FIFO、socket、块设备等非普通节点在任何 I/O 之前拒绝。

`fs.grep` 与 `fs.find` 使用 Java NIO 原生遍历，全部在 JVM 内完成，不依赖外部检索二进制。忽略规则按检索目标自身解析：从目标向上取祖先 `.gitignore`（从外到内、last-match-wins，单条 pattern 由 JGit 的 gitignore 语义匹配）、仓库根的 `.git/info/exclude`（优先级低于同目录 `.gitignore`，`.git` 文件形式的 worktree 经 `gitdir`/`commondir` 解析），与调用 `workdir` 无关，`.git` 元数据始终硬排除。单行模式流式扫描，不受单文件大小上界限制，但单行超过 1 MiB 时无法宣称结果完整，降级为显式失败（目录扫描记为「未搜索路径」，绝不静默返回无匹配）；需要整文件视图的 `multiline` 保留 64 MiB 上界。文本编码与 `fs.read` 共享 `TextStreams`：UTF-8 或缺 BOM 时按 UTF-8、UTF-16LE/BE 由 BOM 判定，其余编码（如 GBK）严格解码失败并按二进制显式报错，不做替换字符降级。

LSP 能力由 Daemon 直连外部语言服务器：CLI 的 `--lsp-config` 指向一个绝对路径的 JSON 文件，声明预先安装的服务器命令、扩展名与项目根标记；缺省即禁用 LSP，服务器不会被自动安装。客户端按项目根与配置复用一条常驻 stdio 连接（没有在途请求且闲置 5 分钟后回收，关闭宽限 1 秒），查询前同步文件，位置编码按服务器声明协商。客户端的协议流与 stderr 各自持续排空：协议流完整解析，stderr 只保留有界诊断尾部，用于在服务器崩溃时报告退出码与诊断。单次请求超时或取消只发送 `$/cancelRequest`，不终止共享客户端；进程收尾才收敛整个执行范围——先 `shutdown` 再 `exit`，宽限期后整组（含服务器自己派生的后代）被收敛，退出码与诊断取服务器命令自己发布的那一份。

编码能力的参数只有一个配置来源：[`CodingToolsConfig`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/CodingToolsConfig.java) 由 `DaemonMain` 用 CLI 取值与数据目录资源根构建，不读取任何 `kkstudio.daemon.*` 系统属性。其中 `previewMaxLines = 2000` 与 `previewMaxBytes = 51200` 是固定常量，`bashExecutable` 与可选的 `lspConfig`（解析为发现表）由对应 CLI 选项覆盖。

## 二进制结果直传

Daemon 不把字节编码进 WebSocket。消息与预算的权威定义见 [Harness Environment 的载荷编解码器](harness-environment.md#载荷编解码器)；宿主侧的流程是：[`DaemonCapabilityResultCodec`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonCapabilityResultCodec.java) 在终态编码前对全部内容完成条目数、单资源与聚合资源预算预检，并确认最终 wire JSON 不超过 16 MiB，全部通过后才由 [`DaemonResourceTransferClient`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonResourceTransferClient.java) 直传对象存储：

```text
Daemon -> Gateway: RESOURCE_UPLOAD_REQUEST
Gateway -> Daemon: RESOURCE_UPLOAD_TICKET
  READY            -> 已去重命中，直接生成终态引用
  PENDING(uploadId, presigned PUT)
                   -> Daemon -> Object Storage: PUT bytes + 票据指定的原始 headers
                   -> Daemon -> Gateway: RESOURCE_UPLOAD_COMMIT
                   -> Gateway -> Daemon: READY | FAILED
Daemon -> Gateway: COMPLETED(仅 uploadId 与权威元数据)
```

一次上传由 `(invocationId, transferId)` 唯一关联，`transferId` 在控制面重试与同进程重连期间保持不变。控制帧的「检查调用仍活动」与「发送」必须在同一临界区内完成，对终态抢占互斥：控制帧要么完整发生在终态之前，要么确定不发送，终态永远不会被之后的控制帧越过。REQUEST、PUT、COMMIT 只受当前 invocation 的有效 deadline 与取消状态约束，连接暂时失效时等待同一实例重连后以相同 `transferId` 重放；已经上传过的字节只重新提交，绝不重复传输。

PUT 请求完全按票据的已签名事实构造：方法与 headers 与签名一致，不自造或覆盖 `Content-Type`，整个 HTTP 调用不越过 invocation 剩余 deadline。存储拒绝只报告状态码；预签名 URL、签名 header 与字节都不进入日志、异常文本、终态 payload 或 history，终态 resource 只携带全局 `uploadId`、媒体类型、名称、大小、SHA-256 与可选预览。

## 包架构

| 包路径 | 职责与边界 |
| --- | --- |
| `fun.fengwk.kkstudio.harness.daemon` | 进程启动入口与运行时编排。解析启动配置（`DaemonConfig`、`DaemonTokenFile`、`DaemonDataDirectory`）、冻结能力注册表（`DaemonCapabilityRegistry`）、持有任务、LSP 与调度三类执行资源、驱动握手与重连状态机、按 wire 有效超时裁决能力调用 deadline 并按 message type 校验入站报文。 |
| `fun.fengwk.kkstudio.harness.daemon.coding` | 编码能力实现：文件读写与编辑、命令执行、原生文本检索与 LSP 桥接。`EnvironmentPaths` 只用显式 `workdir` 解析相对路径，绝不把 HOME 当作文件系统沙箱或会话默认目录；`ProcessScope`/`ProcessScopeHelper` 在命令启动前建立 POSIX 进程组或 Windows Job Object 作为收敛边界；大文本经 `TextOutputStore` 落盘为本地日志，二进制结果由终态编码阶段直传对象存储；调用之间不继承目录。 |
| `fun.fengwk.kkstudio.harness.daemon.journal` | 进程内调用执行事实与去重日志。跟踪 invocation 的 `RUNNING` 与终态，以原子操作保证单次执行并记录终态结果；重连后的重复 `INVOKE` 幂等重放 `STARTED` 或终态报文。日志在进程整个生命周期内有效，连接断开不改变执行状态。 |
| `fun.fengwk.kkstudio.harness.daemon.skill` | Skill Package 安装面。`SkillPackageInstaller` 把 Platform 指定的 exact commit 拉取进 `skill-work/cache` 的 bare cache（origin URL 与请求不一致时整份丢弃重建，绝不复用其它仓库的对象）、在 `skill-work/staging` 物化校验后原子替换 `<data-dir>/skills/<package>/`，失败保留旧目录并回滚；构造时自愈清理 staging 残留与残留备份；物化拒绝绝对路径、`..` 逃逸、符号链接与 gitlink。capability 协议本身在 `coding` 包实现。 |
| `fun.fengwk.kkstudio.harness.daemon.transport` | 底层网络传输抽象与基于 OkHttp WebSocket 的生产实现。提供连接管理、文本帧收发与传输监听，强制协商 `permessage-deflate`，并对单消息累积体积与二进制帧执行策略违规关闭。 |

## 源码与测试

源码入口按包分组（包职责见上表）：

- 进程与运行时：[`daemon/DaemonMain.java`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonMain.java)、[`DaemonConfig.java`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonConfig.java)、[`DaemonDataDirectory.java`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonDataDirectory.java)、[`DaemonRuntime.java`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonRuntime.java)、[`DaemonCapabilityRegistry.java`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonCapabilityRegistry.java)、[`DaemonResourceTransferClient.java`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonResourceTransferClient.java)。
- 能力实现与本地状态：[`CodingCapabilities.java`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/CodingCapabilities.java)、[`EnvironmentPaths.java`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/EnvironmentPaths.java)。

测试守卫：

- [`DaemonModuleArchitectureTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/DaemonModuleArchitectureTest.java)：模块依赖方向、能力实现所需的 import 白名单与线程池所有权边界。
- [`DaemonConfigTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/DaemonConfigTest.java)、[`DaemonMainTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/DaemonMainTest.java)、[`DaemonTokenFileTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/DaemonTokenFileTest.java)：选项默认值与唯一性、凭证不外泄、信息命令、未知参数 fail-closed、token 文件的绝对路径与权限规则。
- [`DaemonDataDirectoryTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/DaemonDataDirectoryTest.java)：owner-only 布局、进程内与跨 JVM 独占锁、重启后锁释放、启动期只清理遗留 `.part` 并保留已发布数据。
- [`DaemonRuntimeTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/DaemonRuntimeTest.java)：握手与重连、`REGISTRATION_REJECTED`/`RETRY_LATER` 分支、入站协议校验、超时裁决与超时/取消收尾窗口（终态类型、已捕获输出注入、兜底收敛、停机收敛）、取消与终态重放、上传在重连后的恢复与去重。
- [`OkHttpWebSocketTransportTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/transport/OkHttpWebSocketTransportTest.java)：`permessage-deflate` 协商门禁、文本帧传输、二进制拦截与超限关闭。
- [`WorkdirPathSemanticsTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/WorkdirPathSemanticsTest.java)、[`CodingCapabilitiesTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/CodingCapabilitiesTest.java)、[`NativeSearchCapabilitiesTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/NativeSearchCapabilitiesTest.java)、[`FindGrepCapabilitiesTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/FindGrepCapabilitiesTest.java)、[`GitIgnoreDiscoveryTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/GitIgnoreDiscoveryTest.java)：显式 workdir 语义、编码能力端到端行为、原生检索的匹配/上限/失败/超时、`.gitignore` 与 `info/exclude` 的分层解析、LSP 有效超时与取消终止进程树。检索行为的源用例映射见[内置检索测试映射](../operations/builtin-search-tests.md)。
- [`OutputSpoolTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/OutputSpoolTest.java)、[`TextOutputStoreTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/TextOutputStoreTest.java)、[`BashCapabilityTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/BashCapabilityTest.java)、[`ProcessScopeTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/ProcessScopeTest.java)、[`WindowsCommandLineTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/WindowsCommandLineTest.java)、[`WindowsJobScopeTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/WindowsJobScopeTest.java)、[`ProcessScopeCrossPlatformTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/ProcessScopeCrossPlatformTest.java)（夹具 [`ProcessScopeFixtureMain.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/ProcessScopeFixtureMain.java)）：内联与落盘阈值、预览字符边界与重叠去重、行数一致性、捕获预算与磁盘失败降级（含 durable 发布失败）、原子发布、合并流的大输出完整捕获、启动前收尾不启动 helper、stdin 立即关闭、超时/取消/退出三种收尾的输出保留与 `process.outcome` 区分、自然退出/`exec`/`disown`/嵌套 fork 下后台后代在终态通知前收敛、忽略温和信号的后代由强杀阶段收敛、用户 EXIT trap 与精确退出码保真、范围 keeper 的失败关闭与私有状态目录清理、异常路径（调度被拒、监听器抛错）下先收敛范围再通知终态（读端在收敛后才关闭，后台后代不会提前脱离可达范围）、Windows 命令行 argv 转义规则与 Job 结构布局、无 shell 依赖的跨平台收敛事实（自然退出收敛活着的子进程、终止覆盖孙进程、许可前取消无副作用）、取消落在派发窗口时整组（含忽略温和信号的后代）仍由内核确认收敛、双向标准流下命令真的收到 stdin 字节且 stderr 不被合并、根进程自然退出时留下的子进程同样收敛，以及 LSP 客户端生命周期（含启动失败时收敛服务器留下的、忽略温和信号的子进程）所用的收敛顺序。

---

上级：[系统设计](../system-design.md)。相关文档：[内置 Bash 测试映射](../operations/builtin-bash-tests.md)、[Harness Environment](harness-environment.md)、[Harness Environment Server](harness-environment-server.md)、[Harness MCP](harness-mcp.md)、[Harness Common](harness-common.md)、[Platform](platform.md)、[Environment Daemon 安装与运行](../operations/environment-daemon.md)。
