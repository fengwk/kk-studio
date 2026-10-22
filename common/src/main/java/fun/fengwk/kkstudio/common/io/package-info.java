/**
 * 跨调用方复用的 JDK HTTP 响应体边界工具。
 *
 * <p>{@link fun.fengwk.kkstudio.common.io.DeadlineResponseBody} 把 send 前冻结的剩余预算延伸到响应体读取， 覆盖 JDK
 * ofInputStream 只约束 headers 的空档：到期主动关闭底层 body 打断阻塞 read，并把超时引发的 EOF/IO 统一呈现为超时失败。
 *
 * <p>本包只持有纯 JDK 的流包装与截止点状态，不构造 HTTP 客户端、不持有调用方凭据，也不承载领域语义。
 */
package fun.fengwk.kkstudio.common.io;
