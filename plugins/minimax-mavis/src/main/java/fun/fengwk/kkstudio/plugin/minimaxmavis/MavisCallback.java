package fun.fengwk.kkstudio.plugin.minimaxmavis;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * MiniMax SSO 登录的 deep-link 回调。
 *
 * <p>回调形如 {@code minimax-cn://auth-callback?accessToken=...}；scheme 决定 region。解析是严格的：限制总长度与 query
 * 字段数，要求 authority 恰为 {@code auth-callback}、没有 path 与 fragment，且恰有一个非空 {@code accessToken}。任何不符合
 * 预期的输入都在发出网络请求之前失败，异常消息不会回显回调原文。
 */
public record MavisCallback(String accessToken, MavisRegion region) {

  private static final int MAX_CALLBACK_URL_CHARS = 16_384;
  private static final int MAX_CALLBACK_FIELDS = 32;
  private static final int MAX_ACCESS_TOKEN_CHARS = 8_192;
  private static final String AUTHORITY = "auth-callback";
  private static final String ACCESS_TOKEN_FIELD = "accessToken";
  private static final String ERROR_FIELD = "error";
  private static final String SCHEME_SEPARATOR = "://";

  public MavisCallback {
    if (accessToken == null || accessToken.isBlank()) {
      throw new MavisValidationException("MiniMax callback access token must not be empty");
    }
    if (region == null) {
      throw new MavisValidationException("MiniMax callback region must not be null");
    }
  }

  /** 严格解析 deep-link 回调，返回唯一 access token 与其 region。 */
  public static MavisCallback parse(String callbackUrl) {
    String normalized = normalize(callbackUrl);
    int separator = normalized.indexOf(SCHEME_SEPARATOR);
    if (separator <= 0) {
      throw invalidCallback();
    }
    MavisRegion region =
        MavisRegion.fromCallbackScheme(normalized.substring(0, separator))
            .orElseThrow(MavisCallback::invalidCallback);
    String remainder = normalized.substring(separator + SCHEME_SEPARATOR.length());
    int authorityEnd = authorityEnd(remainder);
    if (!AUTHORITY.equals(remainder.substring(0, authorityEnd))) {
      throw invalidCallback();
    }
    String query = queryOf(remainder.substring(authorityEnd));

    List<String> tokens = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    readFields(query, tokens, errors);
    if (!errors.isEmpty()) {
      throw new MavisAuthException("MiniMax login callback reported an error");
    }
    if (tokens.size() != 1) {
      throw new MavisValidationException(
          "callback must contain exactly one non-empty access token");
    }
    String token = tokens.get(0);
    if (token.isEmpty() || token.length() > MAX_ACCESS_TOKEN_CHARS) {
      throw new MavisValidationException(
          "callback must contain exactly one non-empty access token");
    }
    return new MavisCallback(token, region);
  }

  @Override
  public String toString() {
    return "MavisCallback[region=" + region.id() + ", accessToken=" + MavisRedaction.REDACTED + "]";
  }

  private static String normalize(String callbackUrl) {
    if (callbackUrl == null) {
      throw invalidCallback();
    }
    String normalized = callbackUrl.strip();
    if (normalized.length() > MAX_CALLBACK_URL_CHARS) {
      throw invalidCallback();
    }
    // WHATWG 解析忽略的 ASCII 空白，先按同样规则移除，再拒绝其余控制字符。
    String withoutUnsafeBytes = normalized.replace("\t", "").replace("\r", "").replace("\n", "");
    for (int index = 0; index < withoutUnsafeBytes.length(); index++) {
      char character = withoutUnsafeBytes.charAt(index);
      if (character < 0x20 || character == 0x7F) {
        throw invalidCallback();
      }
    }
    return withoutUnsafeBytes;
  }

  private static int authorityEnd(String remainder) {
    int end = remainder.length();
    for (int index = 0; index < remainder.length(); index++) {
      char character = remainder.charAt(index);
      if (character == '/' || character == '?' || character == '#') {
        end = index;
        break;
      }
    }
    return end;
  }

  private static String queryOf(String tail) {
    if (tail.isEmpty()) {
      return "";
    }
    if (tail.charAt(0) == '/') {
      throw invalidCallback();
    }
    if (tail.charAt(0) == '#') {
      throw invalidCallback();
    }
    if (tail.charAt(0) != '?') {
      throw invalidCallback();
    }
    String query = tail.substring(1);
    int fragment = query.indexOf('#');
    if (fragment >= 0) {
      throw invalidCallback();
    }
    return query;
  }

  private static void readFields(String query, List<String> tokens, List<String> errors) {
    if (query.isEmpty()) {
      return;
    }
    String[] fields = query.split("&", -1);
    if (fields.length > MAX_CALLBACK_FIELDS) {
      throw invalidCallback();
    }
    for (String field : fields) {
      int separator = field.indexOf('=');
      if (field.isEmpty() || separator < 0) {
        throw invalidCallback();
      }
      String name = decodeFormComponent(field.substring(0, separator));
      String value = decodeFormComponent(field.substring(separator + 1));
      if (ERROR_FIELD.equals(name)) {
        errors.add(value);
      } else if (ACCESS_TOKEN_FIELD.equals(name)) {
        tokens.add(value);
      }
    }
  }

  /** 按 form 语义解码一个 query 组件：{@code +} 表示空格，百分号转义按 UTF-8 解码。 */
  private static String decodeFormComponent(String value) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
    int index = 0;
    while (index < value.length()) {
      char character = value.charAt(index);
      if (character == '+') {
        bytes.write(' ');
        index++;
        continue;
      }
      if (character == '%' && index + 2 < value.length()) {
        int high = Character.digit(value.charAt(index + 1), 16);
        int low = Character.digit(value.charAt(index + 2), 16);
        if (high >= 0 && low >= 0) {
          bytes.write((high << 4) | low);
          index += 3;
          continue;
        }
      }
      bytes.writeBytes(String.valueOf(character).getBytes(StandardCharsets.UTF_8));
      index++;
    }
    return bytes.toString(StandardCharsets.UTF_8);
  }

  private static MavisValidationException invalidCallback() {
    return new MavisValidationException("invalid MiniMax callback URL");
  }
}
