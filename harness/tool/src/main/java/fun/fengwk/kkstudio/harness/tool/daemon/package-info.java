/**
 * Platform 与 Environment Daemon 共享的 WebSocket JSON wire 协议。
 *
 * <p>Envelope 只描述传输顺序和关联标识；具体 payload 在协议版本内按 message type 解释。scope 字段为 canonical {@code
 * environmentName}（Environment 的唯一路由身份：bounded 小写名称，无空白/无 {@code '/'}）；不存在展示名或 UUID。
 *
 * <p>v2 {@code READY} payload 由 {@link
 * fun.fengwk.kkstudio.harness.tool.daemon.DaemonCapabilitiesCodec} 编解码，是版本化/类型化的能力对象： {@code
 * {"version":2,"environment":{"operatingSystem","workingDirectory","timeZone"},
 * "skills":[{"name","description"}],"mcpServers":[{"name","status","error",
 * "tools":[{"name","description"}]}]}}。environment 只含 prompt 所需的 OS family、canonical workdir 与
 * ZoneId；skills 与 MCP server 摘要只含可安全上报的短字段。MCP 工具完整 schema 只通过固定的 {@code mcp_list_tools}
 * 桥接工具返回。Environment 工具由 EnvironmentToolCatalog 固定，不按连接协商。
 *
 * <p>v2 Skill 加载消息：
 *
 * <ul>
 *   <li>{@code LOAD_SKILL}：gateway → daemon，payload {@code {"name":string}}，必须带 {@code
 *       invocationId}；
 *   <li>{@code SKILL_LOADED}：daemon → gateway，payload {@code
 *       {"name":string,"content":string}}，content 为完整 SKILL.md 正文；
 *   <li>{@code SKILL_LOAD_FAILED}：daemon → gateway，payload {@code
 *       {"name":string,"message":string}}。
 * </ul>
 *
 * <p>v2 result payload（{@code PARTIAL} / {@code COMPLETED}）由 {@link
 * fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolResultCodec} 编解码。编码分相：PARTIAL 只允许 text/json，
 * resource/binary 在任何 store 操作之前拒绝；COMPLETED 先对全部内容做计数/单条/聚合资源字节预算预检（默认 8 MiB）， 预检全部通过后才允许任何 store
 * 读写。最终 payload 的 UTF-8 字节数必须 ≤ 16 MiB（bounded 输出在中止点拒绝超限）。 {@code contents} 数组中每个元素为单一对象（最多 64 个）：
 *
 * <ul>
 *   <li>text: {@code {"type":"text","text":string}}；
 *   <li>json: {@code {"type":"json","json":value}}，{@code value} 原样透传；
 *   <li>resource: {@code
 *       {"type":"resource","uri":string,"mediaType":string,"name":string|null,"size":long,
 *       "sha256":string,"contentBase64":string}}。{@code size}/{@code sha256} 对每个 wire resource
 *       都是必填， 且在任何 Base64 分配前完成校验；解码先施加原始 payload 的 UTF-8 上限（16 MiB），再按“当前 size 是否超过剩余 聚合预算”在
 *       Base64 校验之前拒绝超限条目，并配置 Jackson StreamReadConstraints 限制字符串/嵌套/数字长度。 {@code contentBase64} 为
 *       resource 原始 bytes 的 RFC 4648 basic Base64（无换行），解码后长度必须等于 {@code size}、摘要必须等于 {@code
 *       sha256}。
 * </ul>
 *
 * <p>resource 字节随终态 payload 自包含并解码为内联 {@code BinaryToolContent}；PARTIAL 禁止 resource。入站 daemon URI
 * 不是 durable 目的地；持久化外部化由 ToolGateway 在 fenced terminal callback 前完成。连接断开后通过 invocation journal
 * 重发不需要连接内内存映射。
 */
package fun.fengwk.kkstudio.harness.tool.daemon;
