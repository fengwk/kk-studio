/**
 * Platform 与 Environment Daemon 共享的 WebSocket JSON wire 协议。
 *
 * <p>Envelope 只描述传输顺序和关联标识；具体 payload 在协议版本内按 message type 解释。scope 字段为 canonical {@code
 * EnvironmentId}（Environment 的唯一路由 UUID）。
 *
 * <p>当前 {@code READY} payload 由 {@link
 * fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilitiesCodec} 编解码，是版本化/类型化的能力对象： {@code
 * {"version":6,"environment":{"operatingSystem","timeZone","note","rootPath"},
 * "skills":[{"name","description"}]}}。
 *
 * <p>Capability INVOKE payload 由 {@link
 * fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilityInvokeCodec} 编解码，字段固定为 {@code
 * capabilityId}、{@code capabilityVersion}、{@code workspacePath}、{@code arguments} 和 {@code
 * timeoutMillis}；Environment Daemon 使用 {@link
 * fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocol#VERSION} (v6)。
 *
 * <p>result payload（{@code PARTIAL} / {@code COMPLETED}）由 {@link
 * fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilityResultCodec} 编解码，通过通用 {@code
 * INVOKE} 模型承载所有能力（包括 Skill 与目录列表）， 目录浏览结果与 Resource 引用分别由 {@link
 * fun.fengwk.kkstudio.harness.environment.daemon.EnvironmentDirectoryListing} 与 {@link
 * fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceRef} 建模。
 *
 * <p>本包仅负责协议报文与值对象的严格 wire 校验与编解码，不维护连接状态、connection generation、route lease 或 journal 事实。
 */
package fun.fengwk.kkstudio.harness.environment.daemon;
