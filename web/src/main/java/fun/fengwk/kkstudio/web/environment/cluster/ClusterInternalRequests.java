package fun.fengwk.kkstudio.web.environment.cluster;

import jakarta.servlet.http.HttpServletRequest;

import fun.fengwk.kkstudio.platform.environment.cluster.ClusterInternalAuthenticationException;
import fun.fengwk.kkstudio.platform.environment.cluster.HmacClusterInternalAuthentication;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内部端点共用的请求取值约定：认证头必须恰好出现一次，canonical path 必须逐字等于发送端签名时的字符串。
 *
 * <p>重复出现的认证头一律按认证失败拒绝（绝不静默取首个）；canonical path 用原始 requestURI + query，不做任何规范化或解码。
 */
public final class ClusterInternalRequests {

  private static final List<String> AUTH_HEADER_NAMES =
      List.of(
          HmacClusterInternalAuthentication.HEADER_TARGET,
          HmacClusterInternalAuthentication.HEADER_TIMESTAMP,
          HmacClusterInternalAuthentication.HEADER_NONCE,
          HmacClusterInternalAuthentication.HEADER_SIGNATURE);

  private ClusterInternalRequests() {}

  /** 取出四个内部认证头；同名头出现多次即拒绝。 */
  public static Map<String, String> authHeaders(HttpServletRequest request) {
    Map<String, String> headers = new HashMap<>();
    for (String name : AUTH_HEADER_NAMES) {
      Enumeration<String> values = request.getHeaders(name);
      if (values == null || !values.hasMoreElements()) {
        continue;
      }
      String value = values.nextElement();
      if (values.hasMoreElements()) {
        throw new ClusterInternalAuthenticationException(
            "duplicate internal authentication header");
      }
      headers.put(name, value);
    }
    return headers;
  }

  /** 认证与转发共用的 canonical path（原始 URI + query，不做规范化）。 */
  public static String canonicalPath(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String query = request.getQueryString();
    return query == null ? uri : uri + "?" + query;
  }
}
