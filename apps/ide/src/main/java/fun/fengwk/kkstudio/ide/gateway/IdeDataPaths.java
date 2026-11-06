package fun.fengwk.kkstudio.ide.gateway;

import jakarta.servlet.http.HttpServletRequest;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.cluster.IdeInternalPaths;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** IDE 数据面路径规则与重定位。 */
public final class IdeDataPaths {

  private static final String DATA_HTTP_SUFFIX = "/data-http";
  private static final String DATA_WS_SUFFIX = "/data-ws";
  static final String VIEW_ID_PARAMETER = "viewId";
  static final String PATH_PARAMETER = "path";

  private IdeDataPaths() {}

  /** 组装发往 Daemon 的请求路径：{@code /ide/<viewId>/<bindingEpoch><viewPath>}。 */
  public static String daemonPath(UUID viewId, long bindingEpoch, String viewPath) {
    Objects.requireNonNull(viewId, "viewId");
    if (bindingEpoch <= 0) {
      throw new IllegalArgumentException("bindingEpoch must be positive");
    }
    Objects.requireNonNull(viewPath, "viewPath");
    if (!viewPath.startsWith("/")) {
      throw new IllegalArgumentException("viewPath must start with /");
    }
    return "/ide/" + viewId + "/" + bindingEpoch + viewPath;
  }

  public static String dataHttpPath(
      EnvironmentId environmentId, UUID viewId, String rawPathAndQuery) {
    return dataPath(environmentId, viewId, rawPathAndQuery, DATA_HTTP_SUFFIX);
  }

  public static String dataWebSocketPath(
      EnvironmentId environmentId, UUID viewId, String rawPathAndQuery) {
    return dataPath(environmentId, viewId, rawPathAndQuery, DATA_WS_SUFFIX);
  }

  private static String dataPath(
      EnvironmentId environmentId, UUID viewId, String rawPathAndQuery, String suffix) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(viewId, "viewId");
    Objects.requireNonNull(rawPathAndQuery, "rawPathAndQuery");
    return IdeInternalPaths.environmentPrefix(environmentId)
        + suffix
        + "?"
        + VIEW_ID_PARAMETER
        + "="
        + viewId
        + "&"
        + PATH_PARAMETER
        + "="
        + URLEncoder.encode(rawPathAndQuery, StandardCharsets.UTF_8);
  }

  /** 浏览器 path 必须是以 {@code /} 起始的 view 相对路径；拒绝点段、反斜杠与编码分隔符，避免 Daemon 拼接内部基址后被归一化跳出视图作用域。 */
  public static boolean isSafeViewPath(String rawPathAndQuery) {
    if (rawPathAndQuery == null
        || !rawPathAndQuery.startsWith("/")
        || rawPathAndQuery.startsWith("//")) {
      return false;
    }
    int question = rawPathAndQuery.indexOf('?');
    String path = question < 0 ? rawPathAndQuery : rawPathAndQuery.substring(0, question);
    if (path.indexOf('\\') >= 0 || path.contains("//")) {
      return false;
    }
    String lower = path.toLowerCase(Locale.ROOT);
    if (lower.contains("%2e")
        || lower.contains("%2f")
        || lower.contains("%5c")
        || lower.contains("%00")) {
      return false;
    }
    for (String segment : path.split("/", -1)) {
      if (".".equals(segment) || "..".equals(segment)) {
        return false;
      }
    }
    return true;
  }

  /** 浏览器 view 基址；{@code viewId == null} 时只返回 Environment 根路径。 */
  public static String viewBase(
      HttpServletRequest request, EnvironmentId environmentId, UUID viewId) {
    String contextPath = request.getContextPath();
    String base =
        contextPath == null || contextPath.isEmpty()
            ? IdeGatewayConstants.ROUTE_PREFIX + environmentId + "/"
            : (contextPath.endsWith("/") ? contextPath : contextPath + "/")
                + "ide/"
                + environmentId
                + "/";
    return viewId == null ? base : base + viewId + "/";
  }

  /** 把上游绝对 path 重定位到当前浏览器 view 基址；绝对 URL 保持不变。 */
  public static String rebaseLocation(
      String location, HttpServletRequest request, EnvironmentId environmentId, UUID viewId) {
    if (location == null || location.isBlank()) {
      return location;
    }
    if (location.startsWith("/") && !location.startsWith("//")) {
      String relative = internalViewRelative(location, viewId);
      String base = viewBase(request, environmentId, viewId);
      return base + relative.substring(1);
    }
    return location;
  }

  /** Worker scope 只能落在当前浏览器 view；无效或越界声明收窄到 view 根。 */
  public static String rebaseWorkerScope(
      String scope, HttpServletRequest request, EnvironmentId environmentId, UUID viewId) {
    String base = viewBase(request, environmentId, viewId);
    if (viewId == null || scope.indexOf('?') >= 0 || scope.indexOf('#') >= 0) {
      return base;
    }
    String relative = internalViewRelative(scope, viewId);
    if (!relative.equals(scope) && isSafeViewPath(relative)) {
      return base + relative.substring(1);
    }
    try {
      URI uri = URI.create(scope);
      if (uri.isAbsolute()
          || uri.getRawAuthority() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null) {
        return base;
      }
      String path = uri.getRawPath();
      if (path != null && path.startsWith("./")) {
        path = path.substring(2);
      }
      if (path == null || !isSafeViewPath("/" + path)) {
        return base;
      }
      String resolved = URI.create(request.getRequestURI()).resolve(uri).getRawPath();
      return resolved != null && resolved.startsWith(base) ? resolved : base;
    } catch (IllegalArgumentException error) {
      return base;
    }
  }

  private static String internalViewRelative(String location, UUID viewId) {
    if (viewId == null) {
      return location;
    }
    String prefix = "/__kk/ide/view/" + viewId + "/";
    if (!location.startsWith(prefix)) {
      return location;
    }
    int slash = location.indexOf('/', prefix.length());
    int question = location.indexOf('?', prefix.length());
    int end;
    if (slash < 0 && question < 0) {
      end = location.length();
    } else if (slash < 0) {
      end = question;
    } else if (question < 0) {
      end = slash;
    } else {
      end = Math.min(slash, question);
    }
    if (!positiveDecimal(location.substring(prefix.length(), end))) {
      return location;
    }
    String tail = location.substring(end);
    if (tail.isEmpty()) {
      return "/";
    }
    return tail.charAt(0) == '?' ? "/" + tail : tail;
  }

  private static boolean positiveDecimal(String value) {
    if (value.isEmpty() || value.charAt(0) == '0') {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }
}
