package fun.fengwk.kkstudio.harness.environment.tunnel;

import java.util.List;
import java.util.Map;

/**
 * 一次隧道 HTTP 交换的请求行与请求头。
 *
 * <p>鉴权头、cookie 与 hop-by-hop 头由调用方决定，本对象不重复实现这些策略。构造时拒绝非法方法、非 origin-form 路径、非法头名和 CR/LF
 * 注入，并深度复制为不可变头。
 */
public record TunnelHttpRequest(String method, String path, Map<String, List<String>> headers) {

  /**
   * 校验并冻结请求。
   *
   * @param method 合法 HTTP token
   * @param path 以 {@code /} 开始的 origin-form，不含 scheme、authority 或 fragment
   * @param headers 多值请求头；没有请求头时传 {@link Map#of()}，不能为 {@code null}
   * @throws IllegalArgumentException 任一字段不满足契约
   */
  public TunnelHttpRequest {
    method = TunnelHttp.requireToken(method, "method");
    path = TunnelHttp.requireOriginPath(path);
    headers = TunnelHttp.freezeHeaders(headers);
  }
}
