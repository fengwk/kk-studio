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
 * <p>服务端帧：
 *
 * <pre>{@code
 * {"type":"subscribed","resource":{...},"cursor":"<canonical decimal>"}
 * {"type":"event","resource":{...},"name":"revision","revision":"<canonical decimal>"}
 * {"type":"event","resource":{...},"name":"realtime","payload":{...}}
 * {"type":"event","resource":{...},"name":"version","version":"<canonical decimal>"}
 * {"type":"resync","resource":{...}}
 * {"type":"error","message":"<text>"}
 * }</pre>
 *
 * <p>{@code subscribed} 的 {@code cursor} 是订阅建立瞬间的 durable cursor（Thread revision / Canvas version）；
 * 该 cursor 之后的事件保证送达，之前的由客户端随后拉取的 snapshot/changes 覆盖。事件帧在 ack 帧之后才发送。 非法帧、未知资源或游标错误时发送 {@code
 * error} 帧并关闭连接，客户端重连恢复。
 */
package fun.fengwk.kkstudio.web.events;
