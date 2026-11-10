/**
 * Provider 请求预算：{@link fun.fengwk.kkstudio.harness.model.limits.RequestLimits}
 * 值类型、代码内预设与按成员取最严值的合并。
 *
 * <p>限制全部来自代码内预设，按 Provider 协议与官方域名匹配（PLAN 11.1）；代理、私有网关等其他端点全部未知，不补默认值。字段注明 RAW、BASE64 或 BODY
 * 口径，「小于 X」归一为 X − 1，未知即缺省。
 */
package fun.fengwk.kkstudio.harness.model.limits;
