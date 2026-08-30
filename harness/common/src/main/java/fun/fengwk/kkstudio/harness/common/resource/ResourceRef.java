package fun.fengwk.kkstudio.harness.common.resource;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 不可变的规范 Resource URI 引用。
 *
 * <p>只允许 {@code data} / {@code file} / {@code s3} / {@code https} / {@code http} 五种 scheme；构造时执行全部
 * 域约束与规范校验（含 data URI 解码与 size/sha 验证），保证 codec 反序列化与直接构造得到同样的严格结果。
 *
 * <p>非 base64 data 载荷使用 frozen 表示：percent 解码后按字节重新编码并精确相等——原始 ASCII 只能是 RFC unreserved 与原始 {@code
 * '/'}，其余任何可表示字节都必须是大写 {@code %XX}（原始逗号/分号/冒号/问号/井号被拒绝，其编码形式被接受； 编码 slash/NUL
 * 仍被全局规则禁止）；无法按此规则表示的字节只能走 {@code ;base64,} 形式。
 */
public record ResourceRef(String uri, String mediaType, String name, Long size, String sha256) {

  /** URI 的 UTF-8 字节上限；data URI 在解码载荷前即适用。 */
  public static final int MAX_URI_UTF8_BYTES = 131072;

  /** data URI 解码后的字节上限。 */
  public static final int MAX_DATA_DECODED_BYTES = 65536;

  /** mediaType 的 ASCII 字节上限。 */
  public static final int MAX_MEDIA_TYPE_ASCII_BYTES = 255;

  /** name 的 UTF-8 字节上限。 */
  public static final int MAX_NAME_UTF8_BYTES = 512;

  /** ResourceResultContent preview 的 UTF-8 字节上限。 */
  public static final int MAX_PREVIEW_UTF8_BYTES = 16384;

  private static final Pattern MEDIA_TYPE_PATTERN =
      Pattern.compile("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+");
  private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");

  public ResourceRef {
    if (uri == null) {
      throw new IllegalArgumentException("uri must not be null");
    }
    if (mediaType == null || !MEDIA_TYPE_PATTERN.matcher(mediaType).matches()) {
      throw new IllegalArgumentException(
          "mediaType must be canonical lowercase type/subtype without parameters");
    }
    if (mediaType.length() > MAX_MEDIA_TYPE_ASCII_BYTES) {
      throw new IllegalArgumentException(
          "mediaType must not exceed " + MAX_MEDIA_TYPE_ASCII_BYTES + " ASCII bytes");
    }
    if (name != null) {
      if (name.isBlank()) {
        throw new IllegalArgumentException("name must not be blank");
      }
      if (name.codePoints().anyMatch(Character::isISOControl)) {
        throw new IllegalArgumentException("name must not contain control characters");
      }
      if (utf8Length(name, "name") > MAX_NAME_UTF8_BYTES) {
        throw new IllegalArgumentException(
            "name must not exceed " + MAX_NAME_UTF8_BYTES + " UTF-8 bytes");
      }
    }
    if (size != null && size < 0) {
      throw new IllegalArgumentException("size must not be negative");
    }
    if (sha256 != null && !SHA256_PATTERN.matcher(sha256).matches()) {
      throw new IllegalArgumentException("sha256 must be 64 lowercase hex digits");
    }
    // URI 校验（含 data 解码与 size/sha 验证）；data URI 返回解码字节，其余返回 null。
    ResourceUriValidator.validate(uri, mediaType, size, sha256);
  }

  /** 严格计算 UTF-8 字节数；拒绝 Java String 中未配对的代理项，避免静默替换后突破协议边界。 */
  public static int utf8Length(String value, String name) {
    try {
      return StandardCharsets.UTF_8
          .newEncoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .encode(CharBuffer.wrap(value))
          .remaining();
    } catch (CharacterCodingException error) {
      throw new IllegalArgumentException(name + " must be valid Unicode", error);
    }
  }

  /**
   * 精确计算 UTF-8 字节数但不超过 {@code maxBytes}：一旦累计超过上限立即停止扫描并返回当前计数（必然 ≥ {@code maxBytes} +
   * 1），全程不分配完整编码缓冲。与 {@link #utf8Length} 相同的严格代理项语义。
   */
  public static int utf8LengthUpTo(String value, String name, int maxBytes) {
    if (maxBytes < 0) {
      throw new IllegalArgumentException("maxBytes must not be negative");
    }
    int count = 0;
    for (int index = 0; index < value.length() && count <= maxBytes; index++) {
      char c = value.charAt(index);
      if (c < 0x80) {
        count++;
      } else if (c < 0x800) {
        count += 2;
      } else if (Character.isHighSurrogate(c)) {
        if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
          throw new IllegalArgumentException(name + " must be valid Unicode");
        }
        count += 4;
        index++;
      } else if (Character.isLowSurrogate(c)) {
        throw new IllegalArgumentException(name + " must be valid Unicode");
      } else {
        count += 3;
      }
    }
    return count;
  }
}
