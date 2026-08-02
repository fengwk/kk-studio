/**
 * Runtime 对基础设施暴露的窄端口。
 *
 * <p>端口不泄漏 Spring、JDBC、Redis 或 HTTP 类型。PostgreSQL durable activation 是调度 truth；Redis adapter 只提供
 * lossy realtime projection。
 */
package fun.fengwk.kkstudio.harness.runtime.port;
