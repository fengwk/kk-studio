/**
 * Platform 与 Environment Daemon 共享的 WebSocket JSON wire 协议。
 *
 * <p>Envelope 只描述传输顺序和关联标识；具体 payload 在协议版本内按 message type 解释。scope 字段为 canonical {@code
 * environmentName}（Environment 的唯一路由身份：bounded 小写名称，无空白/无 {@code '/'}）；不存在展示名或 UUID。
 *
 * <p>当前 {@code READY} payload 由 {@link
 * fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilitiesCodec} 编解码，是版本化/类型化的能力对象： {@code
 * {"version":5,"environment":{"operatingSystem","timeZone","note"},
 * "skills":[{"name","description"}],"mcpServers":[{"name","status","error",
 * "tools":[{"name","description"}]}]}}。
 *
 * <p>Capability INVOKE payload 由 {@link
 * fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilityInvokeCodec} 编解码，字段固定为 {@code
 * capabilityId}、{@code capabilityVersion}、{@code workspacePath}、{@code arguments} 和 {@code
 * timeoutMillis}；Environment Daemon 使用 {@link DaemonProtocol#VERSION} (v5)。
 *
 * <p>result payload（{@code PARTIAL} / {@code COMPLETED}）由 {@link
 * fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilityResultCodec} 编解码。
 */
package fun.fengwk.kkstudio.harness.environment.daemon;
