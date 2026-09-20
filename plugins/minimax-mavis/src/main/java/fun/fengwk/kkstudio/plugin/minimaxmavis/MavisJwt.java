package fun.fengwk.kkstudio.plugin.minimaxmavis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Mavis access token 的本地 JWT 解析。
 *
 * <p>只读取数值 {@code exp} 作为本地时限；token 未经签名校验，因此这里的任何结果都不能当作身份事实，只能用于「本地已过期则 不再发送请求」这一失败前置判断。
 */
public final class MavisJwt {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Pattern BASE64URL = Pattern.compile("[A-Za-z0-9_-]+");
  private static final int JWT_SEGMENT_COUNT = 3;

  private MavisJwt() {}

  /** 解析 JWT payload 中的数值 {@code exp}；任何不符合预期的 token 都返回空而不是抛错。 */
  public static Optional<Instant> numericExpiresAt(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    String[] segments = token.split("\\.", -1);
    if (segments.length != JWT_SEGMENT_COUNT || !BASE64URL.matcher(segments[1]).matches()) {
      return Optional.empty();
    }
    byte[] payload = decodeBase64Url(segments[1]);
    if (payload == null) {
      return Optional.empty();
    }
    JsonNode decoded;
    try {
      decoded = MAPPER.readTree(payload);
    } catch (IOException error) {
      return Optional.empty();
    }
    if (decoded == null || !decoded.isObject()) {
      return Optional.empty();
    }
    JsonNode exp = decoded.get("exp");
    if (exp == null || !exp.isNumber()) {
      return Optional.empty();
    }
    long seconds = exp.longValue();
    try {
      return Optional.of(Instant.ofEpochSecond(seconds));
    } catch (DateTimeException error) {
      return Optional.empty();
    }
  }

  private static byte[] decodeBase64Url(String value) {
    String padded = value + "=".repeat((4 - value.length() % 4) % 4);
    try {
      return Base64.getUrlDecoder().decode(padded);
    } catch (IllegalArgumentException error) {
      return null;
    }
  }
}
