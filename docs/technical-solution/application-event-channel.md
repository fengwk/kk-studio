# 应用事件通道

浏览器与后端之间唯一的实时事件通道是单条应用级 WebSocket 连接 `ws(s)://<host>/api/events/v1`（服务端 `ApplicationEventWebSocketHandler.PATH`，前端 `ApplicationEventProvider` 应用生命周期内单例）。Thread 的 durable version 与 Canvas 的 graph version 前进、Redis realtime overlay 都经这条连接投递；`subscribe/unsubscribe` 之外**不搬运任何 HTTP 能力**——snapshot、`/changes`、`command-batches`、stop、approval 等仍全部走 REST（无 head relocation 端点）。

事实源：`web/.../events/`（`EventFrameCodec` / `ApplicationEventHub` / `ApplicationEventWebSocketHandler` / `AsyncTextSender` / `ThreadVersionHub` / `CanvasVersionHub`）与前端 `frontend/src/shared/app-events/`（`protocol` / `connection` / `manager` / `context`）。

## 1. 帧协议

所有帧（客户端与服务端）都带 `version:1`。客户端帧字段集精确（duplicate/trailing/unknown/missing/wrong-type 一律拒绝），资源 `id` 必须是 canonical UUID（`UUID.fromString` 往返一致），游标是 canonical 非负十进制字符串（`0|[1-9][0-9]*`，不超 bigint）。

客户端帧：

```json
{"version":1,"type":"subscribe","resource":{"kind":"thread"|"canvas","id":"<canonical UUID>"}}
{"version":1,"type":"unsubscribe","resource":{"kind":"thread"|"canvas","id":"<canonical UUID>"}}
```

`type` 只接受精确小写 `subscribe` / `unsubscribe`；`kind` 只接受 `thread` / `canvas`。

服务端帧：

```json
{"version":1,"type":"subscribed","resource":{"kind":"thread"|"canvas","id":"<canonical UUID>"},"cursor":"<canonical decimal>"}
{"version":1,"type":"event","resource":{...},"name":"version","cursor":"<canonical decimal>","data":{"version":"<canonical decimal>"}}
{"version":1,"type":"event","resource":{...},"name":"realtime","data":{...}}
{"version":1,"type":"event","resource":{...},"name":"version","cursor":"<canonical decimal>","data":{"version":"<canonical decimal>"}}
{"version":1,"type":"resync","resource":{...}}
{"version":1,"type":"heartbeat"}
{"version":1,"type":"error","code":"<string>","message":"<string>"}
{"version":1,"type":"error","code":"<string>","message":"<string>","resource":{...}}
```

- `subscribed`：订阅已在 wire 上建立（首次与每次重连重订阅后都会发送）；`cursor` 是订阅建立瞬间的 durable cursor——Thread 为 version、Canvas 为 version。该 cursor 之后的事件保证送达，之前的由客户端随后拉取的 snapshot / `/changes` 覆盖。
- `event` 的 `name` 为 `version` / `realtime` / `version`：
  - Thread `version`：`data` 为 `{"version":"N"}`，顶层 `cursor` 必带且与 `data.version` 完全相等；
  - Thread `realtime`：`data` 是 realtime codec 的 JSON 对象（`MODEL_DELTA` / `TOOL_PARTIAL` envelope：`threadId/subjectKind/subjectId/attempt/sequence?/type/payload/createdAt`），**绝不携带 `cursor`**；
  - Canvas `version`：`data` 为 `{"version":"N"}`，顶层 `cursor` 必带且与 `data.version` 完全相等。
- `resync`：整体替换为全量快照（重新读取 snapshot / `/changes`）。
- `heartbeat`：连接级空闲保活，不绑定 resource、不携带 cursor/data；前端严格解码后静默消费，不触发业务 listener。
- `error`：`code` / `message`；资源级错误（`RESOURCE_NOT_FOUND`）额外携带 `resource` 供客户端定位，连接级错误不携带。

前端 `decodeServerMessage` 是严格解码：version 必须为 1、每种 type/name 只接受精确字段集、`resource/name` 组合必须合法（thread 仅 `version|realtime`，canvas 仅 `version`）、durable 事件的 cursor 必须存在且与 data 值相等、realtime 的 data 必须是非数组 JSON 对象且不得携带 cursor；畸形消息永远不会到达 listeners。

## 2. 订阅与 ack-before-event

服务端 `ApplicationEventHub` 是传输无关 Hub，按 `(kind, id)` 资源维护本地订阅与共享上游：

- 每个资源只有一组上游：Thread = version source + realtime source，Canvas = version source；首个本地订阅建立上游，最后一个释放时关闭。重复 subscribe 在传输层幂等。
- 订阅原子返回建立瞬间的 durable cursor：上游注册先于 cursor 读取，且 fan-out 与「读 cursor + 注册订阅者」在同一把资源锁内互斥，因此 ack cursor 之后的事件不因注册竞态丢失。
- **事件帧不先于 ack 帧**：`subscribed` ack 帧入队后才 `activate()` 订阅；激活前到达的信号缓冲在订阅内，激活后按到达顺序投递。缓冲与建立上游期间的 early 缓冲都**有界（512 个信号）**，溢出清空并折叠为单个 `resync`（客户端整体快照恢复），保证可恢复且内存有界。
- version 信号在投递前按 ack cursor 过滤掉不晚于游标的陈旧值。

## 3. 错误、关闭与重连

| 情形 | 处理 |
| --- | --- |
| 非法客户端帧 | 连接级 `error{code=INVALID_FRAME,message}`，随后以 1002 `PROTOCOL_ERROR` 关闭 |
| 未知资源（subscribe） | 资源级 `error{code=RESOURCE_NOT_FOUND,resource}`，**保持连接** |
| 发送队列过载（512 帧 / 2MB 双限） | `error{code=BACKPRESSURE}` 后以 1013 `TRY_AGAIN_LATER` 关闭 |
| 应用 shutdown | 尽力发送 `error{code=SEND_FAILED}` 后以 1012 `SERVICE_RESTART` 关闭；断线释放全部订阅 |
| 网络断线 / 传输错误 | 释放全部订阅；前端退避重连 |
| heartbeat 入队过载 | 与普通事件一致，发送 `BACKPRESSURE` 后以 1013 关闭，由前端重连 |

前端 `ApplicationEventConnection` 状态机 `idle -> connecting -> open -> backoff -> connecting -> closed`：

- 每次 `connect()` 递增 generation，旧 socket 的迟到回调因 generation 落后直接失效——过期连接的事件永远不会干扰新连接；
- 重连退避 250ms..10s（每档加最多 20% jitter），`online` / `visibilitychange(visible)` 立即重试；1012（服务重启）0-delay 立即重试；1002/1008（协议错误/策略违规）terminal，不无限重连；
- Manager 按资源维护 listener/refcount：同一资源无论多少消费者只有一条 wire 订阅（首 ref 发 subscribe、末 ref 发 unsubscribe），每次 open（首次与重连）后重发全部 active 订阅；
- `subscribed` / `version` / `resync` / 资源级 `error` 都触发 snapshot 对账，`realtime` 事件走 overlay reducer。

## 4. 不占阻塞线程

发送走 jakarta `AsyncRemote.sendText(SendHandler)`：同一时刻只有一个 in-flight 发送，完成回调驱动下一帧，帧顺序严格串行且**不占用任何常驻或阻塞 worker**；AsyncRemote 设置 10s send timeout，卡死的对端不会无限占用发送链。所有浏览器连接共享一个 daemon `ScheduledExecutorService`，每 20 秒只把 heartbeat 放入既有异步发送队列，不为连接创建线程；连接关闭后立即停止向该连接入队，应用 shutdown 取消共享任务。Thread/Canvas 的事件源（PostgreSQL LISTEN）是各自独立的守护线程，与浏览器连接数无关。

相关文档：[architecture.md](architecture.md)、[harness-runtime-contracts.md](harness-runtime-contracts.md)、[frontend-implementation-design.md](frontend-implementation-design.md)、[e2e-regression.md](e2e-regression.md)。
