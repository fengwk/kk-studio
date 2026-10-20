package fun.fengwk.kkstudio.platform.environment.cluster;

import java.util.Map;
import java.util.UUID;

/**
 * 内部（跨节点）HTTP/WS 请求的认证端口。
 *
 * <p>{@link #sign} 为发往目标 owner 的请求生成标准头；{@link #verify} 在任何 daemon 副作用之前校验入站内部请求。签名 HMAC 绑定 {@code
 * method}、canonical {@code requestPath}、目标实例 UUID、{@code timestamp} 与 {@code nonce}，并带 30s
 * 有界内存重放拒绝。
 *
 * <p>最终约定：不使用额外的 {@code X-KK-IDE-Path} 头；内部 HTTP raw path 作为 URL query 固定放入 canonical {@code
 * requestPath}（例如 {@code /internal/environment-ide/{env}/http?path=<URLEncoded RAW>}），因此原始 query
 * 语义已被签名覆盖。 秘密绝不进入 URL、日志或异常消息。
 */
public interface ClusterInternalAuthentication {

  /**
   * 生成发往 {@code owner} 的认证头。
   *
   * @param method HTTP 方法（大小写不敏感，签名前统一大写）
   * @param requestPath 已编码的 canonical path（以 {@code /} 开头，含 query 时一并包含）
   * @param targetNodeId 目标节点实例 UUID
   * @return 不可变的标准认证头集合
   */
  Map<String, String> sign(String method, String requestPath, UUID targetNodeId);

  /**
   * 校验入站内部请求的认证头；任何缺失、越界、目标/方法/路径不符、时间戳越界或重放都以异常 fail-closed。
   *
   * @param method HTTP 方法
   * @param requestPath 与发送端一致的 canonical path
   * @param headers 入站请求头（大小写不敏感读取）
   * @throws ClusterInternalAuthenticationException 校验失败
   */
  void verify(String method, String requestPath, Map<String, String> headers);
}
