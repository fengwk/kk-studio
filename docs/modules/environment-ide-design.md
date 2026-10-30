# Environment、Daemon 与 IDE 契约

本文是 Environment、Daemon、受管 code-server 与浏览器 IDE 视图的当前契约：谁拥有哪个事实、连接与数据面如何建立、视图如何被绑定与回收、运行状态如何上报。生效版本为 5.0.0：控制 wire 版本 7、READY payload 版本 6、IDE 数据 wire 版本 2、能力目录版本 3。协议细节见 [Harness Environment](harness-environment.md)，IDE 应用见 [IDE](ide.md)，宿主进程见 [Environment Daemon](daemon.md)，浏览器侧见 [Frontend](frontend.md)。

设计遵循“一个事实只有一个负责方”：Platform 管身份、配置和路由，Daemon 管本机执行与资源，code-server 提供工作台，浏览器表达操作和保存展示偏好。用户系统、资源依赖授权、团队与工作空间权限不在本契约内；Environment 保持共享平台资源，不增加用户所有者字段。

## 使用模型

一个浏览器 tab 只有一个全局 IDE 面板。Chat、Project 和环境入口统一调用面板；每个 Environment 在该 tab 中最多一个视图，首次打开才分配资源。切换环境显示已有视图，不重建工作台；不同 tab 使用独立视图，共享对应 Daemon 的 code-server 实例。

```text
Browser tab
  ├─ Chat panes / Project / Environment ──> 全局 IDE 面板
  │                                        ├─ env-A -> view-A
  │                                        └─ env-B -> view-B
  ├─ 一条应用事件 WS：多资源订阅、视图使用登记
  └─ HTTP API 与各 IDE iframe 自己的 HTTP / WS
                     │
                任意 Server
                     │ 单跳内部 HTTP / WS
                owner Server
                     ║
           控制 WS + 一条复用数据 WS
                     ║
                   Daemon
                     ├─ 工具执行与进程监管
                     ├─ 安装更新
                     └─ 一个按需启动的 code-server
                          └─ 多个有独立身份的工作台窗口
```

全局面板挂在应用外壳（[`providers.tsx`](../../frontend/src/app/providers.tsx) 装配 [`IdePanelProvider`](../../frontend/src/features/environment/ide/IdePanelProvider.tsx) 与 [`IdeOverlayPanel`](../../frontend/src/features/environment/ide/IdeOverlayPanel.tsx)），普通路由和聊天 pane 不拥有它的生命周期。收起只隐藏，关闭环境视图才释放资源。已经打开的环境保留各自 iframe，切换仅改变可见性；隐藏的视图仍占资源、仍计入额度。

从某入口再次打开同一环境，只聚焦已有视图；只有带明确目录切换意图的操作才改变工作区，切目录保留未保存文档检查。多个视图可以打开同一目录：它们共享磁盘、Git 工作树和宿主权限，不是协同编辑或文件系统隔离。工作台沿用原生外部修改检测、未修改缓冲自动重载与保存冲突处理。

## 事实与存储边界

| 事实 | 权威方 | 持久化 |
| --- | --- | --- |
| Environment 身份、名称、配置、凭据摘要 | Platform | PostgreSQL |
| Server 节点地址、Daemon owner 与连接租约 | Platform | PostgreSQL |
| 最近 READY 宿主信息、Skill 同步投影、有限运维事件 | Platform | PostgreSQL |
| 当前运行版本与更新进度 | Daemon | 内存；Server 只缓存当前代际 |
| 更新器接管、替换与恢复所需事实 | 本机更新器 | 私有 handoff/result 文件 |
| 活跃 IDE 视图、使用租约、资源句柄 | Daemon | 内存 |
| 工作区描述、可恢复草稿、code-server 用户数据 | Daemon/code-server | 本机私有文件 |
| 当前 tab 的展示与恢复索引 | 浏览器 | sessionStorage |
| 新 tab 使用的最后展示状态 | 浏览器 | localStorage |
| 请求、响应和 IDE 字节流 | 传输两端 | 有界内存队列，不落业务库 |

Server 重启可以重建当前快照，不能凭缓存决定本机进程已经退出。Daemon 重启后的工作区文件只表示恢复资料，不表示旧视图仍活跃。UI 里的未知、离线和历史版本都不能替代当前运行事实。

## Environment 与安装授权

### 数据结构

`environment` 保留 `id`、全局唯一 `name`、`registration_token_hash`、`install_config`、CAS `version` 和时间字段，增加可空的 `install_code jsonb`：

```json
{
  "codeHash": "<sha256>",
  "expiresAt": "<UTC instant>"
}
```

对象只有这两个属性，摘要是 64 位小写十六进制，时间严格解析为 UTC Instant。对象结构由边界校验与 Schema CHECK 共同约束；没有 code 时必须是 SQL NULL。

安装 code 保持随机、高熵、五分钟有效、一次性兑换。签发和兑换在锁定同一 Environment 行的事务中执行：

1. 签发校验当前配置和预期版本，覆盖原 code，不修改安装配置，也不递增 CAS 版本。
2. 兑换校验摘要与数据库时间下的有效期；不匹配或到期不产生安装授权。
3. 成功兑换原子清空 code，生成新注册 token、更新其摘要并递增配置版本。
4. 所有其他 token 修改路径，包括导入和轮换，都在同一事务中清空 code。

明文 token/code 不落库、不写日志，脚本响应使用 no-store。兑换响应丢失后 code 已被消费，用户重新签发。

### 连接表的三个 JSONB

`environment_connection` 保留 owner、lease token、连接状态、最后活动时间和租约：

- `runtime_info`：最后被接受的 READY 宿主 metadata，断线保留，离线页面标明“上次上报”。实际 Daemon 版本只取此上报事实。
- `skill_state`：可重建投影，READY 后重新同步；旧代际不能覆盖新代际。
- `recent_events`：最近 200 条去敏、有界运维事件，包括更新失败；不作为锁、完整审计账本或更新进度库。

当前运行状态与更新进度只存在于当前控制代际的内存缓存，Server 不写运行进度表，也不提供更新历史查询。`cluster_node` 继续描述 Server 节点，不表示宿主机或 code-server 存活。

## 连接、路由与数据复用

### 控制连接

每个 Environment 只有一个当前有效的 Daemon owner。沿用 `(environmentId, ownerNodeId, leaseToken)` 围栏和 `daemonInstanceId`，所有连接写与命令准入都校验有效代际。

控制 WS 长期保持，承载传输握手、心跳、工具命令与有界结果、IDE 管理命令、更新命令、视图租约和数据连接授权。协议严格拒绝未知字段、越界长度和错误消息顺序。已有工具执行、取消、RECOVER 和真实退出证据契约不变。

控制协议在 5.0.0 的形状：

| 消息 | 方向 | 载荷 |
| --- | --- | --- |
| `READY` | Daemon → Server | `version=6`、`daemonVersion`、`environment`、`runtimeStatus` |
| `HEARTBEAT` | Daemon → Server | `runtimeStatus`、`ideLeaseRound` |
| `RUNTIME_STATUS` | Daemon → Server | 与 `runtimeStatus` 相同的关键变化快照 |
| `DATA_BIND` | Server → Daemon | `generation`、`ticket`、`streamUrl` |
| `IDE_LEASE` | Server → Daemon | `round`、`bindings[]` |

`runtimeStatus` 是 `{revision, status}`：`revision` 是规范正十进制字符串且不超过 Java long 上限，只在当前控制代际内比较；`status` 固定为 `{daemonVersion, update}`，`update` 为 null 或 `{targetVersion, downloadedBytes, totalBytes}`，字节计数非负，`totalBytes` 为 null 或不小于已下载量。READY 的 `daemonVersion` 必须与 `runtimeStatus.status.daemonVersion` 一致。Server 只在接受更高 revision 后替换缓存；同 revision 内容不同是协议错误，更低 revision 视为迟到快照忽略。

数据连接首次有需求时，由 owner 经控制连接给出一次性 256-bit 绑定票据，30 秒内消费。票据绑定 Environment、Daemon 实例、owner 租约代际和数据连接代际；只用于整条数据连接，不为每个 HTTP 请求签发。Daemon 仍只主动连接配置的 Studio 受控地址；票据和完整连接 URL 不记日志。每个当前控制代际最多一条已接受的数据连接，数据连接失效后的新连接使用新代际；旧回调不能注销新连接。控制连接失联后不建立新流；已有流只能在 owner 租约仍有效时收尾，围栏失效即关闭。

### 非 owner 请求

浏览器不要求粘性路由：

```text
管理：Browser --HTTP--> B --内部 HTTP--> owner A --控制 WS--> Daemon
页面：Browser --HTTP--> B --内部 HTTP--> owner A ==逻辑流== Daemon --本机 HTTP--> code-server
长流：Browser ==WS== B ==内部 WS== owner A ==逻辑流== Daemon ==本机 WS== code-server
```

B 从 `environment_connection` 和 `cluster_node` 找到 owner，使用现有内部认证单跳转发。A 再次验证自己持有有效路由。原始请求、cookie 和内部头使用已有受控转发规则；业务字节不通过 PostgreSQL 通知搬运。

Daemon 的数据连接若落到 B，B 将整条 WS 单跳桥接给 A。中继不解析或生成逻辑流 ACK，不增加另一套 credit。只有原 owner 消费绑定票据。owner 变化或内部转发失败时终结请求，不改投另一节点重放副作用。

### 数据 wire

数据通道专用于受控 IDE 上游，不接受任意目标 host/port。每个逻辑流携带 `viewId`、视图绑定代际、模式、方法、路径和过滤后的 headers；Daemon 校验视图仍可用，再访问私有 loopback code-server。静态资源也使用视图作用域路由。

每个 WebSocket binary message 正好携带一帧，头部固定 12 字节，数据 wire 版本为 2：

```text
version:u8 | type:u8 | flags:u16 | streamId:u32 | payloadLength:u32 | payload
```

多字节整数使用网络字节序。payload 最大 65536 字节，元数据 JSON 最大 16 KiB。未定义 flags、长度不符和不支持的版本使整条数据连接失败。

非 DATA 帧 flags=0。WS DATA 的 bit0=TEXT、bit1=BINARY，二者必须且只能设置一个；bit2=FIN 表示该上游消息的最后一段，其余位为 0。同一消息的各段类型一致。CLOSE 载荷为 `code:u16 + UTF-8 reason`，按 RFC 6455 校验关闭码及 123 字节原因上限；RESET JSON 固定为 `code, message`，message 上限 500 字符且去除凭据和控制字符。

Server 在真正发出 OPEN 时从 1 严格连续递增分配 streamId，只在本连接内有效且不复用；用尽后先排空再重建连接。streamId 0 保留给连接级 WINDOW。

| type | 载荷与约束 |
| --- | --- |
| 1 OPEN | Server 发出严格请求 JSON，预占流额度；只有 Server 可开流 |
| 2 READY | Daemon 接受逻辑流；拒绝以 RESET 回应，收到 READY 前不发送 body |
| 3 HEADERS | HTTP 状态/响应头，或 WS 上游握手结果；在响应 DATA 前且只发一次 |
| 4 DATA | HTTP flags=0；WS flags 表达 TEXT/BINARY 和 FIN，逐段透传消息边界 |
| 5 WINDOW | 正的 u32 增量额度；streamId=0 补连接额度，否则补对应流额度 |
| 6 END | 空载荷，HTTP 当前方向结束；请求 END 不结束响应 |
| 7 COMPLETE | 空载荷；Server 已消费响应 END 并成功 flush 浏览器输出 |
| 8 RESET | 有界错误码与去敏原因，结束该流两个方向，不表示业务副作用未发生 |
| 9 CLOSE | WS 关闭码和有界原因，保留上游关闭语义，完成双向关闭后回收 |

固定资源预算：最多 64 条并发流，每流每方向初始 credit 为 256 KiB；整连接每方向初始 credit 为 4 MiB。每个 DATA 的成本是 `max(payloadLength, 512)`，必须同时取得流和连接额度；额度只覆盖数据，不覆盖头部和有界管理帧。管理帧队列上限 256 帧/256 KiB，耗尽关闭连接。

接收方真实写入下游并受其背压约束后，归还精确累计消费额度；按至少 64 KiB 消费或队列排空触发。WINDOW 不能超过已发送但未确认的成本，溢出/伪造额度关闭连接。发送器是单一写入者，按流 round-robin 每次最多发送一个 DATA，管理帧优先但不得越过同流先前 DATA 的 END。

RESET 可以放弃该流尚未发送的数据；每侧按本地账本释放丢弃的接收缓冲并归还连接级额度。已关闭流的迟到 DATA 仍计入连接额度，消费后丢弃并返还。对从未分配的流发 DATA、重复 OPEN 或违反连接额度为协议错误；已关闭流的迟到 END/WINDOW 不复活该流。

HTTP 成功条件是响应 END 写入回调成功且收到 COMPLETE。flush 失败使用 RESET，不能用关闭共享 WS 的 1000 表达某条请求成功。首次物理写失败后发送器停用，不重试损坏的底层写状态；所有流以结果未确认/失败终结。TCP 断开、RESET 都不触发自动请求重放。

整条数据连接使用原生 Ping/Pong，15 秒探测、45 秒未响应失效。中继同样必须有界转发，不能先缓存无限数据再归还上游额度。浏览器自身的 code-server WS 仍是独立连接，HTTP 也仍走正常请求；它们不塞进 Studio 的应用事件 WS。GitHub 下载和对象存储直传不经过此数据通道。

## 浏览器工作状态：sessionStorage 与 localStorage

### 作用域与结构

每个 tab 有一个应用级状态容器，React 内存为运行时读取来源。sessionStorage 保存本 tab 恢复快照；localStorage 保存最后一次操作产生的可复用状态。两者均以版本化固定结构编码。

Chat 以 `chatId` 为 scope，包含布局、实际存在的分栏尺寸、聚焦 pane、各 pane 的安全 `PaneTarget`。沿用现有 pane 标识，不将 paneId 当作 IDE 身份。新会话/分支的目标描述可以保存，但不包含提交请求、命令 ID、消息正文或待执行动作。

IDE 是 tab 全局 scope（[`ide-tab-state.ts`](../../frontend/src/features/environment/ide/ide-tab-state.ts)）：

```ts
type IdePresentation = {
  panelVisible: boolean
  selectedEnvironmentId: string | null
  openedEnvironmentIds: string[]
}
type IdeRecovery = {
  viewId: string
  daemonInstanceId: string
  directory: string
}
// sessionStorage: { version: 1, presentation, recoveryByEnvironment }
// localStorage:   { version: 1, presentation }
```

目录是最后确认的恢复信息，不是允许越权访问路径的凭据。运行 `connectionId`、bindingEpoch、iframe DOM、Promise、bootstrap 票据、凭据、请求中状态均不持久化。viewId 只进入 sessionStorage，不能成为跨 tab 默认值。

固定键前缀为 `kk-studio.tab-state.v1:`（sessionStorage）和 `kk-studio.last-state.v1:`（localStorage），后缀为 `chat:<chatId>` 或 `ide`。单 scope 文档限制 64 KiB，严格校验版本、字段、ID 和集合上限；不扫描或清除无关站点存储。

### 初始化与双写

初始化顺序是 sessionStorage 的有效快照、localStorage 的有效快照、产品默认值。localStorage 的 IDE 状态没有运行身份，初始化时为所选环境按需创建自己的视图；其余环境仅保留打开意图，首次切到时才分配资源。

sessionStorage 中已经有 viewId 的视图逐个恢复绑定，即使面板收起也保持这些已打开视图的使用证明；失败或超过保留期的非选中视图仅保留环境选择项，不批量创建新进程。只有默认呈现、没有恢复身份且面板处于收起状态时，不自动创建所选环境的工作台。存储恢复只能执行上述声明式打开/恢复，不重放目录切换、终端命令或上次失败的用户动作。

读取完成后初始化内存与 sessionStorage，不写回 localStorage。刷新恢复、服务器快照回读、离线变化、无效引用清理也不更新全局最后状态。

用户事件经同一个提交入口更新内存：完整本地快照写 sessionStorage，剔除运行身份后的快照写 localStorage。包含布局、分栏比例、焦点、pane 对应 Thread、IDE 展开/收起、选择环境、显式打开/关闭环境。异步创建的 viewId 只补写 sessionStorage；请求结果不能伪造新的用户操作。

localStorage 使用单 scope 的一次 `setItem` 替换快照，最后成功写入者获胜，不加跨 tab 锁。已初始化 tab 不监听最后状态的 storage 事件来覆盖当前布局。页面重挂载先读本 tab 的 sessionStorage。

sessionStorage 复制到新 tab 时，两份后续独立；IDE 运行恢复仍须经过活跃绑定检查。

### 副作用恢复记录的边界

`PendingAcceptance`、`BoundPendingMessage`、Stop 精确重试身份、未知上传属于副作用对账资料，不是“最后使用状态”。保留现有独立存储契约及原始请求身份，不复制进 session/local 默认快照，不因 pane 切换、初始化或存储缺失自动重新提交操作。

展示目标可以从最后状态继承，执行意图不可以。加载现有 pending 记录时必须核对记录自身的 owner、Thread/目标与精确请求身份；仅 paneId 相同不足以把记录应用到当前面板。不匹配的记录保留供原操作对账，不能删除或改写成当前 tab 的请求。

现有 composer 草稿机制也独立保留：文本和已持久化资源引用按既有契约恢复，File/未完成上传不能伪装成可恢复附件。父子组件对 pane 目标统一从 tab 状态容器读取，避免各自重新读 localStorage 得出不同目标。

存储不可用时保留内存，明确提示“本页恢复状态未保存”；恢复索引写入失败不能假装下次刷新仍可接回。损坏的呈现快照可舍弃并选择下一来源，副作用记录损坏不能按“从未执行”处理。

## 统一 IDE 面板与视图接口

### UI 行为

| 操作/事实 | 行为 |
| --- | --- |
| 任意 pane 打开已有 Environment | 展开全局面板并选中已有视图 |
| 首次打开某 Environment | 建立一个视图，保存恢复索引 |
| 切换环境 | 保留其他已打开 iframe，仅切可见性 |
| 收起面板或应用内路由切换 | 不销毁视图，仍续租 |
| 明确关闭当前环境视图 | 等待关闭确认；dirty 时提示保存/放弃 |
| Environment 新增 | 增加选择项，不启动工作台 |
| Environment 暂时离线或列表查询失败 | 显示离线/未知，保留恢复线索 |
| 权威查询确认删除 | 撤销该环境入口与流、移除本地选择；远端清理由控制失效/租约保证 |

不把“列表缺一项”当成删除。环境重命名用稳定 id 保持视图。Daemon 实例变化时原 view 不再作为活跃资源恢复，重新创建工作台。

面板状态不再挂在 AgentPane；去掉 pane 级 IDE 生命周期登记及普通页面路由离开时逐个关闭 IDE 的逻辑。

### 身份与事件登记

documentId 是每次顶层页面加载在内存生成的随机 UUID，不进任何存储，用于区分刷新前后及复制 tab。viewId 是客户端首次分配、同 tab 每 Environment 一个的 UUID，进入 sessionStorage。bindingEpoch 是 Daemon 在成功绑定时递增的代际。

浏览器事件 WS 建立后，Server 返回 `eventNodeId` 与 `connectionId`；页面发送内存 documentId 并登记自己的 IDE 使用意图。二者只是路由/关联键，不是认证凭据，每次 WS 重建都有新 connectionId。登记帧是应用事件协议 v2 的 `ide-use`：`documentId`、`revision` 与 `views[]`，每项是 `environmentId`、`viewId`、`claimId` 与可选 `bindingEpoch`；尚未 claim 的条目没有 epoch，不能续租。服务端回 `ide-use-accepted` 确认 revision，同 revision 内容不同拒绝，新 revision 原子替换列表。

浏览器先经事件 WS 登记 `{environmentId, viewId, claimId}` 的打开/恢复意图，收到确认后发管理 HTTP。owner 使用 cluster_node 找到 eventNodeId，以内部认证查询“该 connectionId/documentId 是否存活且已登记完全相同的意图”，成功才执行 claim；事件节点不可达时不建立新绑定。claim 成功后浏览器把 epoch 写入同一持有列表，再由事件节点提供续租证明。

### 管理 API

| 接口 | 请求/结果 |
| --- | --- |
| POST `/api/environments/{id}/ide/views` | `viewId`、`documentId`、`eventNodeId`、`connectionId`、`claimId`、`mode(create/resume)`、可选 `directory`；成功返回绑定身份、目录、workspacePath 与短期 entryUrl |
| PUT `/api/environments/{id}/ide/views/{viewId}/directory` | 当前 `documentId`、`bindingEpoch`、目标目录；检查 dirty，成功才写工作区 |
| POST `/api/environments/{id}/ide/views/{viewId}/terminal` | 当前 `documentId`、`bindingEpoch`；聚焦/创建该视图终端 |
| DELETE `/api/environments/{id}/ide/views/{viewId}` | 当前 `documentId`、`bindingEpoch` 与显式 `discard`；正常 dirty 拒绝，放弃必须由确认操作产生 |
| 应用事件 WS `ide-use` | `documentId`、`revision`、`views[]`；空列表分离全部，不表示确认销毁 |
| GET `/api/environments/{id}/runtime` | owner 返回当前 `{revision, status}`；无本代 READY 快照时返回 unavailable，而不是伪造 update=null |

UUID 统一规范形式。POST 的 claimId 在一次请求未确认时保持不变；同 claimId/同形请求返回同一绑定，不重复创建。身份相同但参数漂移拒绝。resume 找不到视图返回 `VIEW_GONE`；前端分配新 viewId 后 create，不偷偷把恢复变成新创建。已占用返回 `VIEW_IN_USE`，当前 tab 可以明确打开独立视图。

每个 view 同时只有一个有效 document 绑定。同 documentId 在 WS 断开后重连可重绑，递增 epoch 并使旧通道失效；不同 documentId 仅在旧绑定明确分离或使用证明过期后恢复。刷新时若旧连接尚未释放，显示等待或提供“打开新视图”，不强行踢掉原窗口。复制 tab 不能依靠相同 sessionStorage 接管活跃视图。

异步操作在执行前后均核对 epoch；旧关闭、旧续租、旧 iframe bootstrap 或迟到创建响应不能撤销新绑定。管理命令失败的有界幂等回执按现有命令缓存预算保存，不增加持久任务表。

### 浏览器入口

iframe 使用同源视图作用域路径 `/environment-ide/{environmentId}/{viewId}/...`。bootstrap 入口是 `/environment-ide/{environmentId}/{viewId}/__ide/bootstrap?token=...`，消费一次性票据后设置 Path 限定为该 view 基址的 HttpOnly/SameSite=Strict cookie（可信 TLS 下加 Secure），再跳转到 code-server 的 `/?workspace=<encoded>`；同源网关另有 `__ide/session` 只报告当前 cookie 与代际是否仍可进入数据面，不续租、不重建视图。HTTP、WS upgrade 和逻辑流 OPEN 均复核当前视图绑定；关闭或换代后旧 cookie 失效。

code-server 的资源基址和远端 WS 地址必须保留该 view 路径，需要将 view 上下文传到 code-server 管理接口，覆盖根页面、静态资源、worker、webview 和 WS 重连；不能靠请求 Referer 或全环境共享 cookie 猜窗口。code-server 私有上游 cookie 只由 Daemon 注入，永不到浏览器。

本契约沿用可信部署和外部入口认证，同源 iframe 不是不可信代码沙箱；不能把路径与票据宣称为多租户安全隔离。

## 使用证明与实际资源释放

### 使用证明

复用每 tab 的应用事件 WS，不为每 view 再建一条使用心跳。Server 每 15 秒用 WebSocket 原生 Ping/Pong 检查连接；45 秒没有有效 Pong 即停止为该连接提供证明。后台页面的 JS 定时器不是唯一依据。

有视图时，Daemon 在每次 15 秒心跳中附加递增 `ideLeaseRound`，并在本机记录该轮发出时的单调时间。owner 按事件节点批量查询本轮仍存活的绑定，返回的 `IDE_LEASE` 携带同一 round、viewId、documentId 和 epoch。Daemon 只接受当前未过期轮次、相符绑定的一次证明；轮次在下一心跳或 15 秒后失效，旧控制代际的 round 一律无效。接受后以该轮的本机发出时间作为使用证明时间，而不是以延迟到达时间重新获得完整保留窗口。

这是一轮一批的状态核对，不为每个视图启动定时器连接；失败等下一次正常心跳，不重放旧轮次。整个回收计时由 Daemon 单调时钟完成，不要求宿主机与 Server 墙钟同步。

Daemon 为每个 view 记录最后接受的有效使用证明和当前 epoch：

- 45 秒无证明：不再把旧 document 当成活跃占用，允许恢复竞争。
- 最后有效证明起 600 秒无新证明：开始到期释放。
- 收到明确分离：立即解除占用并冻结该 epoch 的续租，保留从分离起最多 600 秒的恢复窗口；同代际重复分离不反复延长期限，迟到续租不能复活它。
- 创建后没有浏览器使用证明：从创建成功起最多保留 600 秒。

这两个期限分别解决“何时允许接回”和“何时销毁”，不叠加成两段保留窗口。Server 重启、owner 切换和查询快照不重置 Daemon 的本机期限。Daemon 心跳、本机 bridge 活跃、终端输出均不能为浏览器续租。

### 释放过程

对外只展示当前事实，内部需有最小的 `OPEN / CLOSING` 资源门闩：到期与显式关闭都原子进入 CLOSING，停止新流和恢复绑定，然后执行以下流程：

1. 显式正常关闭检查 dirty；拒绝时原子退回 OPEN，不释放资源。显式放弃或租约到期按明确的数据策略继续。
2. 固定上限 10 秒请求工作台/扩展同步可恢复文本和终端状态；不无限等待失联 renderer。
3. 关闭本 view 的 HTTP/WS 流，调用 code-server 的视图终止接口。
4. 等待该视图专属受管范围退出；使用现有进程 supervisor 的终止与升级机制，不能只检查根 PID。
5. 释放 view map、锁、等待者、计时器、bridge 与临时 workspace 引用，撤销票据和 cookie。

到期是主动清理策略，不是退出证据。终止失败的 view 保持 CLOSING、占额度并报告 `CLEANUP_FAILED`，禁止恢复或假报已释放；允许显式再次清理。不能以失败为由杀掉同实例的其他活跃视图。用户文件永不随视图关闭删除。

普通文本的已同步未保存内容保存在受管恢复位置，恢复时明确展示并让用户决定保存或丢弃；恢复文件私有且总量上限 16 MiB，达到上限告警，不静默删除其他视图的恢复内容。备份失败需要事件和可读诊断，不承诺找回浏览器未同步内容或所有自定义编辑器内存。

视图终端属于视图资源，不承诺 tab 消失后永久运行；长期任务使用已有独立任务机制。进程监管只保证其受管范围。

最后一个 view 真正释放、没有 CLOSING/创建中视图和在途数据流后，闲置 60 秒停止整个 code-server 及数据连接。新视图取消停止。Daemon、Agent 工作及独立 LSP 不随之停止。每 Environment 最多 64 个视图，OPEN/CLOSING/创建预留都计数，配额由 Daemon 原子裁决。

### 受管 code-server

code-server 以固定版本的完整运行时随 Daemon 发行（[`runtime-manifest.json`](../../daemon/runtime-manifest.json)），仍使用受管子进程、私有 loopback、匹配 Node 和原生模块。一个 Environment 一个实例。

Daemon 通过 code-server 的本机管理接口操作视图（[`codeServer.ts`](../../daemon/src/ide/codeServer.ts)）：`GET /__kk/ide/views`、`GET /__kk/ide/views/{viewId}?bindingEpoch=...`、`POST /__kk/ide/views/{viewId}` 与 `DELETE /__kk/ide/views/{viewId}`；请求头携带本次启动生成的 `x-kk-studio-management-token`，只监听私有 loopback。检查视图返回它的状态（`OPEN`、`CLOSING`、`CLEANUP_FAILED`、`CLOSED`）与失败原因；关闭视图返回受管的完成证据：`cleanup.complete` 与 http、websocket、管理连接、extension host、terminal 的释放计数。视图同一 epoch 的重复 register/close 幂等，旧 epoch 不能作用于新绑定；关闭期间冻结该视图的新进程和新终端创建，再注销已持有资源。普通恢复只更新绑定，不因 Server 重连重新拉起一套未清理的资源。

code-server 服务端在创建 extension-host connection、终端及相关资源时登记 view 归属；共享 PTY Host 保持共享，通过 terminal ID 释放对应 shell；扩展宿主及其受管后代使用不可复用的监管句柄，不凭旧 PID 误杀新进程。其他视图、共享服务和 Agent 独立进程不能被计入同一个关闭范围。

Daemon 正常停机终止受管 IDE 范围；异常重启先按现有 supervisor 所有权机制核对、收敛旧范围，之后才能启动新实例。code-server 单独崩溃也会使其全部运行视图失效，即使 Daemon 的 instanceId 没变：Daemon 原子停止这些视图的准入并进入清理，清理未确认前不启动第二个 code-server。浏览器恢复得到 `VIEW_GONE` 后按新 viewId 建立视图。

## Daemon 更新：当前状态

### 状态与查询

READY、心跳和关键变化使用同一完整 runtime status 结构；关键变化立即发送 `RUNTIME_STATUS`，正常心跳仍为 15 秒。

```json
{
  "revision": "12",
  "status": {
    "daemonVersion": "5.0.0",
    "update": {
      "targetVersion": "5.0.1",
      "downloadedBytes": 8388608,
      "totalBytes": 134217728
    }
  }
}
```

无更新时 `update` 必须明确为 null，总长度未知时 `totalBytes` 为 null，字节数非负且已知总量不得小于已下载量。进度只统计发行归档，校验文件不混入。100% 只表示下载完成，校验/替换期间对象仍存在。当前 Daemon 版本始终来自上报，目标版本不填补缺失值。

owner 缓存运行状态，通过既有环境变更通知提示有订阅的浏览器回读；通知只传小标识，不承载进度正文。无有效 READY 路由或当前代际尚无状态时，live 查询返回 unavailable；静态卡片仍可显示上次版本。Server 重启后等待 READY/心跳快照，不把空缓存解释为未更新。

### 发起、互斥与幂等

POST `/api/environments/{id}/update` 目标由 Platform 的运行版本及正式发行元数据决定，不接受浏览器自选 URL。Server 复用现有业务在途检查并路由命令，不插入任务行；响应是当前运行快照加命令接收状态（当前已是目标版本、同一目标正在更新、命令已进入发送队列但结果未知）。

Daemon 在同一准入门闩下检查并进入更新：没有未收敛普通执行、没有 OPEN/CLOSING/创建中 IDE 视图、没有活动 IDE 流，且发行与本机平台匹配。纯闲置 code-server 可以先确认停止。与新调用/新视图创建互斥，进入更新后只允许状态、取消和必要的收尾管理消息。

| 条件 | 回复 |
| --- | --- |
| 当前已是目标版本 | 返回当前版本，不重新安装 |
| 正在更新同一目标 | 返回当前 update 快照，不启动第二次 |
| 正在更新不同目标 | 明确 BUSY，返回正在执行的目标 |
| 有在途工作或有效视图 | 明确 BUSY，未开始 |
| 可以开始 | 返回已接受与当前快照，后台执行 |

保留控制请求关联 ID 及有界同请求回执缓存，不提供历史查询。同 ID 参数不同拒绝；新请求在上次明确失败后可以再尝试。请求断线后前端显示“结果未确认”并读快照，不自动生成新 ID 重放。

### 下载与跨进程交接

下载 watchdog 从请求开始计时，只有非空 body chunk 刷新；连续 300 秒无进展才中止。headers、redirect 和空 chunk 不算进展，持续慢下载不受累计时长限制。body 完成后停止 watchdog，再关闭 dispatcher。外部取消、无进展超时、网络失败分别报告，保留清理失败原因但不泄漏 URL/凭据。无自动重试或续传。

校验官方制品、SHA256、平台 manifest、配置和空间条件后，独立更新器取得安装目录级内核互斥，发送本机接管确认，再获得替换许可。Daemon 的内存更新门闩从接受命令一直保持到确定失败或退出；更新器互斥覆盖旧进程退出、备份和替换，不由 Server 回执决定是否安装。

更新器及新进程的握手顺序固定为：

1. 更新器持有互斥，持久写入本次 handoff 和替换步骤，再停止旧受管范围。
2. 在同一文件系统内将旧发行重命名为唯一备份，再将 staging 重命名到发行位置；每步持久记录。两个 rename 不是一个原子事务，中途缺失发行路径必须由交接恢复处理。保留持久配置/用户数据，不原地逐文件覆盖运行程序。
3. 启动新 Daemon。新进程完成本机版本/配置预检，但在更新器完成交接前不接受普通业务。
4. 新进程通过私有本机通道报告目标版本启动成功；更新器写 result 并释放互斥，新 Daemon 才进入正常 READY。

正常启动若发现互斥仍被其他更新器持有，不能创建第二个更新器。崩溃后凭明确的 handoff、目录布局和进程事实完成或拒绝恢复；不能因文件陈旧或计时到期猜测成功。失败时恢复路径必须保持旧归档/备份可用，不承诺任意失败点都能自动回滚。

本机操作目录的 ID 只用于安全交接和诊断，不重新变成 Server 任务模型。能够恢复运行的 Daemon 将失败报告为有界事件并清空 update；不能启动时 Server 只显示离线/未知。正式成功体验来自目标版本 Daemon 的 READY，不来自文件下载完成或 websocket 断开。

## 代码落点

本契约不增加 Maven 模块，不引入通用 workflow、连接池框架或新数据库服务。边界沿用 apps → platform → harness → common，Web 作为组合根。

| 落点 | 职责 |
| --- | --- |
| [Schema baseline](../../schema/src/main/resources/db/migration/V1__schema.sql) | 安装 code 合并进 `environment`，连接表只保留三种投影 |
| [Environment service](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/environment/EnvironmentService.java) | 一次性授权事务、token 修改统一失效 |
| [Environment registry](../../platform/runtime/src/main/java/fun/fengwk/kkstudio/platform/environment/registry/EnvironmentRegistry.java) | 保留租约与三种投影，不存 IDE/更新任务 |
| [Environment server](../../harness/environment/src/main/java/fun/fengwk/kkstudio/harness/environment/server/EnvironmentDaemonServer.java) | 控制状态契约、更新准入、代际绑定；IDE 载荷仍由应用定义 |
| [IDE application](../../apps/ide/src/main/java/fun/fengwk/kkstudio/ide/) | mux codec/流状态、视图管理命令、租约协调与跨节点服务 |
| [IDE Web gateway](../../web/src/main/java/fun/fengwk/kkstudio/web/environment/ide/) | HTTP/WS 适配、视图入口、绑定校验与单跳代理 |
| [Browser event channel](../../web/src/main/java/fun/fengwk/kkstudio/web/events/) | connectionId、ide-use、原生 Ping/Pong 和连接释放 |
| [Daemon runtime](../../daemon/src/runtime.ts) / [update](../../daemon/src/update.ts) | 本机准入、完整状态、下载进度与更新器交接 |
| [Daemon IDE](../../daemon/src/ide/) / [transport](../../daemon/src/transport/) | 视图权威、期限和资源句柄、数据复用 |
| [Runtime manifest](../../daemon/runtime-manifest.json) / [IDE extension](../../daemon/ide-extension/) | 固定带管理接口的 code-server 发行与桥接契约 |
| [IDE panel](../../frontend/src/features/environment/ide/IdeOverlayPanel.tsx) / [pane entry](../../frontend/src/features/chat/runtime/AgentPane.tsx) | pane 入口调用全局面板，移除 pane 对 IDE 生命周期所有权 |
| [Chat page](../../frontend/src/features/chat/ChatWorkspacePage.tsx) / [pane state](../../frontend/src/features/chat/chat-pane-state.ts) / [pane target hook](../../frontend/src/features/chat/runtime/usePaneTarget.ts) | tab 单一状态来源、用户动作双写、初始化只读 |
| [Pending contracts](../../frontend/src/features/chat/runtime/agent-pane/pane-target.ts) / [composer storage](../../frontend/src/features/chat/composer/composer-draft.ts) | 呈现状态与副作用恢复资料分离，保留精确重试 |

协议破坏性变更分别升级协议号：控制 wire 7、READY 6、IDE 数据 wire 2、浏览器事件协议 2；能力目录保持 3。产品版本必须升级，固定 code-server fork 也必须发布新不可变制品并更新摘要。

## 验收要点

| 验收面 | 必须观察到的结果 |
| --- | --- |
| 安装授权 | 并发兑换只成功一次；覆盖签发、过期、导入/轮换 token 后旧 code 失效 |
| 运行状态 | 重连/Server 重启恢复实际快照；未知不变成 null；目标不覆盖运行版本 |
| 更新互斥 | 相同目标重复请求不重复执行；新任务/新视图与进入更新原子互斥 |
| 下载 | 累计超过 300 秒仍有进展则成功；无进展/取消/网络错误区分，body 后慢清理不误报超时 |
| 更新交接 | 在停止、替换、启动各边界注入崩溃，不出现两更新器或新进程提前准入 |
| 数据并发 | 多流字节、类型、FIN、半关闭正确；慢流不吞噬整连接内存 |
| 协议故障 | 超窗/畸形帧失败；单流 RESET 不误杀其他流；物理发送失败不重试底层坏状态 |
| HTTP 完成 | END 写回调、消费及 flush 任一失败都不能报告成功 |
| 多节点 | 浏览器事件 WS、管理 HTTP、Daemon 数据 WS 分别落在不同非 owner 时仍能正确绑定/转发；owner 换代时不重放请求 |
| tab 存储 | A/B 各自恢复，用户动作双写，初始化不覆盖全局，viewId 永不进入 local 默认 |
| 未知请求 | pane 切换/刷新不生成新身份重复提交；pending/Stop/上传对账契约保持 |
| 面板 | 同环境跨 pane 复用；跨环境保留 iframe；普通路由切换不销毁；删除与离线不同处理 |
| 恢复竞争 | 刷新/复制 tab/响应丢失/旧关闭迟到，始终最多一个当前绑定且不误关新连接 |
| 保留窗口 | 599 秒可恢复、600 秒进入释放；中途真正续租延长；旧证明/Server 重启不重置期限 |
| 实际资源 | 正常关闭、强杀 tab、丢失关闭请求后，相关流、扩展宿主、终端和登记确实收敛；code-server 单独崩溃不保留假活跃视图或并行重启两实例 |
| 多视图隔离 | 关闭 A 不影响 B；同目录文件变化按工作台规则更新 |
| 最后视图 | 全部资源已收敛后闲置 60 秒停止 IDE，Agent 和独立 LSP 不受影响 |
| 草稿恢复 | 已同步文本能被恢复；备份失败明确报告，不伪造零丢失或静默清除用户文件 |

真实进程验收覆盖发行支持的五个平台；浏览器验收包含文件编辑/外部重载、目录切换、终端、刷新、多 tab、后台冻结和崩溃断线。

---

上级：[系统设计](../system-design.md)。相关文档：[Environment Daemon](daemon.md)、[IDE](ide.md)、[Frontend](frontend.md)、[开发与测试](../operations/development-and-testing.md)。
