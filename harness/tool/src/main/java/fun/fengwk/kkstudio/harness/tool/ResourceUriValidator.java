package fun.fengwk.kkstudio.harness.tool;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * ResourceRef 的包私有 URI 校验器：只依赖 JDK，不做任何网络/存储访问。
 *
 * <p>先做全局约束（UTF-8 字节上限、控制字符/反斜杠、URI.create + toASCIIString 一致性、百分号转义规范），再按 scheme 做精确校验；{@code
 * data} URI 在构造时解码载荷并验证 size/sha，其余 scheme 不做任何解引用。
 */
final class ResourceUriValidator {

  /** RFC 4648 规范 base64（含正确 padding）。 */
  private static final Pattern CANONICAL_BASE64 =
      Pattern.compile("(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?");

  private ResourceUriValidator() {}

  /**
   * 校验 URI 及按 scheme 的 size/sha 要求。
   *
   * @return data URI 的解码字节，其余 scheme 返回 null
   */
  static byte[] validate(String uri, String mediaType, Long size, String sha256) {
    if (uri.length() > ResourceRef.MAX_URI_UTF8_BYTES) {
      throw new IllegalArgumentException(
          "uri must not exceed " + ResourceRef.MAX_URI_UTF8_BYTES + " UTF-8 bytes");
    }
    for (int index = 0; index < uri.length(); index++) {
      char character = uri.charAt(index);
      if (character > 0x7f) {
        throw new IllegalArgumentException("uri must be canonical ASCII");
      }
      if (character == '\\' || Character.isISOControl(character)) {
        throw new IllegalArgumentException("uri must not contain control characters or backslash");
      }
    }
    URI parsed;
    try {
      parsed = URI.create(uri);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("uri is not a valid URI", error);
    }
    if (!parsed.toASCIIString().equals(uri)) {
      throw new IllegalArgumentException("uri must be canonical ASCII");
    }
    validatePercentEscapes(uri);
    String scheme = parsed.getScheme();
    if (scheme == null) {
      throw new IllegalArgumentException("uri must declare an absolute scheme");
    }
    return switch (scheme) {
      case "data" -> validateData(parsed, mediaType, size, sha256);
      case "file" -> {
        validateFile(parsed, size, sha256);
        yield null;
      }
      case "s3" -> {
        validateS3(parsed, size, sha256);
        yield null;
      }
      case "http", "https" -> {
        validateHttp(parsed, scheme);
        yield null;
      }
      default -> throw new IllegalArgumentException("unsupported uri scheme: " + scheme);
    };
  }

  /** 全局百分号转义规则：大写 hex、不得编码 NUL / 反斜杠 / 斜杠 / unreserved。 */
  private static void validatePercentEscapes(String uri) {
    for (int index = 0; index < uri.length(); index++) {
      char current = uri.charAt(index);
      if (current != '%') {
        continue;
      }
      if (index + 2 >= uri.length()
          || !isUpperHex(uri.charAt(index + 1))
          || !isUpperHex(uri.charAt(index + 2))) {
        throw new IllegalArgumentException("percent escapes must use uppercase hex digits");
      }
      int value = hexValue(uri.charAt(index + 1)) * 16 + hexValue(uri.charAt(index + 2));
      if (value == 0) {
        throw new IllegalArgumentException("percent escapes must not encode NUL");
      }
      if (value == '/' || value == '\\') {
        throw new IllegalArgumentException("percent escapes must not encode slash or backslash");
      }
      if (isUnreserved(value)) {
        throw new IllegalArgumentException("percent escapes must not encode unreserved characters");
      }
      index += 2;
    }
  }

  /**
   * data URI：要求精确 header {@code <mediaType>,} 或 {@code <mediaType>;base64,}，规范 percent/base64
   * 载荷，构造时 解码并验证 size/sha；解码字节不得超过 {@link ResourceRef#MAX_DATA_DECODED_BYTES}。
   *
   * <p>非 base64 载荷的 frozen 表示：percent 解码后按字节重新编码并精确相等——原始 ASCII 只能是 RFC unreserved 与 原始 {@code
   * '/'}（编码 slash 仍被全局规则禁止）；其余任何可表示字节都必须是大写 {@code %XX}（如逗号/分号/冒号/问号/ 井号/空格）。因此原始 {@code ,} 与
   * {@code %2C} 不能同时通过（选择编码形式）；无法按此规则表示的字节（如 NUL）只能走 base64 形式。query/fragment 一律拒绝。
   */
  private static byte[] validateData(URI parsed, String mediaType, Long size, String sha256) {
    requireSizeAndSha(size, sha256, "data");
    if (parsed.getQuery() != null || parsed.getFragment() != null) {
      throw new IllegalArgumentException("data uri must not carry query or fragment");
    }
    String ssp = parsed.getRawSchemeSpecificPart();
    int comma = ssp.indexOf(',');
    if (comma < 0) {
      throw new IllegalArgumentException("data uri must be <mediaType>,<payload>");
    }
    String header = ssp.substring(0, comma);
    String payload = ssp.substring(comma + 1);
    boolean base64 = header.endsWith(";base64");
    String expectedHeader = base64 ? mediaType + ";base64" : mediaType;
    if (!header.equals(expectedHeader)) {
      throw new IllegalArgumentException(
          "data uri header must be exactly <mediaType>, or <mediaType>;base64,");
    }
    byte[] decoded;
    if (base64) {
      if (!CANONICAL_BASE64.matcher(payload).matches()) {
        throw new IllegalArgumentException("data uri payload is not canonical base64");
      }
      decoded = Base64.getDecoder().decode(payload);
      if (!Base64.getEncoder().encodeToString(decoded).equals(payload)) {
        throw new IllegalArgumentException("data uri payload has non-canonical base64 pad bits");
      }
    } else {
      decoded = percentDecode(payload);
      if (!canonicalReencode(decoded).equals(payload)) {
        throw new IllegalArgumentException(
            "data uri payload must use canonical percent encoding (raw ASCII limited to RFC "
                + "unreserved and '/', everything else uppercase %XX)");
      }
    }
    if (decoded.length > ResourceRef.MAX_DATA_DECODED_BYTES) {
      throw new IllegalArgumentException(
          "data uri decoded payload must not exceed "
              + ResourceRef.MAX_DATA_DECODED_BYTES
              + " bytes");
    }
    if (size.longValue() != decoded.length) {
      throw new IllegalArgumentException("data uri size must equal decoded payload length");
    }
    if (!sha256.equals(sha256Hex(decoded))) {
      throw new IllegalArgumentException("data uri sha256 must match decoded payload digest");
    }
    return decoded;
  }

  /** file URI：精确 {@code file:///...}、无 authority/query/fragment、恰好一个前导斜杠、非空绝对路径、无 dot/空 segment。 */
  private static void validateFile(URI parsed, Long size, String sha256) {
    requireSizeAndSha(size, sha256, "file");
    // java.net.URI 对空 authority 返回 null，无法区分 file:/x 与 file:///x；用 raw SSP 的前导 "///" 精确判定。
    String ssp = parsed.getRawSchemeSpecificPart();
    if (ssp == null || !ssp.startsWith("///")) {
      throw new IllegalArgumentException("file uri must use exactly file:/// with empty authority");
    }
    if (parsed.getQuery() != null || parsed.getFragment() != null) {
      throw new IllegalArgumentException("file uri must not carry query or fragment");
    }
    String rawPath = parsed.getRawPath();
    if (rawPath == null || !rawPath.startsWith("/") || rawPath.startsWith("//")) {
      throw new IllegalArgumentException("file uri must declare exactly one leading slash");
    }
    if (rawPath.indexOf('%') >= 0) {
      throw new IllegalArgumentException("file uri path must not use percent encoding");
    }
    if (rawPath.length() == 1) {
      throw new IllegalArgumentException("file uri path must not be empty");
    }
    String[] segments = rawPath.split("/", -1);
    for (int index = 1; index < segments.length; index++) {
      String segment = segments[index];
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        throw new IllegalArgumentException("file uri path segments must not be empty, '.' or '..'");
      }
    }
  }

  /** s3 URI：无 userinfo/port/query/fragment、小写合法 bucket、非空 key、无 dot/空 segment、原始 key 无百分号。 */
  private static void validateS3(URI parsed, Long size, String sha256) {
    requireSizeAndSha(size, sha256, "s3");
    if (parsed.getUserInfo() != null || parsed.getPort() != -1) {
      throw new IllegalArgumentException("s3 uri must not carry userinfo or port");
    }
    if (parsed.getQuery() != null || parsed.getFragment() != null) {
      throw new IllegalArgumentException("s3 uri must not carry query or fragment");
    }
    // 用 raw authority 而非 getHost()：getHost() 对不合法 reg-name（如连续点）返回 null，会掩盖 bucket 具体错误。
    validateBucket(parsed.getRawAuthority());
    String rawPath = parsed.getRawPath();
    if (rawPath == null || !rawPath.startsWith("/")) {
      throw new IllegalArgumentException("s3 uri must declare a key path");
    }
    String key = rawPath.substring(1);
    if (key.isEmpty()) {
      throw new IllegalArgumentException("s3 uri key must not be empty");
    }
    if (key.indexOf('%') >= 0) {
      throw new IllegalArgumentException("s3 uri key must not use percent encoding");
    }
    // URI.create + toASCIIString 一致性已把原始 key 字符限制在保守 ASCII（pchar + '/'）。
    for (String segment : key.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        throw new IllegalArgumentException("s3 uri key segments must not be empty, '.' or '..'");
      }
    }
  }

  /** http/https URI：非空小写 host、无 userinfo/query/fragment、拒绝显式默认端口、路径无 dot segment；不承诺 SSRF 安全。 */
  private static void validateHttp(URI parsed, String scheme) {
    if (parsed.getUserInfo() != null) {
      throw new IllegalArgumentException("http/https uri must not carry userinfo");
    }
    if (parsed.getQuery() != null || parsed.getFragment() != null) {
      throw new IllegalArgumentException("http/https uri must not carry query or fragment");
    }
    String host = parsed.getHost();
    if (host == null || host.isEmpty()) {
      throw new IllegalArgumentException("http/https uri must declare a non-empty host");
    }
    if (!host.equals(host.toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException("http/https uri host must be lowercase");
    }
    int port = parsed.getPort();
    if (port == 0 || port > 65535) {
      throw new IllegalArgumentException("http/https uri port must be between 1 and 65535");
    }
    String expectedAuthority = port == -1 ? host : host + ":" + port;
    if (!expectedAuthority.equals(parsed.getRawAuthority())) {
      throw new IllegalArgumentException(
          "http/https uri authority must use canonical host and port");
    }
    if (port != -1
        && ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443))) {
      throw new IllegalArgumentException("http/https uri must not declare its default port");
    }
    String rawPath = parsed.getRawPath();
    if (rawPath != null) {
      for (String segment : rawPath.split("/", -1)) {
        if (segment.equals(".") || segment.equals("..")) {
          throw new IllegalArgumentException("http/https uri path must not contain dot segments");
        }
      }
    }
  }

  /** 小写 DNS 风格 bucket：3-63 字符、字母/数字开头结尾、仅小写字母/数字/连字符/点、无连续点、不得形如 IP 地址。 */
  private static void validateBucket(String bucket) {
    if (bucket == null || bucket.length() < 3 || bucket.length() > 63) {
      throw new IllegalArgumentException("s3 uri bucket must be 3-63 characters");
    }
    for (int index = 0; index < bucket.length(); index++) {
      char character = bucket.charAt(index);
      boolean legal =
          (character >= 'a' && character <= 'z')
              || (character >= '0' && character <= '9')
              || character == '-'
              || character == '.';
      if (!legal) {
        throw new IllegalArgumentException(
            "s3 uri bucket must be lowercase letters, digits, '-' or '.'");
      }
    }
    if (!isLowerAlnum(bucket.charAt(0)) || !isLowerAlnum(bucket.charAt(bucket.length() - 1))) {
      throw new IllegalArgumentException("s3 uri bucket must start and end with a letter or digit");
    }
    if (bucket.contains("..")) {
      throw new IllegalArgumentException("s3 uri bucket must not contain consecutive dots");
    }
    String[] segments = bucket.split("\\.");
    boolean ipLike = segments.length > 1;
    for (String segment : segments) {
      if (!isLowerAlnum(segment.charAt(0)) || !isLowerAlnum(segment.charAt(segment.length() - 1))) {
        throw new IllegalArgumentException(
            "s3 uri bucket labels must start and end with a letter or digit");
      }
      if (!segment.chars().allMatch(Character::isDigit)) {
        ipLike = false;
      }
    }
    if (ipLike) {
      throw new IllegalArgumentException("s3 uri bucket must not be formatted as an IP address");
    }
  }

  private static void requireSizeAndSha(Long size, String sha256, String scheme) {
    if (size == null || sha256 == null) {
      throw new IllegalArgumentException(scheme + " uri requires non-null size and sha256");
    }
  }

  private static boolean isUnreserved(int value) {
    return (value >= 'a' && value <= 'z')
        || (value >= 'A' && value <= 'Z')
        || (value >= '0' && value <= '9')
        || value == '-'
        || value == '.'
        || value == '_'
        || value == '~';
  }

  private static boolean isUpperHex(char character) {
    return (character >= '0' && character <= '9') || (character >= 'A' && character <= 'F');
  }

  private static int hexValue(char character) {
    if (character >= '0' && character <= '9') {
      return character - '0';
    }
    return character - 'A' + 10;
  }

  /** 百分号解码为字节；转义已通过 {@link #validatePercentEscapes} 校验，其余字符为 ASCII。 */
  private static byte[] percentDecode(String value) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (current == '%') {
        bytes.write(hexValue(value.charAt(index + 1)) * 16 + hexValue(value.charAt(index + 2)));
        index += 2;
      } else {
        bytes.write(current);
      }
    }
    return bytes.toByteArray();
  }

  /** 按 frozen 规则重新编码：unreserved 与 {@code '/'} 原样，其余字节大写 {@code %XX}。 */
  private static String canonicalReencode(byte[] decoded) {
    StringBuilder canonical = new StringBuilder(decoded.length);
    for (byte value : decoded) {
      int b = value & 0xff;
      if (isUnreserved(b) || b == '/') {
        canonical.append((char) b);
      } else {
        canonical.append('%');
        canonical.append(UPPER_HEX.charAt(b >> 4));
        canonical.append(UPPER_HEX.charAt(b & 0xf));
      }
    }
    return canonical.toString();
  }

  private static final String UPPER_HEX = "0123456789ABCDEF";

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 unavailable", error);
    }
  }

  private static boolean isLowerAlnum(char character) {
    return (character >= 'a' && character <= 'z') || (character >= '0' && character <= '9');
  }
}
