package fun.fengwk.kkstudio.web.environment.ide;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.ide.cluster.IdeInternalPaths;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 数据面的内部单跳路径：只由 owner 路由节点使用，全部路径都参与 HMAC 签名。
 *
 * <p>物理数据连接与浏览器逻辑 WS 都只做一跳：非 owner 不解析 mux 帧、不生成 ACK，也不把请求改投其它节点。
 */
final class IdeDataInternalPaths {

  static final String DATA_HTTP_SUFFIX = "/data-http";

  static final String DATA_WS_SUFFIX = "/data-ws";

  static final String PHYSICAL_DATA_SUFFIX = "/data";

  static final String VIEW_ID_PARAMETER = "viewId";

  static final String PATH_PARAMETER = "path";

  private IdeDataInternalPaths() {}

  static String dataHttpPath(EnvironmentId environmentId, UUID viewId, String rawPathAndQuery) {
    return dataPath(environmentId, viewId, rawPathAndQuery, DATA_HTTP_SUFFIX);
  }

  static String dataWebSocketPath(
      EnvironmentId environmentId, UUID viewId, String rawPathAndQuery) {
    return dataPath(environmentId, viewId, rawPathAndQuery, DATA_WS_SUFFIX);
  }

  /** 校验浏览器 view 基址之后的原始 path/query；内部 code-server 基址只由 Daemon 构造。 */
  static String viewPath(String rawPathAndQuery) {
    Objects.requireNonNull(rawPathAndQuery, "rawPathAndQuery");
    if (!isSafeViewPath(rawPathAndQuery)) {
      throw new IllegalArgumentException("IDE view path must stay within the view base path");
    }
    return rawPathAndQuery;
  }

  /**
   * 浏览器 path 必须是以 {@code /} 起始的 view 相对路径；拒绝点段、反斜杠与编码分隔符，避免 Daemon 拼接内部基址后被归一化 跳出 {@code
   * /__kk/ide/view/{viewId}/{epoch}}。
   */
  static boolean isSafeViewPath(String rawPathAndQuery) {
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

  static String physicalDataPath(EnvironmentId environmentId, String ticket) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(ticket, "ticket");
    return IdeInternalPaths.environmentPrefix(environmentId)
        + PHYSICAL_DATA_SUFFIX
        + "?ticket="
        + URLEncoder.encode(ticket, StandardCharsets.UTF_8);
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

  /** 由内部 origin 与 canonical path 构造内部 WS 地址；只接受裸 http(s) origin。 */
  static URI internalWebSocketUri(URI internalUrl, String canonicalPath) {
    if (internalUrl == null) {
      throw new IllegalArgumentException("owner route has no internal origin");
    }
    String scheme = internalUrl.getScheme();
    if (scheme == null) {
      throw new IllegalArgumentException("owner internal origin has no scheme");
    }
    String webSocketScheme =
        switch (scheme.toLowerCase(Locale.ROOT)) {
          case "http" -> "ws";
          case "https" -> "wss";
          default -> throw new IllegalArgumentException("owner internal origin must be http(s)");
        };
    if (internalUrl.getHost() == null
        || internalUrl.getUserInfo() != null
        || internalUrl.getQuery() != null
        || internalUrl.getFragment() != null
        || (internalUrl.getPath() != null
            && !internalUrl.getPath().isEmpty()
            && !"/".equals(internalUrl.getPath()))) {
      throw new IllegalArgumentException("owner internal origin must be a bare origin");
    }
    return URI.create(webSocketScheme + "://" + internalUrl.getAuthority() + canonicalPath);
  }

  /** 内部物理数据 WS 的 servlet 映射（Spring 路径模式）。 */
  static final String PHYSICAL_DATA_MAPPING =
      IdeInternalPaths.INTERNAL_IDE_PREFIX + "/*" + PHYSICAL_DATA_SUFFIX;

  /** 内部浏览器逻辑 WS 的 servlet 映射（Spring 路径模式）。 */
  static final String BROWSER_DATA_WS_MAPPING =
      IdeInternalPaths.INTERNAL_IDE_PREFIX + "/*" + DATA_WS_SUFFIX;
}
