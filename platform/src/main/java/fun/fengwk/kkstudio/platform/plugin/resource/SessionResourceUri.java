package fun.fengwk.kkstudio.platform.plugin.resource;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 会话 Resource URI 的规范解析：精确 {@code kkstudio:/resources/<canonical-uuid>}。
 *
 * <p>只接受完整且规范的形态（固定前缀、单个规范小写 UUID、无其余成分）。任何近似但非法的形态（大写 UUID、查询参数、多余路径、其它 scheme）都返回空，由调用方按自己的
 * 协议语义报错，绝不被宽容解析成某个 blob 而放大越权面。
 */
final class SessionResourceUri {

  /** 规范前缀。 */
  static final String PREFIX = "kkstudio:/resources/";

  /** 规范小写 UUID 的精确形态。 */
  private static final Pattern CANONICAL_UUID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  private SessionResourceUri() {}

  /** 严格解析为 blob id；不是规范形态时返回空。 */
  static Optional<UUID> parse(String value) {
    if (value == null || !value.startsWith(PREFIX)) {
      return Optional.empty();
    }
    String candidate = value.substring(PREFIX.length());
    if (!CANONICAL_UUID.matcher(candidate).matches()) {
      return Optional.empty();
    }
    UUID blobId = UUID.fromString(candidate);
    if (!blobId.toString().equals(candidate)) {
      return Optional.empty();
    }
    return Optional.of(blobId);
  }
}
