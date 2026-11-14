package fun.fengwk.kkstudio.harness.model.provider.impl.openai;

import java.net.URI;
import java.util.Locale;

/**
 * OpenAI 协议族共享的 baseUrl 校验步骤。
 *
 * <p>每个协议按自己的策略顺序组合这些步骤，异常信息只描述约束，不回显 endpoint 或凭证。
 */
public final class OpenAiEndpointUri {

  private OpenAiEndpointUri() {}

  /** 解析非空 endpoint；语法非法统一报错且不回显原值。 */
  public static URI parse(String endpoint) {
    if (endpoint == null || endpoint.isBlank()) {
      throw new IllegalArgumentException("endpoint must not be blank");
    }
    try {
      return URI.create(endpoint);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("endpoint is not a valid URI");
    }
  }

  /** 校验并返回小写化的 http/https scheme。 */
  public static String requireHttpScheme(URI uri) {
    String scheme = uri.getScheme();
    if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
      throw new IllegalArgumentException("endpoint scheme must be http or https");
    }
    return scheme.toLowerCase(Locale.ROOT);
  }

  /** 校验并返回非空 raw authority。 */
  public static String requireAuthority(URI uri) {
    String rawAuthority = uri.getRawAuthority();
    if (rawAuthority == null || rawAuthority.isBlank()) {
      throw new IllegalArgumentException("endpoint must contain a valid host");
    }
    return rawAuthority;
  }

  /** 拒绝 user-info；部分协议的 authority 里残留 '@' 也视为 user-info。 */
  public static void requireNoUserInfo(URI uri, boolean rejectAuthorityAtSign) {
    if (uri.getUserInfo() != null
        || uri.getRawUserInfo() != null
        || (rejectAuthorityAtSign
            && uri.getRawAuthority() != null
            && uri.getRawAuthority().contains("@"))) {
      throw new IllegalArgumentException("endpoint must not contain user-info");
    }
  }

  public static void requireNoQuery(URI uri) {
    if (uri.getRawQuery() != null || uri.getQuery() != null) {
      throw new IllegalArgumentException("endpoint must not contain query");
    }
  }

  public static void requireNoFragment(URI uri) {
    if (uri.getRawFragment() != null || uri.getFragment() != null) {
      throw new IllegalArgumentException("endpoint must not contain fragment");
    }
  }

  public static void requireHost(URI uri) {
    if (uri.getHost() == null || uri.getHost().isBlank()) {
      throw new IllegalArgumentException("endpoint must contain a valid host");
    }
  }

  /** 去掉 base path 尾部斜杠后追加协议端点路径。 */
  public static String appendPath(URI uri, String suffix) {
    String rawPath = uri.getRawPath();
    if (rawPath == null) {
      rawPath = "";
    }
    while (rawPath.endsWith("/")) {
      rawPath = rawPath.substring(0, rawPath.length() - 1);
    }
    return rawPath + suffix;
  }

  /** 用规范化 scheme 与 raw authority 重建端点；不解析、不改写已转义路径。 */
  public static URI rebuild(String scheme, String rawAuthority, String path) {
    return URI.create(scheme + "://" + rawAuthority + path);
  }
}
