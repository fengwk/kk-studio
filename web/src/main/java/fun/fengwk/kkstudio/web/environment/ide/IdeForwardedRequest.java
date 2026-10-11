package fun.fengwk.kkstudio.web.environment.ide;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内部数据面入口还原出的「浏览器视角」请求：把 owner 收到的 {@code ?path=<URLencoded原始path/query>} 还原为原始 URI、query 与参数。
 *
 * <p>数据面的转发逻辑（bootstrap 消费、path 透传、workspace 参数校验）只依赖原始 path/query 与查询参数，因此内部入口用一个只读包装视图让 owner
 * 侧执行与浏览器直连完全一致的校验与转发，不复制也不放宽任何规则。请求头、Cookie、请求体与 TLS 状态仍取内部请求本身（转发方只带 IDE 会话 cookie 与安全头，TLS
 * 状态由转发方按浏览器侧链路显式声明）。
 */
final class IdeForwardedRequest extends HttpServletRequestWrapper {

  /** 转发方按浏览器侧链路声明的原始 scheme（仅用于 cookie 属性判定）。 */
  static final String SCHEME_HEADER = "X-KK-Origin-Scheme";

  private final String path;
  private final String query;
  private final Map<String, List<String>> parameters;
  private final boolean secure;

  private IdeForwardedRequest(
      HttpServletRequest request,
      String path,
      String query,
      Map<String, List<String>> parameters,
      boolean secure) {
    super(request);
    this.path = path;
    this.query = query;
    this.parameters = parameters;
    this.secure = secure;
  }

  /**
   * 由内部请求与原始 {@code path} 参数构造包装视图。
   *
   * @param request 内部（已认证）请求
   * @param rawPathAndQuery 转发方还原的原始 path（含 query）
   */
  static IdeForwardedRequest of(HttpServletRequest request, String rawPathAndQuery) {
    int question = rawPathAndQuery.indexOf('?');
    String path = question < 0 ? rawPathAndQuery : rawPathAndQuery.substring(0, question);
    String query = question < 0 ? null : rawPathAndQuery.substring(question + 1);
    return new IdeForwardedRequest(
        request, path, query, parse(query), schemeSecure(request.getHeader(SCHEME_HEADER)));
  }

  /** 只接受显式的 {@code https} 声明；缺失或其它值一律按非安全链路处理。 */
  private static boolean schemeSecure(String scheme) {
    return scheme != null && scheme.trim().equalsIgnoreCase("https");
  }

  private static Map<String, List<String>> parse(String query) {
    Map<String, List<String>> parameters = new LinkedHashMap<>();
    if (query == null || query.isEmpty()) {
      return parameters;
    }
    for (String pair : query.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      int equals = pair.indexOf('=');
      String name = equals < 0 ? pair : pair.substring(0, equals);
      String value = equals < 0 ? "" : pair.substring(equals + 1);
      parameters.computeIfAbsent(decode(name), ignored -> new ArrayList<>()).add(decode(value));
    }
    return parameters;
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  @Override
  public String getRequestURI() {
    return path;
  }

  @Override
  public String getQueryString() {
    return query;
  }

  @Override
  public String getParameter(String name) {
    List<String> values = parameters.get(name);
    return values == null || values.isEmpty() ? null : values.get(0);
  }

  @Override
  public String[] getParameterValues(String name) {
    List<String> values = parameters.get(name);
    return values == null ? null : values.toArray(new String[0]);
  }

  @Override
  public Map<String, String[]> getParameterMap() {
    Map<String, String[]> map = new LinkedHashMap<>();
    parameters.forEach((name, values) -> map.put(name, values.toArray(new String[0])));
    return Map.copyOf(map);
  }

  @Override
  public boolean isSecure() {
    return secure;
  }
}
