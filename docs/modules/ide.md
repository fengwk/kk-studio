# IDE

IDE 让用户在 Studio 同源下打开某个 Environment 上由 Daemon 托管的 code-server，并把视图定位到受管工作区目录。它是建立在平台之上的应用：[`apps/ide`](../../apps/ide/pom.xml) 持有 IDE 的载荷协议、元数据服务、反向数据流状态机与跨节点转发；内核的 [Harness Environment](harness-environment.md) 只提供一条不解析载荷的 IDE 通道；浏览器入口与 HTTP/WebSocket 数据面由 [Web](web.md#同源-ide-网关与数据面) 映射；Daemon 侧见 [Environment Daemon](daemon.md#受管-ide-与反向数据流)。

模块依赖 [Harness Environment](harness-environment.md)（Daemon 连接与通道）、[Platform](platform.md)（集群节点注册与内部认证）、[Share](share.md)（IDE DTO）与 [Common](common.md)，经 [`IdeAutoConfiguration`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/IdeAutoConfiguration.java) 装配。

## 两条数据路径

```text
元数据命令：Studio API -> EnvironmentIdeService -> IdeCommandTransport -> IDE_COMMAND / IDE_RESULT（Daemon 连接）
数据流：    浏览器 <-> Web 网关 <-> 反向数据流（Daemon 主动连回 Studio）<-> code-server
```

元数据命令打开、切换、聚焦或关闭视图，走既有 Daemon 连接上的 `IDE_COMMAND` / `IDE_RESULT` 消息；HTTP 请求与 WebSocket 字节从不经过这条连接，而是由 `OPEN_STREAM` 命令让 Daemon 主动建立一条反向数据流。字节只在网关与反向流之间流转，绝不写入数据库。

## 命令通道

[`EnvironmentDaemonServer`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServer.java) 的 `requestIde` 只做三件事：沿用 Agent 调用的准入（本节点持有 READY 连接、无更新在途、锁内复核数据库 READY 租约），登记一条在途调用，并把匹配 `invocationId` 的 `IDE_RESULT` 载荷原样交回。IDE 调用不进入 Agent journal，也不重放：命令未进入传输时以 BUSY/UNAVAILABLE 失败，递交过程中传输失败或连接代际关闭时以结果不确定终结，调用方超时后 `abandonIde`，迟到的回执被安静忽略。

载荷属于 IDE 应用：[`DaemonIdeCommandTransport`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/DaemonIdeCommandTransport.java) 用 [`DaemonIdeCodec`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/protocol/DaemonIdeCodec.java) 编码 [`IdeCommand`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/protocol/IdeCommand.java)、解码 [`IdeReply`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/protocol/IdeReply.java)。动作集合 [`IdeAction`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/protocol/IdeAction.java) 为 `OPEN_VIEW`、`CHANGE_DIRECTORY`、`FOCUS_TERMINAL`、`CLOSE_VIEW` 与 `OPEN_STREAM`，与 Daemon 的 [`contracts.ts`](../../daemon/src/contracts.ts) 一致。回执载荷不合法只让这一条 IDE 调用失败，不影响同一连接上的 Agent 能力调用。

每条命令进入传输时冻结代际 [`DaemonConnectionIdentity`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonConnectionIdentity.java)，即 Environment 当前 READY 连接背后的 Daemon 实例。所有 IDE 凭据都绑定这个代际：同一 Daemon 进程断线重连后，它的 code-server 与视图都还在，凭据继续有效；另一个实例在本节点接管时，内核发出 `onDaemonInstanceReplaced`，旧代际的凭据与流一律作废。每个 Environment 在本节点至多保留一个代际。各入口按 [`IdeGenerationState`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/IdeGenerationState.java) 统一裁决：当前实例可以建立新的反向流；暂无 READY 连接时返回 503，等待同一实例重连；已被接管时返回 409，浏览器需要重新打开视图。

## 元数据服务

[`EnvironmentIdeService`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/EnvironmentIdeService.java) 把 Studio API 的视图请求翻译为 IDE 命令。目录能否访问、文档是否 dirty 由 Daemon 给出标准失败回执，服务端不伪造结果。

- `openView` 额外铸造一次性 bootstrap 票据（[`IdeBootstrapTicketService`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/IdeBootstrapTicketService.java)）：票据绑定 Environment、view、受管工作区路径与连接代际，短时、消费即删除；同源网关消费后设置 Path 限定的 HttpOnly cookie，再跳转 code-server 的 `/?workspace=<encoded>`。
- [`IdeViewAuthorizationListener`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/IdeViewAuthorizationListener.java) 让数据面按 Daemon 代际维护受管工作区授权：视图创建即授权、关闭即撤销、代际结束即清空，不用空闲超时之类的时间推断代替真实关闭事件。

## 反向数据流

每个浏览器 HTTP exchange 或 IDE WebSocket 都对应一条反向流：

1. 网关用 [`IdeStreamTicketService`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/stream/IdeStreamTicketService.java) 铸造 256-bit 一次性票据（绑定连接代际与转发模式，不写入日志），并在 [`IdeStreamPairingRegistry`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/stream/IdeStreamPairingRegistry.java) 登记待配对项；
2. `OPEN_STREAM` 把票据与 Studio 受控的 stream URL 交给 Daemon，Daemon 主动连接 `/api/harness/environment-stream/v1/{ticket}` 并消费票据；
3. 配对成功后，[`IdeStreamRelay`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/stream/IdeStreamRelay.java) 按 [`DaemonIdeStreamCodec`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/protocol/DaemonIdeStreamCodec.java) 的帧收发。

流控由双向独立的 [`IdeStreamCreditWindow`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/protocol/IdeStreamCreditWindow.java) 约束：初始 credit 262144，每个数据帧（HTTP body 或 WS 分段）按载荷字节计价但至少 512，零长度分段同样计价。因此一个窗口同时限制在途字节与在途帧数（至多 512 帧），两端的入站队列按 credit 推导出的上界定界，code-server 终端输出、RPC 这类小消息的突发不会被误断开。ACK 只能确认已真实消费的数据帧且至少覆盖一帧；超窗发送、超窗/不足一帧/负数 ACK、未知帧与关闭后的操作一律关闭流，因此没有无界排队，也没有“先 ACK 后缓冲”。

单条 HTTP exchange 只限制等待首个响应以及传输中两次进展之间的空闲（`kk-studio.harness.ide-gateway.request-timeout`），持续有进展的大文件下载不受整体时长限制。Daemon 侧关闭反向流时，网关把它给出的关闭码（code-server 的关闭码或协议原因）原样转述给浏览器，只有缺失或不能发送时才用 1011；跨节点单跳同样转述。每条 IDE WebSocket 结束、以及每条非正常结束的 HTTP exchange，网关都写一行诊断日志：结束原因、Daemon 侧与浏览器侧关闭码、Daemon 对 `OPEN_STREAM` 的失败回执、双向帧数与字节数和时长，不含票据、cookie 与查询串；Daemon 在宿主日志写一行 `daemon ide <mode> stream failed: <原因>`。由此可以判定每次断流的关闭方。配对等待超时会关闭可能的 peer 并放弃在途 `OPEN_STREAM`；控制连接短暂断开不影响已建立的流，同一实例回拨的反向流照常配对；另一个实例接管时按代际关闭全部待配对与已配对的流，迟到的反向连接因票据已撤销或代际已被接管而被拒绝。

## 跨节点

多节点部署时，Environment 的 Daemon 连接只在一个 owner 节点上。[`ClusterEnvironmentIdeService`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/cluster/ClusterEnvironmentIdeService.java) 按平台集群注册表的 owner 快照路由：本节点是 owner 时直接调用本地服务；否则经 [`IdeClusterClient`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/cluster/IdeClusterClient.java) 用平台的 HMAC 节点认证单跳转发到 owner 的内部 REST，失败即不可用，绝不自动重放。没有 READY 租约、注册过期或本节点缺少内部配置时，请求在任何 Daemon 副作用之前明确失败。内部路径契约集中在 [`IdeInternalPaths`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/cluster/IdeInternalPaths.java)，元数据请求与回执体上限 16 KiB；浏览器数据面的跨节点转发由 Web 网关使用同一套路径与认证。

## 从哪里改

- 新增 IDE 动作：同时修改 [`IdeCommand`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/protocol/IdeCommand.java)、[`DaemonIdeCodec`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/protocol/DaemonIdeCodec.java) 与 Daemon 的 [`contracts.ts`](../../daemon/src/contracts.ts)；协议变化属于 Daemon 发布，见 [Environment Daemon](../operations/environment-daemon.md)。
- 视图语义：[`EnvironmentIdeServiceImpl.java`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/EnvironmentIdeServiceImpl.java)；跨节点：[`cluster`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/cluster/) 包。
- 测试入口：[`EnvironmentIdeServiceImplTest.java`](../../apps/ide/src/test/java/fun/fengwk/kkstudio/ide/EnvironmentIdeServiceImplTest.java)、[`DaemonIdeCodecTest.java`](../../apps/ide/src/test/java/fun/fengwk/kkstudio/ide/protocol/DaemonIdeCodecTest.java)、[`IdeStreamRelayTest.java`](../../apps/ide/src/test/java/fun/fengwk/kkstudio/ide/stream/IdeStreamRelayTest.java) 与 [`ClusterEnvironmentIdeServiceTest.java`](../../apps/ide/src/test/java/fun/fengwk/kkstudio/ide/cluster/ClusterEnvironmentIdeServiceTest.java)；内核通道的准入与收尾由 [`EnvironmentDaemonServerIdeTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServerIdeTest.java) 覆盖，同源网关的端到端行为见 [Web](web.md)。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Environment](harness-environment.md)、[Environment Daemon](daemon.md)、[Web](web.md)、[Platform](platform.md)。
