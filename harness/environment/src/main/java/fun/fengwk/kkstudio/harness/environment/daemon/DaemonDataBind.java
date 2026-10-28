package fun.fengwk.kkstudio.harness.environment.daemon;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * DATA_BIND payload：一条受控 IDE 数据连接的代际、一次性票据与 Studio 同源入口。
 *
 * <p>ticket 是 43 字符无填充 base64url；streamUrl 只允许绝对 HTTP(S) 地址，且不得携带 userinfo、fragment 或控制字符。
 * “同源”由签发方按受控 Studio origin 最终校验，本 codec 只负责结构边界。
 */
public record DaemonDataBind(long generation, String ticket, URI streamUrl) {

  public static final String PATH = "/internal/environment-data";

  private static final Pattern TICKET = Pattern.compile("^[A-Za-z0-9_-]{43}$");
  private static final int MAX_STREAM_URL_CHARS = 2048;

  public DaemonDataBind {
    if (generation <= 0) {
      throw new IllegalArgumentException("generation must be a positive long");
    }
    if (ticket == null || !TICKET.matcher(ticket).matches()) {
      throw new IllegalArgumentException("ticket must be 43 base64url characters");
    }
    streamUrl = validateStreamUrl(streamUrl);
  }

  private static URI validateStreamUrl(URI value) {
    Objects.requireNonNull(value, "streamUrl");
    String raw = value.toString();
    if (raw.length() > MAX_STREAM_URL_CHARS) {
      throw new IllegalArgumentException("streamUrl is too long");
    }
    if (raw.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("streamUrl must not contain control characters");
    }
    if (!value.isAbsolute() || value.getScheme() == null || value.getRawAuthority() == null) {
      throw new IllegalArgumentException("streamUrl must be an absolute URI");
    }
    String scheme = value.getScheme();
    if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
      throw new IllegalArgumentException("streamUrl must use http or https");
    }
    if (value.getHost() == null) {
      throw new IllegalArgumentException("streamUrl must have a host");
    }
    if (value.getUserInfo() != null) {
      throw new IllegalArgumentException("streamUrl must not contain userinfo");
    }
    if (value.getFragment() != null) {
      throw new IllegalArgumentException("streamUrl must not contain a fragment");
    }
    if (value.getPort() < -1 || value.getPort() > 65535) {
      throw new IllegalArgumentException("streamUrl port is invalid");
    }
    String path = value.getRawPath();
    if (path == null || path.isEmpty() || !path.startsWith("/")) {
      throw new IllegalArgumentException("streamUrl must have an absolute path");
    }
    return value;
  }

  /** 严格解析 wire 上的绝对 URL；解析结果仍需经紧凑构造器校验。 */
  public static URI parseStreamUrl(String raw) {
    if (raw == null) {
      throw new IllegalArgumentException("streamUrl must not be null");
    }
    try {
      return new URI(raw);
    } catch (URISyntaxException error) {
      throw new IllegalArgumentException("streamUrl must be a valid URI", error);
    }
  }

  /** 受控 Studio 同源判定；路径由数据面自身约束，不接受跨 origin 地址。 */
  public boolean isSameOrigin(URI studioOrigin) {
    Objects.requireNonNull(studioOrigin, "studioOrigin");
    return streamUrl.getScheme().equalsIgnoreCase(studioOrigin.getScheme())
        && streamUrl.getHost().equalsIgnoreCase(studioOrigin.getHost())
        && effectivePort(streamUrl) == effectivePort(studioOrigin);
  }

  private static int effectivePort(URI uri) {
    if (uri.getPort() != -1) {
      return uri.getPort();
    }
    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }
}
