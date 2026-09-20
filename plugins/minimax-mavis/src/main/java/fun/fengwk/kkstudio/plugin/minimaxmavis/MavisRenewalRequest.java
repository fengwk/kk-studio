package fun.fengwk.kkstudio.plugin.minimaxmavis;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 桌面 renewal 请求：参数、desktop 签名与 header。
 *
 * <p>MiniMax 没有 OAuth {@code refresh_token}；续期是用当前 access token 调一次 {@code POST
 * /v1/api/user/renewal}。该请求的 query、{@code yy}（请求指纹）与 {@code x-signature} 是私有桌面协议事实，
 * 必须与官方桌面客户端逐字节一致，因此这里的编码、时间与时区处理都按观测行为实现：
 *
 * <ul>
 *   <li>参数与 query 使用 {@code URLSearchParams} 风格编码（空格为 {@code +}，{@code ~} 为 {@code %7E}）；
 *   <li>{@code yy} 的输入是把 path+query 按 {@code safe="~()*!.'"} 再编码一次，再拼接固定分隔符与毫秒摘要后做 MD5；
 *   <li>{@code timezone_offset} 是请求时刻的本地 UTC 偏移秒数（含夏令时），而不是固定时区；
 *   <li>摘要算法是网关协议约定的 MD5，不是本项目的安全选择。
 * </ul>
 *
 * <p>请求 URL 的 query 与 header 都直接携带 token，因此 {@link #toString()} 只输出退化后的安全 URL。
 */
public record MavisRenewalRequest(String url, Map<String, String> headers) {

  /** 该私有端点的固定路径。 */
  public static final String PATH = "/v1/api/user/renewal";

  private static final String DESKTOP_SIGNATURE_CONSTANT = "I*7Cf%WZ#S&%1RlZJ&C2";
  private static final String YY_SAFE_CHARACTERS = "_.-~()*!'";
  private static final char[] LOWER_HEX = "0123456789abcdef".toCharArray();
  private static final char[] UPPER_HEX = "0123456789ABCDEF".toCharArray();
  private static final String USER_AGENT = "MiniMaxAgent";

  public MavisRenewalRequest {
    if (url == null || url.isBlank()) {
      throw new MavisValidationException("renewal url must not be empty");
    }
    headers = Map.copyOf(headers);
  }

  /**
   * 按参考实现逐字节构造一次 renewal 请求。
   *
   * @param credential 当前凭据；region 决定 origin 与 sys_language，token 与 clientUuid 进入 query
   * @param now 请求时刻，决定 {@code unix}、{@code x-timestamp}、时间偏移与签名
   * @param zoneId 请求时刻的本地时区，用于计算 {@code timezone_offset}
   * @param deviceId 会话级设备 id
   * @param osName 已收敛的桌面操作系统名
   */
  public static MavisRenewalRequest sign(
      MavisCredential credential, Instant now, ZoneId zoneId, String deviceId, String osName) {
    long nowMillis = now.toEpochMilli();
    String language = credential.region() == MavisRegion.EN ? "en" : "zh";
    Map<String, String> parameters = new LinkedHashMap<>();
    parameters.put("device_platform", "web");
    parameters.put("biz_id", "3");
    parameters.put("app_id", "3001");
    parameters.put("version_code", "22201");
    parameters.put("unix", Long.toString(nowMillis));
    parameters.put(
        "timezone_offset", Integer.toString(zoneId.getRules().getOffset(now).getTotalSeconds()));
    parameters.put("is_desktop", "1");
    parameters.put("desktop_version", "");
    parameters.put("sys_language", language);
    parameters.put("lang", language);
    parameters.put("uuid", credential.clientUuid());
    parameters.put("device_id", deviceId);
    parameters.put("os_name", osName);
    parameters.put("browser_name", "unknown");
    parameters.put("user_id", "0");
    parameters.put("token", credential.token());
    parameters.put("client", "desktop");

    StringBuilder query = new StringBuilder();
    for (Map.Entry<String, String> parameter : parameters.entrySet()) {
      if (!query.isEmpty()) {
        query.append('&');
      }
      query
          .append(formEncode(parameter.getKey()))
          .append('=')
          .append(formEncode(parameter.getValue()));
    }
    String pathWithQuery = PATH + "?" + query;
    String yy =
        digest(
            quoteForSignature(pathWithQuery) + "_{}" + digest(Long.toString(nowMillis)) + "ooui");
    String timestamp = Long.toString(Math.floorDiv(nowMillis, 1000));

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Content-Type", "application/json");
    headers.put("User-Agent", USER_AGENT);
    headers.put("token", credential.token());
    headers.put("yy", yy);
    headers.put("x-timestamp", timestamp);
    headers.put("x-signature", digest(timestamp + DESKTOP_SIGNATURE_CONSTANT));
    return new MavisRenewalRequest(credential.region().baseUrl() + pathWithQuery, headers);
  }

  @Override
  public String toString() {
    return "MavisRenewalRequest[url=" + MavisRedaction.safeUrl(url) + "]";
  }

  /** {@code URLSearchParams} 风格编码：空格为 {@code +}，{@code ~} 为 {@code %7E}。 */
  private static String formEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /** {@code yy} 输入的二次编码：只保留字母数字与 {@code _. - ~ ( ) * ! . '}。 */
  private static String quoteForSignature(String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    StringBuilder output = new StringBuilder(bytes.length * 2);
    for (byte raw : bytes) {
      int character = raw & 0xFF;
      if (isSignatureSafe(character)) {
        output.append((char) character);
      } else {
        output
            .append('%')
            .append(UPPER_HEX[(character >> 4) & 0xF])
            .append(UPPER_HEX[character & 0xF]);
      }
    }
    return output.toString();
  }

  private static boolean isSignatureSafe(int character) {
    boolean alphanumeric =
        (character >= 'A' && character <= 'Z')
            || (character >= 'a' && character <= 'z')
            || (character >= '0' && character <= '9');
    return alphanumeric || YY_SAFE_CHARACTERS.indexOf(character) >= 0;
  }

  private static String digest(String value) {
    try {
      byte[] hash = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder output = new StringBuilder(hash.length * 2);
      for (byte raw : hash) {
        output.append(LOWER_HEX[(raw >> 4) & 0xF]).append(LOWER_HEX[raw & 0xF]);
      }
      return output.toString();
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("MD5 unavailable", error);
    }
  }
}
