package fun.fengwk.kkstudio.ide.gateway;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 内部数据面入口还原出的浏览器视角请求：把 owner 收到的 rawPathAndQuery 还原为原始 URI、query 与参数。 */
public final class IdeForwardedRequest extends HttpServletRequestWrapper {

  /** 转发方按浏览器侧链路声明的原始 scheme（用于 cookie 属性判定）。 */
  public static final String SCHEME_HEADER = "X-KK-Origin-Scheme";

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

  public static IdeForwardedRequest of(HttpServletRequest request, String rawPathAndQuery) {
    int question = rawPathAndQuery.indexOf('?');
    String path = question < 0 ? rawPathAndQuery : rawPathAndQuery.substring(0, question);
    String query = question < 0 ? null : rawPathAndQuery.substring(question + 1);
    return new IdeForwardedRequest(
        request, path, query, parse(query), schemeSecure(request.getHeader(SCHEME_HEADER)));
  }

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
