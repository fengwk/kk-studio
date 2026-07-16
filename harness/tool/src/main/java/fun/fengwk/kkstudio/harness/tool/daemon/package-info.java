/**
 * Cloud 与 Environment Daemon 共享的 WebSocket JSON wire 协议。
 *
 * <p>Envelope 只描述传输顺序和关联标识；具体 payload 在协议版本内按 message type 解释。
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
 * <p>artifact 字节随终态 payload 自包含；连接断开后通过 invocation journal 重发不需要连接内内存映射。
 */
package fun.fengwk.kkstudio.harness.tool.daemon;
