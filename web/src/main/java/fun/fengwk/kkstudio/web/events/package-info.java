/**
 * 浏览器单应用 WebSocket 事件通道（{@code /api/events/v2}）。
 *
 * <p>连接建立后的第一帧（严格 JSON）：
 *
 * <pre>{@code
 * {"version":2,"type":"connected","eventNodeId":"<canonical UUID>","connectionId":"<canonical UUID>"}
 * }</pre>
 *
 * <p>客户端帧（严格 JSON，字段集精确）：
 *
 * <pre>{@code
 * {"version":2,"type":"subscribe","resource":{"kind":"thread"|"canvas"|"tree","id":"<canonical UUID>"}}
 * {"version":2,"type":"subscribe","resource":{"kind":"projects"|"interactions"|"environments"}}
 * {"version":2,"type":"unsubscribe","resource":{...}}
 * {"version":2,"type":"ide-use","documentId":"<canonical UUID>","revision":"<positive decimal>","views":[{"environmentId":"<canonical UUID>","viewId":"<canonical UUID>","claimId":"<canonical UUID>","bindingEpoch":"<positive decimal>"}]}
 * }</pre>
 *
 * <p>{@code ide-use} 至多 64 项，列表内 viewId 唯一；缺省 bindingEpoch 表示尚未 claim 的意图，不能用于续租。同一 revision
 * 相同内容幂等，不同内容拒绝；更高 revision 原子替换。每个连接固定一个 documentId。
 *
 * <p>服务端帧（全部带 {@code version:2}）：
 *
 * <pre>{@code
 * {"version":2,"type":"subscribed","resource":{...},"cursor":"<canonical decimal>"}
 * {"version":2,"type":"event","resource":{...},"name":"version"|"realtime"|"changed"|"revision","data":{...},"cursor":"<optional canonical decimal>"}
 * {"version":2,"type":"resync","resource":{...}}
 * {"version":2,"type":"heartbeat"}
 * {"version":2,"type":"ide-use-accepted","revision":"<positive decimal>"}
 * {"version":2,"type":"error","code":"<string>","message":"<string>","resource"?:{...}}
 * }</pre>
 *
 * <p>{@code subscribed} 的 {@code cursor} 是订阅建立瞬间的 durable cursor（Thread version / Canvas
 * revision）；该 cursor 之后的事件保证送达，之前的由客户端随后拉取的 snapshot 覆盖。事件帧在 ack 帧之后才发送。非法帧（{@code
 * INVALID_FRAME}）或发送过载 （{@code BACKPRESSURE}）时发送连接级 error 帧并关闭连接；未知资源只回资源级 {@code
 * RESOURCE_NOT_FOUND} 并保持连接。{@code ide-use} 冲突回连接级 {@code IDE_USE_REJECTED} 并保持连接。服务端每 15 秒发送原生
 * ping，45 秒没有有效 pong 即撤销该连接的 IDE 使用证明并关闭连接。
 */
package fun.fengwk.kkstudio.web.events;
