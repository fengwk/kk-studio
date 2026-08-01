/**
 * Platform 与 Environment Daemon 共享的 WebSocket JSON wire 协议。
 *
 * <p>Envelope 只描述传输顺序和关联标识；具体 payload 在协议版本内按 message type 解释。scope 字段为实时唯一 {@code
 * environmentName}，不再使用持久数值 environment id。
 *
 * <p>v1 {@code READY} payload 由 {@link
 * fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillsCodec} 编解码，形状为 {@code
 * {"skills":[{"name","description"}]}。Environment tools are fixed by EnvironmentToolCatalog and
 * are not negotiated per connection.
 *
 * <p>v1 Skill 加载消息：
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
 * <p>v1 result payload（{@code PARTIAL} / {@code COMPLETED}）由 {@link
 * fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolResultCodec} 编解码。{@code contents} 数组中每个元素为单一对象：
 *
 * <ul>
 *   <li>text: {@code {"type":"text","text":string}}；
 *   <li>json: {@code {"type":"json","json":value}}，{@code value} 原样透传；
 *   <li>artifact: {@code
 *       {"type":"artifact","artifactId":string,"mediaType":string,"sizeBytes":long,
 *       "contentBase64":string}}。{@code contentBase64} 为 artifact 原始 bytes 的 RFC 4648 basic
 *       Base64（无换行），且解码后长度必须等于 {@code sizeBytes}。
 * </ul>
 *
 * <p>artifact 字节随终态 payload 自包含并解码为内联 {@code BinaryToolContent}；PARTIAL 禁止 artifact。持久化
 * ArtifactStore 仅由 ToolWorker 在 fenced terminal supplier 内写入。连接断开后通过 invocation journal
 * 重发不需要连接内内存映射。
 */
package fun.fengwk.kkstudio.harness.tool.daemon;
