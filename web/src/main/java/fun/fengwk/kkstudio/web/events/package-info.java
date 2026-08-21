/**
 * 浏览器单应用 WebSocket 事件通道（{@code /api/events/v1}）。
 *
 * <p>客户端帧（严格 JSON，字段集精确）：
 *
 * <pre>{@code
 * {"version":1,"type":"subscribe","resource":{"kind":"thread"|"canvas","id":"<canonical UUID>"}}
 * {"version":1,"type":"unsubscribe","resource":{"kind":"thread"|"canvas","id":"<canonical UUID>"}}
 * }</pre>
 *
 * <p>服务端帧（全部带 {@code version:1}）：
 *
 * <pre>{@code
 * {"version":1,"type":"subscribed","resource":{"kind":"thread"|"canvas","id":"<canonical UUID>"},"cursor":"<canonical decimal>"}
 * {"version":1,"type":"event","resource":{"kind":"thread","id":"<canonical UUID>"},"name":"version","cursor":"<canonical decimal>","data":{"version":"<canonical decimal>"}}
 * {"version":1,"type":"event","resource":{"kind":"thread","id":"<canonical UUID>"},"name":"realtime","data":{...}}
 * {"version":1,"type":"event","resource":{"kind":"canvas","id":"<canonical UUID>"},"name":"version","cursor":"<canonical decimal>","data":{"version":"<canonical decimal>"}}
 * {"version":1,"type":"resync","resource":{"kind":"thread"|"canvas","id":"<canonical UUID>"}}
 * {"version":1,"type":"error","code":"<string>","message":"<string>"}
 * {"version":1,"type":"error","code":"<string>","message":"<string>","resource":{"kind":"thread"|"canvas","id":"<canonical UUID>"}}
 * }</pre>
 *
 * <p>{@code subscribed} 的 {@code cursor} 是订阅建立瞬间的 durable cursor（Thread version / Canvas version）；
 * 该 cursor 之后的事件保证送达，之前的由客户端随后拉取的 snapshot/changes 覆盖。version 事件的 {@code data} 为 {@code
 * {"version":"N"}}，顶层 {@code cursor} 必带且与之相等；realtime 事件的 {@code data} 为 realtime codec JSON 对象且不携带
 * {@code cursor}。事件帧在 ack 帧之后才发送。非法帧（ {@code INVALID_FRAME}）或发送过载（{@code BACKPRESSURE}）时发送连接级 error
 * 帧并关闭连接；未知资源只回资源级 {@code RESOURCE_NOT_FOUND} 并保持连接，客户端重连恢复。
 */
package fun.fengwk.kkstudio.web.events;
