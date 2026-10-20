/**
 * Realtime transport 端口与本地优先总线。
 *
 * <p>Realtime overlay 只提供有损 live projection，通过独立的生命周期围栏与订阅句柄隔离用户回调。连接失联或检测到消息损坏时触发全局快照同步；
 * 通知本身仍可能无感丢失，因此 durable snapshot 始终是唯一恢复事实源。{@link
 * fun.fengwk.kkstudio.harness.infra.realtime.RealtimeBus} 在该端口之上先本地投递，仅在存在其它活跃节点时跨 PostgreSQL 发布，并用
 * envelope origin 丢弃自身回环。{@link fun.fengwk.kkstudio.harness.infra.realtime.RealtimePayloadBuffer}
 * 只保存 PostgreSQL 放不下的完整 canonical event，按字节/条数/TTL 有界，绝不截断内容。
 */
package fun.fengwk.kkstudio.harness.infra.realtime;
