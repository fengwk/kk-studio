/**
 * 与具体消息中间件无关的 realtime transport 端口。
 *
 * <p>Realtime overlay 只提供有损 live projection，通过独立的生命周期围栏与订阅句柄隔离用户回调。连接失联或检测到消息损坏时触发全局快照同步；
 * 通知本身仍可能无感丢失，因此 durable snapshot 始终是唯一恢复事实源。
 */
package fun.fengwk.kkstudio.harness.infra.realtime;
