/**
 * Platform 与 Environment Daemon 共享的 WebSocket JSON wire 协议。
 *
 * <p>Envelope 只描述传输顺序和关联标识；具体 payload 在协议版本内按 message type 解释。scope 字段为 canonical {@code
 * environmentId}（Environment 的唯一持久路由身份）与实时 display {@code environmentName}；路由只使用
 * environmentId，display name 仅用于人读标签。
 *
 * <p>v2 {@code READY} payload 由 {@link
 * fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillsCodec} 编解码，形状为 {@code
 * {"skills":[{"name","description"}]}。Environment 工具由 EnvironmentToolCatalog 固定，不按连接协商。
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
 * resource/binary 在任何 store 操作之前拒绝；COMPLETED 先对全部内容做计数/单条/聚合资源字节预算预检（默认 8 MiB），
 * 预检全部通过后才允许任何 store 读写。最终 payload 的 UTF-8 字节数必须 ≤ 16 MiB（bounded 输出在中止点拒绝超限）。
 * {@code contents} 数组中每个元素为单一对象（最多 64 个）：
 *
 * <ul>
 *   <li>text: {@code {"type":"text","text":string}}；
 *   <li>json: {@code {"type":"json","json":value}}，{@code value} 原样透传；
 *   <li>resource: {@code
 *       {"type":"resource","uri":string,"mediaType":string,"name":string|null,"size":long,
 *       "sha256":string,"contentBase64":string}}。{@code size}/{@code sha256} 对每个 wire resource 都是必填，
 *       且在任何 Base64 分配前完成校验；解码先施加原始 payload 的 UTF-8 上限（16 MiB），再按“当前 size 是否超过剩余
 *       聚合预算”在 Base64 校验之前拒绝超限条目，并配置 Jackson StreamReadConstraints 限制字符串/嵌套/数字长度。
 *       {@code contentBase64} 为 resource 原始 bytes 的 RFC 4648 basic Base64（无换行），解码后长度必须等于
 *       {@code size}、摘要必须等于 {@code sha256}。
 * </ul>
 *
 * <p>resource 字节随终态 payload 自包含并解码为内联 {@code BinaryToolContent}；PARTIAL 禁止 resource。入站 daemon URI
 * 不是 durable 目的地；持久化外部化由 ToolGateway 在 fenced terminal callback 前完成。连接断开后通过 invocation journal
 * 重发不需要连接内内存映射。
 */
package fun.fengwk.kkstudio.harness.tool.daemon;
