package fun.fengwk.kkstudio.plugin.minimaxmavis;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 诊断信息去敏。
 *
 * <p>Mavis 的请求 URL（renewal query）、header（{@code token}、{@code Authorization}）与响应 body 都可能直接携带凭据，
 * 因此任何进入错误消息、日志或响应的文本都必须先经过本类：替换已知秘密，并把内嵌 URL 退化为 {@code scheme://host[:port]/path}（丢弃
 * userinfo、query 与 fragment）。
 */
public final class MavisRedaction {

  /** 被替换秘密的占位文本。 */
  public static final String REDACTED = "[REDACTED]";

  private static final Pattern EMBEDDED_URL =
      Pattern.compile("(?:https?|minimax(?:-cn)?)://[^\\s'\"<>]+", Pattern.CASE_INSENSITIVE);

  private static final String INVALID_URL = "<invalid URL>";

  private MavisRedaction() {}

  /** 用占位文本替换全部非空秘密。 */
  public static String redact(String text, String... secrets) {
    if (text == null) {
      return "";
    }
    String output = text;
    for (String secret : secrets) {
      if (secret != null && !secret.isEmpty()) {
        output = output.replace(secret, REDACTED);
      }
    }
    return output;
  }

  /**
   * 退化为不含凭据的安全 URL：只保留 scheme、host、可选 port 与 path。
   *
   * <p>无法解析的输入返回 {@code <invalid URL>} 而不是原文。
   */
  public static String safeUrl(String url) {
    if (url == null || url.isBlank()) {
      return INVALID_URL;
    }
    URI parsed;
    try {
      parsed = new URI(url);
    } catch (URISyntaxException error) {
      return INVALID_URL;
    }
    String scheme = parsed.getScheme();
    String host = parsed.getHost();
    if (scheme == null || host == null) {
      return INVALID_URL;
    }
    String renderedHost = host.contains(":") ? "[" + host + "]" : host;
    String port = parsed.getPort() < 0 ? "" : ":" + parsed.getPort();
    String path = parsed.getRawPath() == null ? "" : parsed.getRawPath();
    return scheme + "://" + renderedHost + port + path;
  }

  /** 对可能包含服务端原文与内嵌 URL 的诊断文本执行 URL 退化与秘密替换。 */
  public static String diagnostic(String text, String... secrets) {
    if (text == null) {
      return "";
    }
    Matcher matcher = EMBEDDED_URL.matcher(text);
    StringBuilder output = new StringBuilder();
    while (matcher.find()) {
      matcher.appendReplacement(output, Matcher.quoteReplacement(safeUrl(matcher.group())));
    }
    matcher.appendTail(output);
    return redact(output.toString(), secrets);
  }
}
