/**
 * Runtime 对基础设施暴露的窄端口。
 *
 * <p>端口不泄漏 Spring、JDBC、Redis 或 HTTP 类型。适配器可提供 PostgreSQL commit 后的 activation hint 与 Redis realtime
 * projection，但两者都不是 durable truth。
 */
package fun.fengwk.kkstudio.harness.runtime.port;
