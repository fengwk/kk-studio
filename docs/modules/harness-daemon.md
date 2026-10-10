# Harness Daemon

Environment Daemon 是目标宿主上的独立 JVM 进程，把 Platform 的原子能力调用落到文件系统、进程与本地工具链。网络连接只是消息管道：同一 Daemon 进程断线重连，不会重启已受理的执行。安装与三平台管理见 [Environment Daemon 安装与运行](../operations/environment-daemon.md)；共享契约见 [Harness Environment](harness-environment.md)，服务端会话见 [Harness Environment Server](harness-environment-server.md)。

模块依赖 `harness-common`、`harness-environment` 及 Jackson、OkHttp、JGit、RE2/J、LSP4J、JNA。官方发布物将这些依赖和 LSP client 打成 shaded JAR；语言服务器与 Bash 由宿主提供。

## 启动与本地状态

[`DaemonMain`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonMain.java) 先解码参数，再处理信息命令或解析配置，随后打开数据目录、装配能力和运行时，注册 shutdown hook，连接 gateway 并等待结束。

单独 `--help` / `-h` 或 `--version` 不打开数据目录。机器入口 `--base64-args` 必须在首位，后续每个 token 是一个原始应用参数的 UTF-8 Base64；[`DaemonArguments`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonArguments.java) 严格解码一次，拒绝非法 Base64、UTF-8、null 与 NUL，且不回显原值。它不是加密或第二套配置来源。Windows 安装器用该入口传应用参数，并通过任务的 Unicode 字段设置 Java 路径和工作目录，以 ASCII 相对 JAR 名规避 JDK 21 launcher 的 ANSI argv 转换损失。

[`DaemonConfig`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonConfig.java) 只消费唯一的 `--config <绝对路径>`：配置文件是 `daemon.json`，其父目录就是运行数据目录，同目录的 `daemon.token` 是 owner-only 注册凭证。gateway 由配置里的 `studioUrl` 派生，心跳/重连固定 `PT15S`/`PT1S`/`PT30S`。进程不接受 token 文本，也不接受 gateway、数据目录或 LSP 文件路径等第二配置来源；完整参数见安装指南。

[`DaemonTokenFile`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonTokenFile.java) 校验绝对路径、普通文件和非符号链接，在 POSIX 上要求属主可读且无 group/other 权限位。它不核对 Unix UID，也不复核 Windows DACL；当前用户所有权和 Windows ACL 由安装器检查。配置对象只保存路径，每次 HELLO 前读取 UTF-8 内容并去除外围空白，凭证不进入配置对象的 `toString` 或日志。

[`DaemonDataDirectory`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonDataDirectory.java) 持有 `daemon.lock` 独占文件锁，同一目录的第二个进程立即失败。布局为：

```text
<data-dir>/
  daemon.lock
  tmp/workspaces/<uuid>/     # 受控临时 workspace（*.part 中转 → 原子发布 *.log）
  skills/<package>/          # 已安装技能包
  skill-work/cache/          # bare Git 缓存
  skill-work/staging/        # 安装暂存
  skill-work/backup/         # 替换备份
```

POSIX 目录为 0700、文件为 0600；非 POSIX 文件系统退回 Java `File` 的 owner-only 设置，不等同于安装器对 token 的显式 Windows DACL 校验。`tmp/workspaces` 由受控临时存储按 TTL 自动清扫承担（见下）；技能安装器负责恢复或清理 staging/backup；已发布全文由定时清扫按保留期回收，已发布技能包与 Git 缓存由安装器保留、不随服务卸载自动删除。

## 能力注册与调度

[`DaemonCapabilityRegistry`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonCapabilityRegistry.java) 在运行时构造结束前冻结，生产装配必须与共享 catalog 完全一致：9 项编码能力，以及内部 `skill.sync`。LSP 没配置时仍注册对应能力，但查询返回明确的不可用错误。MCP 由 Platform 在 Backend 内承载，不进入 Daemon。

[`DaemonRuntime`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/DaemonRuntime.java) 独占三类执行资源：

| 资源 | 用途 |
| --- | --- |
| 单线程 scheduler | 心跳、重连、超时与 LSP 定时任务 |
| virtual-thread-per-task executor | 编码能力的阻塞任务 |
| 平台线程缓存池 | LSP stdio 阻塞 I/O |

构造失败释放已创建资源。`start()` 幂等；断开后指数退避，上限由配置决定，连接成功后重置。每次连接有新代际，旧连接回调不再推进当前状态。注册被拒进入 FAILED、停止重连并使进程非零退出；`RETRY_LATER` 则退避重连。

INVOKE 先严格解码，再以 `journal.start(invocationId)` 原子去重。已有条目直接重放；新调用检查能力标识、版本和 arguments schema，使用 wire 的有效超时，不回落 descriptor 默认值。运行时预检通过才发送 STARTED，然后进入能力执行。STARTED 不保证宿主命令已经启动，能力自己的路径或进程启动预检仍可能失败。

### 超时与取消收尾

有效超时为 0 时不登记 execution deadline。超时与取消分别请求能力按 `TIMED_OUT`、`CANCELLED` 收尾，wire 终态仍为 FAILED、CANCELLED，不因迟到成功结果改成 COMPLETED。副作用已经发生时不回滚。

能力可通过 `terminationGrace()` 声明有界收尾预算。运行时先开启窗口和兜底定时器，再请求收尾；预算内返回的能力文本追加到裁决正文，保留终态类型，便于失败时带回已捕获输出。超大文本不注入；兜底到期或调度器不可用则直接提交裁决。journal 的 RUNNING → terminal 原子跃迁只允许一次终态。

关闭运行时时先关闭连接与传输，取消在途调用并尝试写入 CANCELLED，清空运行集合，再关闭 LSP 等能力资源，最后依次关闭任务、LSP、调度执行器（各最多等待 5 秒）。**不会清空 journal 的已有条目**，但 journal 是内存数据，进程结束后丢失。

### 实例身份与重连恢复

`daemonInstanceId` 在构造时随机生成，同进程所有 HELLO 复用。连接断开只重置绑定、资源预算与等待票据，不改执行事实。相同 invocationId 的重复 INVOKE：RUNNING 重放 `STARTED(replayed=true)`，已终结重放冻结终态，副作用不再执行。

恢复范围是同一 Daemon 进程与仍持有在途目录的 Gateway。Daemon 重启生成新 instanceId，Gateway 将此前在途调用收敛为结果不确定，等待调用层处理；journal 的去重事实随进程生命周期保留。

[`OkHttpWebSocketTransport`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/transport/OkHttpWebSocketTransport.java) 强制握手协商 `permessage-deflate`，缺失以 1010 关闭；只接收文本帧，二进制帧或单条文本超过 16 MiB 字符以 1008 关闭，不退化为另一种传输。

## 本地存储与文本输出

[`TextOutputStore`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/TextOutputStore.java) 把超阈值命令与检索输出先写 `tmp/workspaces/<uuid>/<name>.part`，再同一目录内原子发布为 `*.log`；每次外化登记一个新 workspace。全文是本地 durable 事实，不是内容寻址 Resource；历史可能仍引用绝对路径。定时清扫（默认 30 分钟一次）只回收超过当前保留期（默认 3 天）且未被使用的整个 workspace，历史引用的全文因此在保留期后可能被自动回收；清扫不跟随符号链接、不越受控根、不删除仍持有 in-use lease 的资源。

小输出内联，超过 50 KiB 或 2000 行时返回有界 head/tail、绝对路径、字节与行数，以及 read/grep 指引。预览不截断多字节字符，不重复重叠窗口；CR、LF、CRLF 的行数与文件读取一致。默认捕获预算为 1 GiB，达到预算只停止文件捕获，继续排空与统计；落盘或发布失败不谎报成功，也不把失败改成整段大文本内联，而是退回无路径的有界预览并在 `detailsJson.textOutput.captureFailed` 显式标记、footer 说明无法落盘，命令的退出状态不因此改变。结果明确区分完整捕获、截断与捕获失败。

自然退出、非零退出、超时、取消都先保留可发布输出。收尾说明不写入全文、不计入全文统计；只有自然退出报告 exitCode。`detailsJson.process.outcome` 区分 EXITED / TIMED_OUT / CANCELLED，运行时强制失败或取消的 wire 正文通过前述收尾窗口携带文本。

[`SkillPackageInstaller`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/skill/SkillPackageInstaller.java) 以请求指定的 exact commit 安装，不以 branch HEAD 替代。先拉取 bare cache、物化 staging、检查整棵 tree，只有新包完整物化并校验通过后才原子替换 `skills/<package>`，因此替换期间始终保留已发布技能包，失败则保留旧包或从 backup 恢复；拒绝符号链接、submodule、路径穿越与非普通文件。包内 `.kkstudio-commit` 记录 commit，模型路径稳定为 `<data-dir>/skills/<package>/<skill>/SKILL.md`。origin URL 变化时重建缓存；marker 相同也重新校验物化结果。

## 编码能力

### workdir 与权限

[`EnvironmentPaths`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/EnvironmentPaths.java) 用本次 arguments 定位路径。文件与 LSP 能力的 `path` 必须是绝对路径，相对路径直接拒绝、不接受 `workdir`；只有 process.exec 要求本次调用显式给出绝对、现存、可读的 `workdir`。目录校验失败即拒绝本次调用，每次调用独立解析目录。

workdir **不是沙箱**：目标可在其外，符号链接照常跟随；宿主权限来自运行用户，业务授权由 Platform 判定。READY 的进程用户、时区、OS、HOME、可信 note 与受控临时目录 `tempDirectory` 仅为宿主展示事实，HOME canonical 化失败时退回绝对规范路径，不是执行默认值。

### 进程执行范围

[`ProcessScope`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/ProcessScope.java) 与 [`ProcessScopeHelper`](../../harness/daemon/src/main/java/fun/fengwk/kkstudio/harness/daemon/coding/ProcessScopeHelper.java) 先建立 OS 范围，再经父进程校验和 permit 放行用户命令。helper 冷启动也计入有效超时；放行前取消或失败不会执行用户命令。无法建立范围时明确失败，不退化为无范围直接执行。

- Linux/WSL/macOS：helper 用 JNA `setsid` 建立 session/进程组；普通后代从创建起归组。结束时先温和信号、宽限后强杀，再向内核确认没有活成员；僵尸不算活成员。
- Windows：命名 Job Object 设置 kill-on-close，不开放 breakaway；首进程在创建时进入 Job，父进程另持句柄。只有 Job 的 ActiveProcesses 为 0 才确认范围结束，helper 退出或根进程退出都不能代替此检查。

自然退出也收敛遗留后代，确认后才通知终态；无法确认收敛时报告失败。process.exec 的 stdin 立即 EOF，stdout/stderr 合并捕获；helper 诊断单独保存，不污染命令输出。Bash 用 `-lc` 执行；Windows 应明确配置所需 Bash，避免裸名解析到 WSL launcher。

范围管理不是恶意命令隔离：POSIX 命令主动重新建立 session/进程组可离开边界，不承诺阻止逃逸。LSP 使用同一范围的双向 stdio 模式，stderr 独立，客户端关闭先发送 shutdown/exit，宽限后收敛后代。跨平台测试见 [`ProcessScopeCrossPlatformTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/coding/ProcessScopeCrossPlatformTest.java)，其它命令行为见[内置 Bash 测试映射](../operations/builtin-bash-tests.md)。

### 文件与检索

文件修改保留编码、BOM、行尾，通过进程内分段锁串行化同文件修改。文本读窗口以 1-based 行/列定位，limit 默认及最大 2000，正文预算 60000 Unicode 码点；扫描到 EOF 得到总行数与 ends_with_newline，内存只驻留窗口。超时/中断不返回半个窗口；续读由 next 指向首个未返回字符。支持的图片以二进制结果直传对象存储，设备、FIFO、socket 等特殊节点在 I/O 前拒绝。详细读写契约见[内置 Read 测试映射](../operations/builtin-read-tests.md)与[文件修改测试映射](../operations/builtin-mutation-tests.md)。

grep/find 用 Java NIO 遍历，不依赖外部检索二进制。忽略规则从目标路径的祖先读取，支持分层 `.gitignore` 与 `.git/info/exclude`（含 worktree 的 gitdir/commondir），不依赖调用目录，始终排除 `.git`。单行检索流式扫描，超长行（1 MiB）显式报告无法完整搜索；multiline 整文件视图上限 64 MiB。UTF-8 与 BOM 指明的 UTF-16 严格解码，不把不支持编码静默替换成乱码。失败、限制与忽略行为见[内置检索测试映射](../operations/builtin-search-tests.md)。

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
| `daemon.journal` | 进程内原子去重与冻结终态，不持久化跨进程执行状态 |
| `daemon.skill` | exact commit 的技能包拉取、校验、替换与启动恢复 |
| `daemon.transport` | WebSocket 文本传输、压缩协商与帧边界 |

包名前缀为 `fun.fengwk.kkstudio.harness`。依赖、import 与执行器所有权由 [`DaemonModuleArchitectureTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/DaemonModuleArchitectureTest.java)守卫。启动与凭证测试见 [`DaemonConfigTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/DaemonConfigTest.java)、[`DaemonTokenFileTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/DaemonTokenFileTest.java)；重连、journal、收尾窗口与上传恢复见 [`DaemonRuntimeTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/DaemonRuntimeTest.java)；目录锁与启动清理见 [`DaemonDataDirectoryTest`](../../harness/daemon/src/test/java/fun/fengwk/kkstudio/harness/daemon/DaemonDataDirectoryTest.java)。

上级：[系统设计](../system-design.md)。相关文档：[Harness Environment](harness-environment.md)、[Harness Environment Server](harness-environment-server.md)、[Harness MCP](harness-mcp.md)、[Environment Daemon 安装与运行](../operations/environment-daemon.md)。
