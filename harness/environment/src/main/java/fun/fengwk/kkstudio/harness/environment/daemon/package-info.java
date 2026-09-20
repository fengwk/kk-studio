/**
 * Platform 与 Environment Daemon 共享的 WebSocket JSON wire 协议。
 *
 * <p>Envelope 只描述消息类型和关联标识，不承载全局顺序；具体 payload 在协议版本内按 message type 解释。scope 字段为 canonical {@code
 * EnvironmentId}（Environment 的唯一路由 UUID）。
 *
 * <p>当前 {@code READY} payload 由 {@link
 * fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilitiesCodec} 编解码，是版本化/类型化的宿主 metadata
 * 对象： {@code
 * {"version":2,"environment":{"operatingSystem","timeZone","userName","homeDirectory","note"}}}。它是
 * READY 的唯一 wire 形状：Daemon 不再上报任何目录或 Skill 事实。
 *
 * <p>Capability INVOKE payload 由 {@link
 * fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilityInvokeCodec} 编解码，字段固定为 {@code
 * capabilityId}、{@code capabilityVersion}、{@code arguments} 和 {@code timeoutMillis}；Environment
 * Daemon 使用 {@link fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocol#VERSION}。 {@code
 * mcp.local.discover} 只复用该 INVOKE/CANCEL/结果通道，不成为模型 Tool。
 *
 * <p>result payload（{@code PROGRESS} / {@code COMPLETED}）由 {@link
 * fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilityResultCodec} 编解码，通过通用 {@code
 * INVOKE} 模型承载所有能力。resource 内容不在 wire 上传输：Daemon 终态编码前把字节经 {@link
 * fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceUploader} 直传全局对象存储，wire 只承载 {@code
 * uploadId} 与权威元数据，控制面由 {@link
 * fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceTransferCodec} 的 {@code
 * RESOURCE_UPLOAD_REQUEST}/{@code COMMIT}/{@code TICKET} 消息承载。
 *
 * <p>需要 workdir 的能力由具体 arguments 携带目标 OS 上的绝对目录；{@link
 * fun.fengwk.kkstudio.harness.environment.daemon.DaemonWorkdirSyntax} 提供发送前的纯词法形状校验。
 *
 * <p>本包仅负责协议报文与值对象的严格 wire 校验与编解码，不维护连接状态、connection generation、route lease 或 journal 事实。
 */
package fun.fengwk.kkstudio.harness.environment.daemon;
