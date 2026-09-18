# Harness Daemon

Environment Daemon 是运行在目标宿主上的独立 JVM 进程。它把 Platform 下发的原子能力调用落到真实文件系统、真实进程与本地工具链上，并把执行事实保留在自身进程内：网络连接只是消息管道，连接中断与重建不会改变已经开始的执行。整个环境链路的契约在 [Harness Environment](harness-environment.md)，Platform 侧的会话与租约协调在 [Harness Environment Server](harness-environment-server.md)，安装、systemd 常驻与升级流程见 [Environment Daemon 安装与运行](../operations/environment-daemon.md)。

模块依赖只有 `harness-common`、`harness-environment` 与 `harness-mcp`，第三方依赖是 Jackson、OkHttp、JGit 与 RE2/J；它不依赖 `harness-tool`、`harness-runtime`、`harness-infra`、`platform` 或 `web`。调用侧传下来的每个调用都自带完整参数，Daemon 不从模型、会话或历史中推断任何执行事实。

## 启动、CLI 与本地数据目录

[`DaemonMain`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonMain.java) 的装配顺序固定：

```text
CLI -> DaemonConfig
   -> DaemonDataDirectory.open(dataDir)          # owner-only 布局 + daemon.lock
   -> CodingToolsConfig.fromCli(environmentRoot, dataDir/resources, ...)
   -> DaemonSkillRegistry.open(dataDir, userHome)
   -> DaemonRuntime.create
   -> shutdown hook -> start -> awaitTermination
```

单个 `--help`/`-h` 或 `--version` 是纯信息命令，在打开数据目录之前输出并直接返回；混用或多余参数一律交给配置解析并失败关闭。注册被拒时进程进入 FAILED，向 stderr 输出原因并以非零状态码退出。

[`DaemonConfig`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonConfig.java) 的选项与默认值：

| 选项 | 默认值 | 说明 |
| --- | --- | --- |
| `--gateway-uri` | 必填 | 只接受 `ws`/`wss` |
| `--registration-token-file` | 必填 | 绝对路径的 owner-only 普通文件，只能出现一次 |
| `--heartbeat` | `PT15S` | 心跳间隔 |
| `--reconnect-initial` | `PT1S` | 首次重连退避 |
| `--reconnect-max` | `PT30S` | 重连退避上限 |
| `--tool-timeout` | `PT5M` | 能力调用默认超时 |
| `--note` | 按操作系统生成 | READY 中的可信备注，单行且不超过 512 字符，只能出现一次 |
| `--environment-root` | 启动用户 HOME 的 canonical 路径 | 只作 READY 展示元数据，只能出现一次 |
| `--data-dir` | `~/.kk-studio` | 本地数据目录，显式给出时必须绝对，只能出现一次 |
| `--bash-executable` | `bash` | `process.exec` 使用的 shell |
| `--lsp-bridge-command` | 缺省禁用 | 启用 LSP 桥接所需的本机命令 |
| `--javap-executable` | `javap` | class 反编译回退程序 |

注册凭证只以 owner-only 普通文件存在：CLI 只接收路径，配置对象不保存凭证文本，因此 `equals`/`hashCode`/`toString` 与日志都不会扩散秘密，凭证在每次 HELLO 前按需读取。[`DaemonTokenFile`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonTokenFile.java) 要求绝对路径、现存普通文件（拒绝符号链接与目录）与 owner-only 权限，并忽略两端空白。未知选项（包括历史遗留的 `--registration-token`、`--skill-dir`）一律启动失败，没有兼容回退。

[`DaemonDataDirectory`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonDataDirectory.java) 以 owner-only 权限创建固定布局（POSIX 目录 0700、文件 0600），持有 `daemon.lock` 的进程独占锁，因此同一目录上的第二个 Daemon 立即失败而不是并发写同一份本地数据。启动只清理 `resources/staging` 下遗留的 `*.part`；已发布的 `resources/text` 永不自动删除，因为 durable history 仍可能引用其中的路径。`--environment-root` 同样只是展示元数据，不参与资源目录、调用 cwd 或路径边界。

## 能力注册表

[`DaemonCapabilityRegistry`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonCapabilityRegistry.java) 在运行时构造完成时冻结，运行期能力集合不可变。注册的 15 项能力与 [Harness Environment](harness-environment.md) 的目录逐项对齐，descriptor 的版本与 schema 都从 catalog 取用：

- [`CodingCapabilities`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/CodingCapabilities.java) 注册 9 项编码能力：`fs.read`、`fs.write`、`fs.edit`、`process.exec`、`fs.grep`、`fs.find`、`lsp.goto-definition`、`lsp.workspace-symbols`、`lsp.java-decompile`；
- [`SkillLoadCapability`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/skill/SkillLoadCapability.java) 注册 `skill.load`，[`McpLocalCallCapability`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/mcp/McpLocalCallCapability.java) 注册 `mcp.local.call`；
- 三个 [`DaemonSkillSourceCapability`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/skill/DaemonSkillSourceCapability.java) 实例注册 `skill.source.refresh`/`install`/`update`，[`McpLocalDiscoverCapability`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/mcp/McpLocalDiscoverCapability.java) 注册 `mcp.local.discover`。

四项管理能力只复用 INVOKE/CANCEL/终态通道，不注册为模型 Tool。

## 执行调度与连接生命周期

[`DaemonRuntime`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonRuntime.java) 是唯一创建与持有执行资源的地方：

| 资源 | 用途 |
| --- | --- |
| 单线程 `ScheduledThreadPoolExecutor` | 心跳定时、重连调度、能力调用超时 |
| virtual-thread-per-task executor | 编码能力与 Local MCP 的阻塞任务执行 |
| [`DaemonLocalMcpManager`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/mcp/DaemonLocalMcpManager.java) | 本地 MCP stdio 子进程生命周期 |

初始化顺序为调度器、执行器、传输、能力注册表、运行时实例；任一步失败都会释放已创建的资源。`start()` 幂等，启动后立即尝试连接并周期发送心跳。关闭或致命失败时按固定顺序收敛：关闭当前连接与传输接入，再关闭 MCP 管理器以强制终止子进程树，然后把每个在途 invocation 取消并以 `CANCELLED` 终态写入 journal，最后对两个线程池执行 `shutdownNow`（每个最多等待 5 秒）；单步失败不会跳过后续清理，也不会悬挂 shutdown。

重连使用指数退避：断开后按当前退避值调度下一次连接，退避倍增并以 `--reconnect-max` 封顶，连接成功后退避重置为初值。每次连接尝试递增代际，过期连接的回调与事件被静默丢弃。

### 入站校验与调用调度

入站报文先按代际归属、协议版本、envelope scope 与 `invocationId` 要求依次校验；scope 必须与当前已绑定环境一致。INVOKE 的处理顺序是：

1. `journal.start(invocationId)` 原子去重，已存在则重放既有条目并结束；
2. 能力标识未知、或 `capabilityVersion` 与 descriptor 不匹配，都直接以 `FAILED` 终态结束，不发送 `STARTED`；
3. 解析有效超时，优先级为「请求指定值 → descriptor 声明值 → Daemon 默认值」；
4. 构造按 schema 校验的执行请求，抢占本地执行资源；
5. 全部预检通过后才发送 `STARTED`，随后执行能力并调度超时。

参数非法、报文超限、资源持久化失败或流式事件包含非法内容，同样以确定性 `FAILED` 终态收敛。超时由调度器触发，抢占终态标记并取消底层句柄；收到 `CANCEL` 时，运行中的调用回复 `CANCELLED` 并取消句柄，已终结的调用重放既有终态。协议载荷非法或作用域不匹配时运行时回复 `ERROR` 并保留 journal；`REGISTRATION_REJECTED` 使进程进入 FAILED、停止重连并以非零状态退出，`RETRY_LATER` 触发断线与退避。

### 实例身份与重连恢复

运行时构造期随机生成一次 `daemonInstanceId`，并在每个 HELLO 中声明；同一进程的所有重连复用该身份，因此 Gateway 能区分「同一 Daemon 重连」与「另一个 Daemon 进程接管」。物理连接失效只重置绑定、资源预算与等待中的上传票据，不改变 [`DaemonInvocationJournal`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/journal/DaemonInvocationJournal.java) 的事实：Gateway 以相同 `invocationId` 重放 INVOKE 时，`RUNNING` 条目重放 `STARTED(replayed=true)`，已终结条目重放对应终态报文，副作用绝不重复执行。

journal 的 `start` 原子去重、`complete` 只允许 `RUNNING` 到终态的单向跃迁，二者共同给出单次终态（terminal-once）保证；执行事实在进程整个生命周期内有效，不受连接波动影响。

### 传输

生产传输由 [`OkHttpWebSocketTransport`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/transport/OkHttpWebSocketTransport.java) 承载，强制本次握手协商 `permessage-deflate`：OkHttp 在 upgrade 请求中声明该扩展，服务端未接受时以 RFC 6455 close code `1010` 关闭且不交付连接，绝不退化为未压缩会话。通道只接收文本帧：单条文本超过 16 MiB 字符上限或收到二进制帧时，确定性发送一次 close code `1008` 策略违规关闭帧并中断连接。

## 本地存储与文本输出

Daemon 不维护本地内容寻址资源库。数据目录下的本地状态分两类：命令输出的 durable 全文，以及 Skill 快照。

`process.exec`/`grep`/`find` 的超阈值文本经 [`TextOutputStore`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/TextOutputStore.java) 写入 `<data-dir>/resources/staging/*.part`（0600）后原子发布为 `<data-dir>/resources/text/*.log`（durable，永不隐式删除）。终态无论大小都只返回一个 `TextResultContent`：小输出完整内联，大输出为有界 head/tail 预览加绝对路径、总字节/行数与 read/grep 指引，同一事实写入 `detailsJson.textOutput`（`totalBytes`、`totalLines`、`capturedBytes`、`captureTruncated`、`captureFailed`，可发布时另有 `path` 与 `readHint`）。

预览与行数是模型直接消费的事实，因此有三条硬约束：

- **字符边界**：head/tail 的字节上界可能落在多字节字符内部，裁剪按完整字符边界回退后再解码，预览不产生 U+FFFD 替换字符，`bytes omitted` 始终是精确的原始字节差。
- **不重复**：输出量小于 head 与 tail 缓冲容量之和时，预览扣除两个窗口的重叠，同一批字节不会展示两次。
- **行数一致**：总行数按 CR、LF、CRLF 三种终止符统计（CRLF 只记一次），与 `fs.read` 经 [`TextStreams`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/TextStreams.java) 报告的总行数完全一致，模型不会看到两个互相矛盾的总数。

内联阈值为 50 KiB 或 2000 行；超过阈值转为落盘，达到捕获预算（默认 1 GiB）后只停止文件捕获并继续统计总数。输出体积与本地磁盘状态永远不是终止进程的理由：磁盘写失败只降级为无路径的有界预览，三种情况都让子进程自然退出，退出码始终是权威事实。失败路径在删除中转文件前先关闭文件流，不泄漏文件描述符也不残留幽灵文件。

## 编码能力

### workdir 语义

`workdir` 是每次调用自己的执行目录，不是文件系统沙箱，也没有默认值。[`EnvironmentPaths`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/EnvironmentPaths.java) 只解析本次调用 arguments 中的路径：

- `workdir` 必填，先经 [`DaemonWorkdirSyntax`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/daemon/DaemonWorkdirSyntax.java) 按本机 OS 做词法校验，再要求自身 `Path` 视角下绝对、现存、为目录且可读，最后返回真实路径；
- 相对 `path` 以本次 `workdir` 为基准解析，绝对 `path` 直接使用，允许落在 workdir 之外；符号链接照常跟随；
- 目录不存在时明确失败：不自动 mkdir、不回退 HOME、不回退 Environment root，也不沿用前一次调用的目录。

命令与文件系统的业务授权由 Platform 的权限判定负责，Daemon 不提供额外的路径沙箱。

### 文件读写与检索

`fs.read` 与 `fs.write`/`fs.edit` 共享统一的文件编码、预览截断与修改边界：文本按既有编码、BOM 与行尾表示写回，同一文件的并发修改通过进程内分段锁串行化。

- **流式分页读取**：媒体类型只由文件前缀判定，文本以固定大小字符块流式解码，因此不存在「文本文件超过 N MiB 就拒绝」的上界，`process.exec` 落盘的全文可以直接被分页读取。图片仍是 Resource 语义：探测到受支持的图片签名时整文件字节成为 `BinaryResultContent`，由终态编码阶段直传对象存储。
- **纯净编号正文**：正文中不保留合成截断标记，`line|` 编号后严格为按 LF 归一化的真实行片段，可直接完整复制为 `fs.edit` 的 `old_string` 做精确比对替换。
- **长行列分页**：单行超过 2000 码点时按 Unicode 码点切片，不截断代理对；可选参数 `column_offset` 是 1-based 正整数码点偏移，仅限纯文本文件，指定时 `limit` 缺省为 1 且必须等于 1。
- **精确有界输出**：包含 header、编号正文、分隔空行与续读尾注在内的完整 UTF-8 输出严格受控于 48 KiB；下一行导致超限时按整行有界截断并给出续读 offset。
- 越界定位元数据与续读提示统一置于非编号尾部，不污染正文编号结构。

`fs.grep` 与 `fs.find` 使用 Java NIO 原生遍历，并用 JGit 规则解析检索目标祖先链上的 `.gitignore`，全部在 JVM 内完成：单行模式流式扫描，不受单文件大小上界限制；只有需要整文件视图的 `multiline` 模式保留 64 MiB 上界。LSP 能力通过可选的本地进程桥接承载，缺少工具链时返回明确的不可用提示，`lsp.java-decompile` 对可解析的 class 目标支持回退到 `javap`。LSP bridge 与 `javap` 子进程经 [`ChildProcessRunner`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/ChildProcessRunner.java) 并发排空 stdout/stderr：stdout 是调用方载荷故完整保留，stderr 只保留有界诊断尾部；deadline 绑定调用的有效超时，超时或取消都终止整棵进程树。

编码能力的参数只有一个配置来源：[`CodingToolsConfig`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/CodingToolsConfig.java) 由 `DaemonMain` 用 CLI 取值与数据目录资源根构建，不再读取任何 `kkstudio.daemon.*` 系统属性。其中 `previewMaxLines = 2000` 与 `previewMaxBytes = 51200` 是固定常量，`bashExecutable`、`javapExecutable` 与可选的 `lspBridgeCommand` 由对应 CLI 选项覆盖。

## Skill 来源与加载

[`DaemonSkillRegistry`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/skill/DaemonSkillRegistry.java) 是进程内的 Skill 目录事实源，持久事实位于 `--data-dir/skills`：

```text
manifest.json                          当前成功发布的来源快照与 retained 索引
bodies/<contentRevision>.md            不可变正文 blob
checkouts/<sourceId>/<commit>/         去除 .git 后的不可变 Git checkout
staging/                               每次 Git 操作的独立临时目录
```

来源配置只通过三个管理能力进入 Daemon，三者共享同一严格 schema，且都不接受 `workdir`：

| 能力 | 语义 |
| --- | --- |
| `skill.source.refresh` | PATH 来源按配置重新扫描；GIT 来源只扫描已持久化的 `currentlyAppliedRevision`，不联网 |
| `skill.source.install` | 仅适用于 GIT：在独立 staging 中用宿主 `git` 获取并固定 commit，扫描成功后发布 |
| `skill.source.update` | 仅适用于 GIT：无显式 ref 时重新解析远端默认 HEAD，显式 tag/commit/ref 始终固定到 commit |

PATH 来源接受目标宿主绝对路径或 `~/`（仅在来源配置中按 Daemon HOME 展开）；默认来源目录不存在时发布空快照，显式来源不存在则失败。扫描以根 `SKILL.md` 表示单个 Skill，否则递归发现；进入 Skill 根后停止下探，并跳过 `.git` 与常见依赖、缓存目录。单个坏 Skill 形成有界诊断，跨来源同名冲突、陈旧 `sourceVersion`/`sourceSetVersion` 或完整候选不合法则拒绝整个发布。

发布在进程内串行完成：先验证 READY 规模（至多 512 个来源、4096 个 Skill）、全局名称唯一性与 retained 一致性，再写不可变正文，最后以同目录原子 move 替换 manifest；文件系统不支持原子 move 时 fail-closed。每来源 publication ticket 只裁决同一 `sourceVersion` 的重叠操作，更高 `sourceVersion` 始终优先；全局 `sourceSetVersion` 防止延迟操作裁剪更新的来源集合。当前快照中的每个 descriptor 必须保留在 retained 索引中（非当前历史记录上限为 2048），因此失败不会改变当前 READY 快照，启动也能从 manifest 恢复；任一 retained body 缺失或不可读时拒绝 READY。

READY 读取 `DaemonSkillRegistry.inventory()`：它在发布锁内一次性取出集合版本与来源快照，因此报告中的 `sourceSetVersion` 与 `skillSources` 必然来自同一次发布，不会出现「新版本号配旧快照」。

`skill.load` 的 arguments 固定为 `{sourceId, name, revision}`，成功结果为 `{body, baseDirectory}`；正文按 SKILL.md 原始字节的 SHA-256 revision 保留，请求只精确匹配冻结 revision，不可用时以 `RESOURCE_CHANGED` 结束，绝不回退到同名当前版本。扫描前会验证成功结果能落入通用 JSON 内容 1 MiB 的 wire 上限。Skill 加载与来源管理都不依赖会话目录。

## Local MCP

Local MCP 以 stdio 子进程形式在绑定的目标 Environment Daemon 内执行，配置由 Platform 通过 `mcp.local.call` / `mcp.local.discover` 下发。[`DaemonLocalMcpParser`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/mcp/DaemonLocalMcpParser.java) 严格解析配置：`type=local`、`environmentId`、`command` 与 `cwd` 必填，`env`、`enabled`、`timeoutMillis`（默认 60 秒）可选；整值 `${VAR}` 由 Daemon 运行时经 `System.getenv` 解析，`cwd` 只要求本机 `Path` 视图下的绝对路径，受信任执行且没有路径白名单。请求声明的 `environmentId` 一旦与当前绑定不一致即 fail-closed。

[`DaemonLocalMcpManager`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/mcp/DaemonLocalMcpManager.java) 按 `(serverId, configVersion)` 懒共享子进程：更高 `configVersion` 自动 fencing 旧版本，旧版本在活动调用归零后关闭（drain），同版本配置漂移直接 fail-closed；执行失败的实例标记为失败，新调用立刻创建新实例，旧实例仍可服务并发中的调用直到排空。两个能力共享同一份 client，使用单一绝对 deadline 覆盖懒初始化与执行，支持单调用精确取消且不打断并发调用；取消或超时不会把共享 client 标记为失败。

## 二进制结果直传

Daemon 不把字节编码进 WebSocket。消息与预算的权威定义见 [Harness Environment 的载荷编解码器](harness-environment.md#载荷编解码器)；宿主侧的流程是：`DaemonCapabilityResultCodec` 在终态编码前对全部内容完成条目数、单资源与聚合资源预算预检，并确认最终 wire JSON 不超过 16 MiB，全部通过后才由 [`DaemonResourceTransferClient`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonResourceTransferClient.java) 直传对象存储：

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
| `fun.fengwk.kkstudio.harness.daemon` | 进程启动入口与运行时编排。解析启动配置（`DaemonConfig`、`DaemonTokenFile`、`DaemonDataDirectory`）、冻结能力注册表（`DaemonCapabilityRegistry`）、持有双线程池与 MCP 管理器资源、驱动握手与重连状态机、解析调用超时并按 message type 校验入站报文。 |
| `fun.fengwk.kkstudio.harness.daemon.coding` | 编码能力实现：文件读写与编辑、命令执行、原生文本检索与 LSP 桥接。`EnvironmentPaths` 只解析本次调用 arguments 中的显式绝对 `workdir`，绝不把它当作文件系统沙箱或会话默认目录；大文本经 `TextOutputStore` 落盘为本地 durable 日志，二进制结果不落本地存储而是由终态编码阶段直传对象存储；调用之间不继承目录。 |
| `fun.fengwk.kkstudio.harness.daemon.journal` | 进程内调用执行事实与去重日志。跟踪 invocation 的 `RUNNING` 与终态，以原子操作保证单次执行并记录终态结果；重连后的重复 `INVOKE` 幂等重放 `STARTED` 或终态报文。日志在进程整个生命周期内有效，连接断开不改变执行状态。 |
| `fun.fengwk.kkstudio.harness.daemon.mcp` | Local stdio MCP 的配置解析、client 代际与子进程生命周期。按 `(serverId, configVersion)` 共享实例，新版本 fencing 旧版本并在活动调用归零后排空，支持单调用精确取消；对外注册管理专用 `mcp.local.discover` 与模型运行时 `mcp.local.call`。 |
| `fun.fengwk.kkstudio.harness.daemon.skill` | Platform 受管 PATH/GIT Skill 来源的发现、持久化与精确加载。完整候选快照经校验后原子发布到 `--data-dir/skills`；READY 按来源通告版本、revision、Skill 描述与诊断；`skill.load` 按 `(sourceId, name, revision)` 返回正文与基目录；不接受会话 `workdir`。 |
| `fun.fengwk.kkstudio.harness.daemon.transport` | 底层网络传输抽象与基于 OkHttp WebSocket 的生产实现。提供连接管理、文本帧收发与传输监听，强制协商 `permessage-deflate`，并对单消息累积体积与二进制帧执行策略违规关闭。 |

## 源码与测试

主要源码入口：[`DaemonMain.java`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonMain.java)、[`DaemonConfig.java`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonConfig.java)、[`DaemonDataDirectory.java`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonDataDirectory.java)、[`DaemonRuntime.java`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonRuntime.java)、[`DaemonCapabilityRegistry.java`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonCapabilityRegistry.java)、[`CodingCapabilities.java`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/CodingCapabilities.java)、[`EnvironmentPaths.java`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/EnvironmentPaths.java)、[`DaemonSkillRegistry.java`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/skill/DaemonSkillRegistry.java)、[`DaemonLocalMcpManager.java`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/mcp/DaemonLocalMcpManager.java)、[`DaemonResourceTransferClient.java`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonResourceTransferClient.java)。

测试守卫：

- [`DaemonModuleArchitectureTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/DaemonModuleArchitectureTest.java)：模块依赖方向、能力实现所需的 import 白名单与线程池所有权边界。
- [`DaemonConfigTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/DaemonConfigTest.java)、[`DaemonMainTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/DaemonMainTest.java)、[`DaemonTokenFileTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/DaemonTokenFileTest.java)：选项默认值与唯一性、凭证不外泄、信息命令、未知参数 fail-closed、token 文件的绝对路径与权限规则。
- [`DaemonDataDirectoryTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/DaemonDataDirectoryTest.java)：owner-only 布局、进程内与跨 JVM 独占锁、重启后锁释放、启动期只清理遗留 `.part` 并保留已发布数据。
- [`DaemonRuntimeTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/DaemonRuntimeTest.java)：握手与重连、`REGISTRATION_REJECTED`/`RETRY_LATER` 分支、入站协议校验、超时裁决、取消与终态重放、上传在重连后的恢复与去重、停机收敛。
- [`OkHttpWebSocketTransportTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/transport/OkHttpWebSocketTransportTest.java)：`permessage-deflate` 协商门禁、文本帧传输、二进制拦截与超限关闭。
- [`WorkdirPathSemanticsTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/WorkdirPathSemanticsTest.java)、[`CodingCapabilitiesTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/CodingCapabilitiesTest.java)、[`NativeSearchCapabilitiesTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/NativeSearchCapabilitiesTest.java)：显式 workdir 语义、编码能力端到端行为、`.gitignore` 检索、LSP 有效超时与取消终止进程树。
- [`OutputSpoolTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/OutputSpoolTest.java)、[`TextOutputStoreTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/TextOutputStoreTest.java)、[`ChildProcessRunnerTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/ChildProcessRunnerTest.java)：内联与落盘阈值、预览字符边界与重叠去重、行数一致性、捕获预算与磁盘失败降级、原子发布、stdout 完整保留与 stderr 有界诊断。
- [`DaemonSkillRegistryTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/skill/DaemonSkillRegistryTest.java)、[`DaemonGitManagerTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/skill/DaemonGitManagerTest.java)、[`SkillLoadCapabilityTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/skill/SkillLoadCapabilityTest.java)：来源扫描与持久恢复、版本围栏、Git ref 固定与取消、精确 revision 加载与 `RESOURCE_CHANGED`。
- [`DaemonLocalMcpParserTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/mcp/DaemonLocalMcpParserTest.java)、[`DaemonLocalMcpManagerTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/mcp/DaemonLocalMcpManagerTest.java)、[`McpLocalCapabilityTest.java`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/mcp/McpLocalCapabilityTest.java)：配置严格解析与 `${VAR}` 解析、代际 fencing 与排空、单一 deadline 与取消不打断并发调用。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Environment](harness-environment.md)、[Harness Environment Server](harness-environment-server.md)、[Harness MCP](harness-mcp.md)、[Harness Common](harness-common.md)、[Platform](platform.md)、[Environment Daemon 安装与运行](../operations/environment-daemon.md)。
