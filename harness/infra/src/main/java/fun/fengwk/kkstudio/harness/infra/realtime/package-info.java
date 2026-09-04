/**
 * 与具体消息中间件无关的 realtime transport 端口。
 *
 * <p>Realtime overlay 只提供有损 live projection，通过独立的生命周期围栏与订阅句柄隔离用户回调；
 * 任何连接断开、通知丢失或解码损坏均触发全局快照同步，durable snapshot 始终是唯一恢复事实源。
 */
package fun.fengwk.kkstudio.harness.infra.realtime;
