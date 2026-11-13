# IDE

IDE 让用户在 Studio 同源下打开某个 Environment 上由 Daemon 托管的 code-server，并把视图定位到受管工作区目录。它是建立在平台之上的应用：[`apps/ide`](../../apps/ide/pom.xml) 持有 HTTP 入口、DTO、载荷协议、权威快照与跨节点转发；[Platform](platform.md) 的 Daemon 连接服务提供一条不解析载荷的 IDE 通道；[Web](web.md#同源-ide-网关) 组合应用；Daemon 侧见 [Environment Daemon](daemon.md)。

模块依赖 [Harness Environment](harness-environment.md)（能力契约）、[Platform](platform.md)（Daemon 连接与通道、集群节点注册与内部认证）与 [Common](common.md)，经 [`IdeAutoConfiguration`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/IdeAutoConfiguration.java) 装配。IDE 控制消息属于 Daemon 控制协议 v4，发行版本与 Daemon 一致。

## 身份与作用域

| 身份 | 生命周期与作用 |
| --- | --- |
| `browserSessionId` | 一个浏览器 tab 的 Chat IDE 会话；存 sessionStorage，刷新不变，复制 tab 时隔离。它不是绕过认证的授权凭据。 |
| `eventNodeId` / `connectionId` | 当前 app-events WS；重连或刷新会变，用于证明当前页面连接的使用意图。 |
| `viewId` | 同一 Daemon 进程内的 workspace 资源；刷新复用，不因路由切换重建。 |
| `claimId` | 一次冻结的绑定操作，用于幂等与结果对账，不承担会话所有权。 |
| `bindingEpoch` | Daemon 已提交的绑定代次；新绑定废止旧网络流、旧命令与旧凭据。 |
| Environment + `daemonInstanceId` | 整个授权代际；进程变化时旧身份全部不可复用。 |

所有控制请求都先验证当前连接已 ACK 的使用意图，再核对 view 的 owner、Daemon 实例与绑定代次。同一 `browserSessionId` 的新连接可以立即绑定到当前权威快照，不等待旧连接关闭；旧连接迟到的 proof、注销或关闭只按原 connection/epoch 处理，不能续租或撤销新绑定。旧 claim 或旧 epoch 的迟到结果也只能作用于精确旧代次，不能关闭、改目录或覆盖新绑定。

## 控制面

```text
元数据命令：Studio API -> EnvironmentIdeService -> IdeCommandTransport -> IDE_COMMAND / IDE_RESULT
使用证明：  Server -> IDE_LEASE（随心跳轮次批量下发的浏览器持有列表）
```

[`EnvironmentDaemonServer`](../../platform/daemon-server/src/main/java/fun/fengwk/kkstudio/platform/daemon/server/EnvironmentDaemonServer.java) 的 `requestIde` 沿用 Agent 调用准入（本节点持有 READY 连接、无更新在途、锁内复核数据库 READY 租约），登记一条在途调用，并把匹配 `invocationId` 的 `IDE_RESULT` 载荷原样交回。IDE 调用不进入 Agent journal，也不重放：确认未进入传输时返回 `DAEMON_NOT_READY`；递交过程中传输失败或连接代际关闭时以 `IDE_OUTCOME_UNKNOWN` 终结，调用方超时后 `abandonIde`，迟到回执不再改变权威快照。

[`IdeCommandTransport`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/IdeCommandTransport.java) 使用 Protobuf 生成的 [`IdeCommand`](../../platform/daemon-server/src/main/protocol/control.proto) 与 [`IdeResult`](../../platform/daemon-server/src/main/protocol/control.proto)。命令与回执都是严格形状，多余或缺失字段都是协议错误。下表成功回执均包含 `ok:true`，OPEN/CHANGE_DIRECTORY/CLOSE/LIST_VIEWS 还携带同一提交点冻结的 `revision` 与 `views`：

| action | 请求字段 | 成功回执 |
| --- | --- | --- |
| `OPEN_VIEW` | `viewId`、`browserSessionId`、`eventNodeId`、`connectionId`、`claimId`、`mode(CREATE/RESUME)`、`expectedBindingEpoch`、`expectedDaemonInstanceId`、可选 `directory` | `viewId`、`browserSessionId`、`eventNodeId`、`connectionId`、`claimId`、`bindingEpoch`、`directory`、`workspacePath`、`revision`、`views` |
| `CHANGE_DIRECTORY` | `viewId`、`browserSessionId`、`bindingEpoch`、`expectedDaemonInstanceId`、`directory` | `viewId`、Daemon 确认的规范 `directory`、`revision`、`views` |
| `FOCUS_TERMINAL` | `viewId`、`browserSessionId`、`bindingEpoch`、`expectedDaemonInstanceId` | `viewId` |
| `COLLAPSE_MOBILE` | `viewId`、`browserSessionId`、`bindingEpoch`、`expectedDaemonInstanceId` | `viewId` |
| `CLOSE_VIEW` | `viewId`、`browserSessionId`、`bindingEpoch`、`expectedDaemonInstanceId`、`discard` | `viewId`、`revision`、`views` |
| `LIST_VIEWS` | 无 | `revision`、`views[]`：完整权威视图快照 |
| `LIST_DIRECTORIES` | 可选 `directory`，省略时为宿主主目录 | `directoryListing`：规范 `path`、可选 `parentPath`、`children[]`、`truncated` |

失败回执只携带安全大写 `code` 与去敏 `message`，不把不同原因压成同一个错误。UUID 一律规范小写；Protobuf 的 `bindingEpoch` 是正 int64，`revision` 是非负 int64，HTTP 中均使用规范十进制字符串。`directory` 与 `workspacePath` 只接受 Daemon 确认的值，客户端输入永不进入摘要。

`OPEN_VIEW` 是唯一安装/恢复入口，内部明确分成“确保 view 资源”和“绑定当前连接”：create 必须携带 `expectedBindingEpoch=0`，resume 必须携带最后确认的正 epoch；两者都核对 `expectedDaemonInstanceId`。同一已提交 claim 的完全相同请求返回同一绑定且不分配新 epoch，参数漂移为 `CLAIM_CONFLICT`；同一 owner 以不同 claim 对已有 view 再次 create 返回 `STALE_BINDING`，不会创建第二个 workspace。resume 不隐式创建视图、不改目录，也不占用新 view 容量；不同 `browserSessionId` 不得借租约到期接管。每个 Daemon 进程最多 64 个视图，创建预留与 CLOSING 同样占用容量。

Server 只用 `CHANGE_DIRECTORY` 的确认回执与同一提交点的完整快照确定目录和绑定；缺失或不完整的快照使调用不可用，
不回退到客户端请求，也不自动重试。回执中的绑定若已被更新快照取代，只返回 `STALE_BINDING`，不重新发布旧授权。

## 快照与 revision

Daemon 只维护一个 IDE revision 序列。create/resume 在原生注册前从同一序列提前分配候选 `bindingEpoch`；创建、绑定、目录变更、关闭、进入 CLOSING 与到期删除等可见状态提交时再推进 revision。允许间隙，因此始终 `bindingEpoch <= revision`。revision 与 epoch 都限定在同一 Daemon 进程内。

OPEN/CHANGE_DIRECTORY/CLOSE/LIST_VIEWS 的可见状态回执提供 `{revision, views}`；错误若已改变 CLOSING 或其它可见状态，也携带完整的提交后快照。快照同步冻结当前已提交视图，不等待其它 view 的在途绑定，不把执行前旧列表包装成新 revision。`views` 按 `viewId` 排序，最多 64 项，不包含票据、用户文件内容或心跳时间；相同 revision 必须对应相同内容，否则是协议错误。reservation 不在快照中，一个 view 的绑定 CAS 也不阻塞对其它 view 的同步读取。

[`IdeViewSnapshotStore`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/IdeViewSnapshotStore.java) 是 Backend 唯一接受的权威快照缓存：同一 Daemon 进程只接受单调递增 revision，更大 revision 原子替换，更小 revision 不生效，相同 revision 内容漂移为 `IDE_PROTOCOL_ERROR`。新 Daemon 进程使用新的缓存代际，不跨 `daemonInstanceId` 比较 revision；迟到旧快照中的 absent view 不能删除新绑定。网关授权、绑定查询、容量与 bootstrap 校验都读取同一个已接受快照；[`IdeBrowserSessionRegistry`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/gateway/IdeBrowserSessionRegistry.java) 只管理 cookie，[`IdeBootstrapTicketService`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/IdeBootstrapTicketService.java) 只管理一次性票据。票据与 cookie 按完整 `(Daemon 身份, viewId, bindingEpoch)` 处理，旧 epoch 的回滚、清理或迟到回执不能撤销新 cookie/票据。

## 管理 API

| 接口 | 请求 / 结果 |
| --- | --- |
| POST `/api/ide/environments/{id}/views` | `viewId`、`browserSessionId`、`eventNodeId`、`connectionId`、`claimId`、`mode(create/resume)`、`expectedBindingEpoch`、`expectedDaemonInstanceId`、可选 `directory`；claim 成功返回绑定身份、目录、workspacePath、短期 entryUrl |
| PUT `/api/ide/environments/{id}/views/{viewId}/directory` | `browserSessionId`、`bindingEpoch`、`expectedDaemonInstanceId`、目标目录；检查 dirty，成功才写工作区 |
| POST `/api/ide/environments/{id}/views/{viewId}/focus-terminal` | `browserSessionId`、`bindingEpoch`、`expectedDaemonInstanceId`；聚焦/创建该视图终端 |
| POST `/api/ide/environments/{id}/views/{viewId}/apply-mobile-layout` | `browserSessionId`、`bindingEpoch`、`expectedDaemonInstanceId`；收起原生侧栏和面板，不更改用户设置 |
| GET `/api/ide/environments/{id}/directories?path=<absolute>` | 省略 path 时使用宿主主目录；返回 `{path,parentPath,children:[{name,path}],truncated}`，根目录的 `parentPath` 为 null |
| GET `/api/ide/environments/{id}/views/{viewId}` | 显式 query：`browserSessionId`、`eventNodeId`、`connectionId`、`claimId`、可选 `expectedDaemonInstanceId`；直接返回 `{daemonInstanceId,revision,view?}`，`view` 为 null 是当前快照的有效 absent 事实 |
| POST `/api/ide/environments/{id}/views/{viewId}/close` | `browserSessionId`、`bindingEpoch`、`expectedDaemonInstanceId` 与显式 `discard`；正常关闭等待原生保存/不保存/取消，状态未知或不可达时才提供经确认的强制关闭 |
| 应用事件 WS `ide-use` | `browserSessionId`、同一连接内 usage 序号 `revision`、`views[]`；空列表分离全部，不表示确认销毁 |
| GET `/api/platform/environments/{id}/runtime` | owner 返回当前 `{revision,status}`；无本代 READY 快照时返回 unavailable |

`GET /views/{viewId}` 是纯读：控制器的 query 参数显式声明，ReadDTO 只作为 Java 内部参数，不生成第二份前端字段接口。读取先要求当前 WS usage 已 ACK，只返回该 `browserSessionId` 的资源与绑定；不创建 view、不续租、不生成票据、不执行终端操作。view 不存在时返回当前快照的 absent 事实，而不是命令未执行的证明；如果其它 owner 持有该 view，返回 `VIEW_OWNED_BY_OTHER_SESSION`。初始 create 可先通过该读取发现当前 `daemonInstanceId`，之后在持久 pending 中冻结此代次；其它恢复必须带 expected 实例。

目录查询只列直接真实子目录，不跟随子目录符号链接；输入目录自身按宿主 `realpath` 规范化。结果按名称排序，最多 1000 项，响应字节达到上限也会截断并设置 `truncated`。不可访问或不存在的目录返回错误，不返回伪造的空列表。HTTP 中 64 位整数在请求与响应中使用规范十进制字符串；元数据业务输入错误由产生处抛出 `DomainException`，由宿主统一映射为 problem 响应。

正常关闭由桥接调用工作台 `vscode.window.tabGroups.close`，等待原生保存/不保存/取消，不设命令超时；取消以 `validation` / `IDE_CLOSE_CANCELLED` 返回，视图保持打开，也不触发外层放弃确认。控制连接中断时结果未知，不回放副作用或采用迟到结果；用户可在状态未知时显式确认强制关闭。

## 结构化失败与结果对账

IDE 边界保留精确原因，所有本地/Daemon 错误都按已知 code 映射，不用 message 或 detail 正则猜测执行阶段：

| reason | 表达的事实 | 客户端行为 |
| --- | --- | --- |
| `DAEMON_NOT_READY` | 本次命令确认未发送或当前无 READY 代次 | 等 READY 后按原意图恢复，不伪造已执行 |
| `VIEW_GONE` | 本代次资源不存在，或 Daemon 已换代 | 保留目录线索，明确提示重新打开，不用旧 epoch/token 复活 |
| `VIEW_OWNED_BY_OTHER_SESSION` | 不是当前 browserSession 的 view | 不抢占，用户可显式打开独立 view |
| `STALE_BINDING` | expected epoch、claim、实例或回执已过期 | 先纯读权威状态再对账 |
| `CLAIM_CONFLICT` | 同 claim 被用于不同操作 | 协议错误，不能自动重试 |
| `IDE_CAPACITY_EXCEEDED` | 创建容量不足 | 管理已有 view；已有 view 的 resume 不受此分支影响 |
| `IDE_OUTCOME_UNKNOWN` | 命令可能已执行 | 纯读对账，不创建新 view，不重放终端/目录副作用 |
| `IDE_CLEANUP_FAILED` | 资源清理未完成 | 保留 CLOSING 与容量，等待显式重试或人工处理 |
| `IDE_PROTOCOL_ERROR` | 回执或快照破坏协议不变量 | 失败本调用并保留上一个已接受快照，不自行修补 |
| `IDE_CLOSE_CANCELLED` | 用户在 IDE 原生关闭中选择取消 | 视图保持原绑定，不触发外层放弃确认 |

继续使用 Problem+JSON 与统一 `DomainException` HTTP 映射，不借 IDE 改全仓错误体系。409 本身不决定重试策略，reason 与确定的执行阶段才决定；网络断线或 5xx 只表示结果可能未知。

结果未知时先纯读该 view 与当前 `daemonInstanceId`，按以下顺序处理：

1. 已提交本 claim：采用已确认结果，再从权威快照取得当前绑定对应的入口。
2. 已有同 owner view，但连接或 epoch 已变化：用权威 epoch 发起新的绑定 CAS。
3. 尚无 view：只按原 viewId、owner 与目录恢复幂等 create，不随机创建替代 view。
4. 同一请求真正被更新绑定取代：明确 `STALE_BINDING`，先读事实再决定，不用定时器循环重试。
5. Daemon 实例已改变：进入 `VIEW_GONE`，不用旧 epoch/token 自动复活运行身份。

连接内只允许一个在途绑定操作。旧响应必须经过 claim、连接、实例与 epoch 复核，不能触发目录切换、终端新建等副作用。任何替代视图都必须由用户显式打开。

## 浏览器会话与数据面

iframe 使用同源视图作用域路径 `/ide/{environmentId}/{viewId}/...`。bootstrap 入口是 `/ide/{environmentId}/{viewId}/__ide/bootstrap?token=...`，消费一次性票据后设置 Path 限定为该 view 基址的 HttpOnly/SameSite=Strict cookie（可信 TLS 下加 Secure），再跳转到 code-server 的 `/?workspace=<encoded>`；同源网关另有 `__ide/session` 只报告当前 cookie 与代际是否仍可进入数据面，不续租、不重建视图。路径段与 query 值统一经 `common.uri.UrlEncoding` 做 RFC 3986 百分号编码，空格是 `%20`，不使用表单语义的 `+`。HTTP、WS upgrade 和逻辑流 OPEN 均复核当前视图绑定；关闭或换代后旧 cookie 失效。

- `openView` 先向事件节点核验 exact intent：只有该物理连接、browserSession 与 view/claim 意图仍存活且匹配时才发命令（[`IdeBrowserUseGateway`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/IdeBrowserUseGateway.java)）；事件节点不可达或当前 usage 未 ACK 时不建立新绑定。`claimId` 在单次请求未确认期间保持不变，同 claim 的同形请求返回同一绑定，参数漂移被拒绝。
- `openView` 成功后才用权威快照铸造一次性 bootstrap 票据；票据绑定 Environment、Daemon 实例、viewId、bindingEpoch 与受管工作区路径，短时、消费即删除。`GET` 不生成 entryUrl，也不发票据。
- 数据面授权由 [`SessionIdeDataViewAuthorizer`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/gateway/SessionIdeDataViewAuthorizer.java) 完成：从配置名匹配的 IDE cookie 查询真实会话，再要求当前 Daemon 进程、OPEN 状态、bindingEpoch 与 workspace 都和唯一权威快照一致。关闭、重绑、过期与实例替换使入口失效；同实例暂断保留 cookie，但不新增授权或续租。
- 数据流以 `/ide/{viewId}/{bindingEpoch}/...` 进入 Daemon，校验当前 OPEN 绑定后转发到 code-server 的私有路径；绑定 CAS 在途时新流与使用证明不 attach，resume 成功后旧流被中止。

每个 browserSession 使用浏览器 Web Locks 做同源独占：刷新复用会话；复制 tab 发现会话已被活页面持有就生成新会话并清空复制来的运行身份，只保留非运行态展示偏好。pagehide 只撤回或暂停使用证明，不删除视图；BFCache pageshow 重新确认锁与权威绑定。Web Locks 不可用时明确报告浏览器能力不足，不退回共享身份或基于本地时间的接管。

使用证明由 [`IdeLeaseCoordinator`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/IdeLeaseCoordinator.java) 在心跳轮次上驱动：Daemon 的心跳带 `ideLeaseRound`，owner 先用 `LIST_VIEWS` 读取当前权威快照，再按 eventNode/connection/browserSession 分组向事件节点查询已确认的持有列表，只有 environment/view/browserSession/epoch 完全匹配的条目才写进 `IDE_LEASE`。心跳本身不续租，查询失败不写证明，也不重放旧轮次；同一 Environment 同一时刻只有一轮在途。有效证明按该轮发出时刻计算 600 秒到期；到期时只有桥接可证明编辑器干净且原生关闭成功才回收视图，dirty 或状态未知时保留并继续占用容量。

## 跨节点

多节点部署时，Environment 的 Daemon 连接只在一个 owner 节点上。元数据请求、快照读取与使用意图查询由 [`ClusterEnvironmentIdeService`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/cluster/ClusterEnvironmentIdeService.java) 按 owner 快照路由。所有内部路径都参与 HMAC 签名并核对本节点 owner 归属；没有 READY 租约、路由为空或认证失败时立即失败，不改投其它节点、不自动重放。

[`ClusterEnvironmentIdeInternalController`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/http/ClusterEnvironmentIdeInternalController.java)、
[`ClusterEnvironmentIdeInternalDataPlaneController`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/http/ClusterEnvironmentIdeInternalDataPlaneController.java)
与 [`IdeBrowserUseInternalController`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/http/IdeBrowserUseInternalController.java)
的成功响应是裸 DTO，错误响应与浏览器 API 使用同一 problem+json 格式。认证失败固定 401 `unauthorized`，请求体超限为 413 `validation`。[`IdeClusterClient`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/cluster/IdeClusterClient.java) 按 `code`、`reason` 与 `detail` 处理内部错误；关闭取消以 `IDE_CLOSE_CANCELLED` 原因穿透到调用方。

## 从哪里改

- 新增或调整 IDE 动作：同时修改 [`control.proto`](../../platform/daemon-server/src/main/protocol/control.proto)、[`ControlProtocolValidator`](../../platform/daemon-server/src/main/java/fun/fengwk/kkstudio/platform/daemon/protocol/ControlProtocolValidator.java)、Go Daemon 的 [`internal/protocol`](../../daemon-go/internal/protocol/) 与 [`internal/ide/service.go`](../../daemon-go/internal/ide/service.go)，并更新 Java/Go golden 与协议测试。协议变化属于 Daemon 发布，见 [Environment Daemon](../operations/environment-daemon.md)。
- 视图服务与快照：[`EnvironmentIdeServiceImpl.java`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/EnvironmentIdeServiceImpl.java)、[`IdeViewSnapshotStore.java`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/IdeViewSnapshotStore.java)；数据面与授权在 [`gateway`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/gateway/)，跨节点在 [`cluster`](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/cluster/) 包。
- 浏览器 Chat 宿主、sessionStorage/Web Locks 与对账状态机在 [`frontend/src/features/environment/ide`](../../frontend/src/features/environment/ide)；IDE 只挂载在 `/chats/:chatId`，离开 Chat 隐藏并保活同一 iframe，非 Chat 页面不显示 IDE。
- 测试入口：Java [`ControlProtocolCodecTest.java`](../../platform/daemon-server/src/test/java/fun/fengwk/kkstudio/platform/daemon/protocol/ControlProtocolCodecTest.java) 与 Go `daemon-go/internal/protocol` golden 测试守护 wire 格式；`daemon-go/internal/ide` 的 Go 测试覆盖视图生命周期；扩展宿主测试位于 [`daemon-go/ide-extension/extension.test.cjs`](../../daemon-go/ide-extension/extension.test.cjs)。[`IdeViewSnapshotStoreTest.java`](../../apps/ide/src/test/java/fun/fengwk/kkstudio/ide/IdeViewSnapshotStoreTest.java)、[`EnvironmentIdeServiceImplTest.java`](../../apps/ide/src/test/java/fun/fengwk/kkstudio/ide/EnvironmentIdeServiceImplTest.java) 与 [`EnvironmentIdeControllerTest.java`](../../web/src/test/java/fun/fengwk/kkstudio/web/ide/EnvironmentIdeControllerTest.java) 覆盖快照接受、纯读、reason 映射与 Problem 传递。
- 真实 code-server 链路从 [`IdeProductionChainAcceptanceTest.java`](../../web/src/test/java/fun/fengwk/kkstudio/web/environment/ide/IdeProductionChainAcceptanceTest.java) 的显式验收命令运行，参数与浏览器入口见[开发与测试](../operations/development-and-testing.md#真实-ide-与-daemon-验收)。该入口需要显式 Node 24、code-server 发行目录与 Playwright 模块；未实际运行的结果或固定用例数不能作为验收结论。

---

上级：[系统设计](../system-design.md)。相关文档：[Harness Environment](harness-environment.md)、[Environment Daemon](daemon.md)、[Web](web.md)、[Platform](platform.md)。
