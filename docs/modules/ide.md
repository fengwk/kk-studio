# IDE

IDE 让用户在 Studio 同源下打开某个 Environment 上由 Daemon 托管的 code-server，并把视图定位到受管工作区目录。它是建立在平台之上的应用：[`apps/ide`](../../apps/ide/pom.xml) 持有 IDE 的载荷协议、元数据服务、复用数据连接状态机与跨节点转发；内核的 [Harness Environment](harness-environment.md) 只提供一条不解析载荷的 IDE 通道；浏览器入口与 HTTP/WebSocket 数据面由 [Web](web.md#同源-ide-网关与数据面) 映射；Daemon 侧见 [Environment Daemon](daemon.md#受管-ide-与复用数据连接)。

模块依赖 [Harness Environment](harness-environment.md)（Daemon 连接与通道）、[Platform](platform.md)（集群节点注册与内部认证）、[Share](share.md)（IDE DTO）与 [Common](common.md)，经 [`IdeAutoConfiguration`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/IdeAutoConfiguration.java) 装配。

## 控制面与数据面

```text
元数据命令：Studio API -> EnvironmentIdeService -> IdeCommandTransport -> IDE_COMMAND / IDE_RESULT（Daemon 控制连接）
使用证明：  Server -> IDE_LEASE（随心跳轮次批量下发的浏览器持有列表）
数据面：    浏览器 <-> Web 网关 <-> 复用 mux 物理 WS（DATA_BIND）<-> code-server
```

元数据命令打开、切换目录、聚焦终端、分离或关闭视图，走既有 Daemon 连接上的 `IDE_COMMAND` / `IDE_RESULT` 消息；HTTP 请求与 WebSocket 字节从不经过这条连接。首次有逻辑流需求时，控制通道下发 `DATA_BIND`，Daemon 随即建立一条带 30 秒一次性票据的复用数据 WebSocket。该连接绑定当前 READY 控制身份与代际，同一 Environment 只保留一条，物理连接内承载多条 HTTP/WS 逻辑流。字节只在网关、mux 流与 code-server 之间流转，绝不写入数据库。

## 命令通道

[`EnvironmentDaemonServer`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServer.java) 的 `requestIde` 只做三件事：沿用 Agent 调用的准入（本节点持有 READY 连接、无更新在途、锁内复核数据库 READY 租约），登记一条在途调用，并把匹配 `invocationId` 的 `IDE_RESULT` 载荷原样交回。IDE 调用不进入 Agent journal，也不重放：命令未进入传输时以 BUSY/UNAVAILABLE 失败，递交过程中传输失败或连接代际关闭时以结果不确定终结，调用方超时后 `abandonIde`，迟到的回执被安静忽略。

载荷属于 IDE 应用：[`DaemonIdeCommandTransport`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/DaemonIdeCommandTransport.java) 用 [`DaemonIdeCodec`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/protocol/DaemonIdeCodec.java) 编码 [`IdeCommand`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/protocol/IdeCommand.java)、解码 [`IdeReply`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/protocol/IdeReply.java)。控制命令只承载视图的创建、恢复、切换目录、聚焦终端、分离与关闭等元数据；数据流不再由控制命令按 exchange 请求。回执载荷不合法只让这一条 IDE 调用失败，不影响同一连接上的 Agent 能力调用。

命令与回执都是严格形状，字段多余或缺失都是协议错误。下表成功回执均包含 `ok:true`：

| action | 请求字段 | 成功回执 |
| --- | --- | --- |
| `OPEN_VIEW` | `viewId`、`documentId`、`eventNodeId`、`connectionId`、`claimId`、`mode(create/resume)`、可选 `directory` | `viewId`、`documentId`、`eventNodeId`、`connectionId`、`claimId`、`bindingEpoch`、`directory`、`workspacePath` |
| `CHANGE_DIRECTORY` | `viewId`、`documentId`、`bindingEpoch`、`directory` | `viewId`、Daemon 确认的规范 `directory` |
| `FOCUS_TERMINAL` | `viewId`、`documentId`、`bindingEpoch` | `viewId` |
| `CLOSE_VIEW` | `viewId`、`documentId`、`bindingEpoch`、`discard` | `viewId` |
| `DETACH_VIEW` | `viewId`、`documentId`、`bindingEpoch` | `viewId` |
| `LIST_VIEWS` | 无 | `views[]`：`viewId`、`documentId`、`eventNodeId`、`connectionId`、`claimId`、`bindingEpoch`、`directory`、`workspacePath`、`state(OPEN/CLOSING)` |

失败回执固定为 `{ok:false, code, message}`：`code` 是安全大写 token（常用 `VIEW_GONE`、`VIEW_IN_USE`、`DIRTY`、`CLEANUP_FAILED`、`BUSY`、`UNAVAILABLE`），`message` 去敏且不超过 500 字符。UUID 一律规范小写，`bindingEpoch` 是规范正十进制字符串且不超过 Java long 上限；`directory` 与 `workspacePath` 只接受 Daemon 确认的值，客户端输入永不进入摘要。视图的 `directory` 与 `workspacePath` 由 Daemon 判定，Server 不伪造。

Server 只用 `CHANGE_DIRECTORY` 的确认回执更新缓存和 DTO；缺失或无效的确认目录使调用不可用，不回退到客户端请求，也不自动重试。

每条命令进入传输时冻结代际 [`DaemonConnectionIdentity`](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/server/DaemonConnectionIdentity.java)，即 Environment 当前 READY 连接背后的 Daemon 实例。所有 IDE 凭据都绑定这个代际：同一 Daemon 进程断线重连后，它的 code-server 与视图都还在，凭据继续有效；另一个实例在本节点接管时，内核发出 `onDaemonInstanceReplaced`，旧代际的凭据与流一律作废。每个 Environment 在本节点至多保留一个代际。各入口按 [`IdeGenerationState`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/IdeGenerationState.java) 统一裁决：当前实例可以建立新的数据逻辑流；暂无 READY 连接时返回 503，等待同一实例重连；已被接管时返回 409，浏览器需要重新打开视图。

## 元数据服务

[`EnvironmentIdeService`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/EnvironmentIdeService.java) 把 Studio API 的视图请求翻译为 IDE 命令。目录能否访问、文档是否 dirty 由 Daemon 给出标准失败回执，服务端不伪造结果。

- `openView` 先向事件节点核验 exact intent：只有该物理连接与 document 仍存活、且登记了完全相同的 environment/view/claim 意图时才发命令（[`IdeBrowserUseGateway`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/IdeBrowserUseGateway.java)）；事件节点不可达时不建立新绑定。`claimId` 在一次请求未确认时保持不变，同 claimId 的同形请求返回同一绑定，参数漂移被拒绝。
- 绑定身份缓存由 [`IdeViewBindingRegistry`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/IdeViewBindingRegistry.java) 持有：缓存缺失或漂移时先用 `LIST_VIEWS` 重建再判定，旧 epoch 的关闭、目录与终端请求不作用于新绑定。
- `openView` 额外铸造一次性 bootstrap 票据（[`IdeBootstrapTicketService`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/IdeBootstrapTicketService.java)）：票据绑定 Environment、Daemon 实例、viewId、bindingEpoch 与受管工作区路径，短时、消费即删除；同源网关消费后设置 Path 限定的 HttpOnly cookie，再跳转 code-server 的 `/?workspace=<encoded>`。
- [`IdeViewAuthorizationListener`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/IdeViewAuthorizationListener.java) 让数据面按 Daemon 代际维护受管工作区授权：视图创建即授权、关闭即撤销、代际结束即清空，不用空闲超时之类的时间推断代替真实关闭事件。
- HTTP 与 WS 网关必需装配 `SessionIdeDataViewAuthorizer`：只从配置名匹配的 IDE cookie 查询真实 `IdeBrowserSessionRegistry`，使用会话中确认的实例、epoch 与 workspace。关闭、分离、重绑、过期与实例替换使入口失效；同实例暂断保留会话，由数据注册表返回不可用，不新增授权或续租。

## 使用证明

使用证明由 [`IdeLeaseCoordinator`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/IdeLeaseCoordinator.java) 在心跳轮次上驱动：Daemon 的心跳带 `ideLeaseRound`，owner 先用 `LIST_VIEWS` 读回当前绑定，再按 eventNode/connection/document 分组向事件节点查询已确认的持有列表，只有 environment/view/document/epoch 完全匹配的条目才写进 `IDE_LEASE`。心跳本身不续租，查询失败不写证明，也不重放旧轮次；同一 Environment 同一时刻只有一轮在途。

## 复用数据面

每个 Environment 的数据面由 [`IdeDataConnectionRegistry`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/data/IdeDataConnectionRegistry.java)
持有唯一物理连接。注册表在首条逻辑流需要时校验当前 READY 控制身份与 owner lease，生成一次性 256-bit 票据并通过 `DATA_BIND`
下发 generation、ticket 与受控绝对 `streamUrl`；票据 30 秒有效、消费即删除。Daemon 连接：

```text
GET /api/harness/environment-data/v2/{environmentId}?ticket=<opaque>
```

接受回连时，注册表再次核对票据绑定身份、当前 READY lease 与数据代际，然后建立
[`MuxConnection`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/mux/MuxConnection.java)。迟到的旧代际 ticket、
已消费 ticket、租约失效或控制身份替换都 fail-closed；旧连接被关闭，新逻辑流必须走新票据，不自动重连或重放。

逻辑流 OPEN 携带 `viewId`、正的 `bindingEpoch`、模式、方法、浏览器 view 基址之后的原始相对 `path`（含 query）和已过滤
headers。Server 只校验该相对路径不越出 view 作用域；Daemon 校验 view/epoch 后自行构造内部基址
`/__kk/ide/view/{viewId}/{bindingEpoch}` 并访问私有 code-server，响应中的内部挂载 `Location` 由 Web 适配层映射回浏览器 view 基址。
`Service-Worker-Allowed` 同样在浏览器出口映射：当前 view 的内部挂载与合法相对范围保留其作用域，根路径、其他 view 或非法范围收窄到当前浏览器 view 根；直连与跨节点执行相同规则。
[`IdeBrowserDataStream`](../../web/src/main/java/fun/fengwk/kkstudio/web/environment/ide/IdeBrowserDataStream.java)
保持浏览器 TEXT/BINARY/FIN 分段边界，文本跨分段按 UTF-8 流式解码；WebSocket 关闭码映射回逻辑流 CLOSE。
[`IdeGatewayDataPlane`](../../web/src/main/java/fun/fengwk/kkstudio/web/environment/ide/IdeGatewayDataPlane.java)
在同一 mux 流上发送 HTTP 请求体与 END，并把响应 DATA 真正写入浏览器输出后才归还 credit。收到响应 END 后先 flush 浏览器输出，
flush 成功才发送 COMPLETE；任何写入、超时或协议错误都 RESET 该流，不重试、不重放。

mux 为每条流和整条连接分别维护有界 credit 与有界队列：每流每方向初始 credit 256 KiB，整连接每方向初始 credit 4 MiB，
并发逻辑流上限 64 条，管理帧队列上限 256 帧/256 KiB。管理帧和 DATA 由唯一物理写入者按顺序发送；第一次物理写失败立即终止整条
连接，所有等待中的 future 失败，已排队但未写出的请求不会改投其它节点。入站 DATA 只有下游实际消费后才 ACK，避免无界排队。
数据 wire 版本为 2（[`MuxLimits`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/mux/MuxLimits.java)）：12 字节头
`version | type | flags | streamId | payloadLength`，帧类型固定为 OPEN/READY/HEADERS/DATA/WINDOW/END/COMPLETE/RESET/CLOSE，
未知版本或长度不符使整条连接失败。

## 跨节点

多节点部署时，Environment 的 Daemon 连接只在一个 owner 节点上。元数据请求由
[`ClusterEnvironmentIdeService`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/cluster/ClusterEnvironmentIdeService.java)
按 owner 快照路由；浏览器 HTTP 与 WebSocket 数据面由 Web 网关经
[`IdeRemoteDataPlaneForwarder`](../../web/src/main/java/fun/fengwk/kkstudio/web/environment/ide/IdeRemoteDataPlaneForwarder.java)
和 [`IdeRemoteBrowserDataBridge`](../../web/src/main/java/fun/fengwk/kkstudio/web/environment/ide/IdeRemoteBrowserDataBridge.java)
单跳转发到 owner 的 `data-http` / `data-ws` 内部入口，不依赖粘性会话。物理数据 WS 若落在非 owner，则由
[`IdePhysicalDataBridge`](../../web/src/main/java/fun/fengwk/kkstudio/web/environment/ide/IdePhysicalDataBridge.java)
把整条连接桥到 owner 的 `/internal/environment-ide/{environmentId}/data`，owner 仍是唯一票据消费者。所有内部路径都参与
HMAC 签名并核对本节点 owner 归属；没有 READY 租约、路由为空或认证失败时立即失败，不改投其它节点、不自动重放。

## 从哪里改

- 新增 IDE 动作：同时修改 [`IdeCommand`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/protocol/IdeCommand.java)、[`DaemonIdeCodec`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/protocol/DaemonIdeCodec.java) 与 Daemon 的 [`contracts.ts`](../../daemon/src/contracts.ts)；协议变化属于 Daemon 发布，见 [Environment Daemon](../operations/environment-daemon.md)。
- 视图语义：[`EnvironmentIdeServiceImpl.java`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/EnvironmentIdeServiceImpl.java)；跨节点：[`cluster`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/cluster/) 包。
- 测试入口：[`EnvironmentIdeServiceImplTest.java`](../../apps/ide/src/test/java/fun/fengwk/kkstudio/ide/EnvironmentIdeServiceImplTest.java) 覆盖 claim、绑定漂移与回执判定；[`IdeLeaseCoordinatorTest.java`](../../apps/ide/src/test/java/fun/fengwk/kkstudio/ide/IdeLeaseCoordinatorTest.java) 覆盖按事件节点核对后的证明批次；[`IdeDataConnectionRegistryTest.java`](../../apps/ide/src/test/java/fun/fengwk/kkstudio/ide/data/IdeDataConnectionRegistryTest.java) 覆盖票据、代际、租约与授权身份；[`MuxWireCodecTest.java`](../../apps/ide/src/test/java/fun/fengwk/kkstudio/ide/mux/MuxWireCodecTest.java) 与 [`MuxConnectionTest.java`](../../apps/ide/src/test/java/fun/fengwk/kkstudio/ide/mux/MuxConnectionTest.java) 覆盖 wire v2 帧与流状态机；[`IdeDataPlaneIntegrationTest.java`](../../web/src/test/java/fun/fengwk/kkstudio/web/environment/ide/IdeDataPlaneIntegrationTest.java) 通过真实 Tomcat HTTP/WS 与 mux peer 覆盖本地及非 owner 单跳。内核通道的准入与收尾由 [`EnvironmentDaemonServerIdeTest.java`](../../harness/environment/src/test/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServerIdeTest.java) 覆盖，同源网关的端到端行为见 [Web](web.md)。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Environment](harness-environment.md)、[Environment Daemon](daemon.md)、[Web](web.md)、[Platform](platform.md)。
