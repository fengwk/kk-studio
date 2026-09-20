package fun.fengwk.kkstudio.plugin.minimaxmavis;

import java.util.Locale;
import java.util.Optional;

/**
 * MiniMax Mavis 的两个官方 region。
 *
 * <p>origin 与登录入口都是固定协议事实，不支持自定义 base URL；region 只决定 origin、登录链接与登录回调的 deep-link scheme。
 */
public enum MavisRegion {
  /** 中国大陆官方 origin，deep-link scheme 为 {@code minimax-cn}。 */
  CN("cn", "https://agent.minimaxi.com", "minimax-cn"),
  /** 国际官方 origin，deep-link scheme 为 {@code minimax}。 */
  EN("en", "https://agent.minimax.io", "minimax");

  private static final String LOGIN_PATH = "/login?sso=1&download_source=default";

  private final String id;
  private final String baseUrl;
  private final String callbackScheme;

  MavisRegion(String id, String baseUrl, String callbackScheme) {
    this.id = id;
    this.baseUrl = baseUrl;
    this.callbackScheme = callbackScheme;
  }

  /** 稳定 region 标识，只允许 {@code cn} 或 {@code en}。 */
  public String id() {
    return id;
  }

  /** 官方 HTTPS origin，不带尾随斜杠。 */
  public String baseUrl() {
    return baseUrl;
  }

  /** 该 region 登录回调使用的 deep-link scheme。 */
  public String callbackScheme() {
    return callbackScheme;
  }

  /** 用户需要打开的官方 SSO 登录链接。 */
  public String loginUrl() {
    return baseUrl + LOGIN_PATH;
  }

  /** 严格解析 region 标识；其他取值一律拒绝，不做默认回退。 */
  public static MavisRegion parse(String value) {
    if (value == null) {
      throw new MavisValidationException("region must be cn or en");
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    for (MavisRegion region : values()) {
      if (region.id.equals(normalized)) {
        return region;
      }
    }
    throw new MavisValidationException("region must be cn or en");
  }

  /** 按 deep-link scheme 解析 region；未知 scheme 返回空。 */
  public static Optional<MavisRegion> fromCallbackScheme(String scheme) {
    if (scheme == null) {
      return Optional.empty();
    }
    String normalized = scheme.toLowerCase(Locale.ROOT);
    for (MavisRegion region : values()) {
      if (region.callbackScheme.equals(normalized)) {
        return Optional.of(region);
      }
    }
    return Optional.empty();
  }
}
