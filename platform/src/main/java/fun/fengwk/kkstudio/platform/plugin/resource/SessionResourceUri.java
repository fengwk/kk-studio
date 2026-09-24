package fun.fengwk.kkstudio.platform.plugin.resource;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 平台托管资源 URI 的规范形态：精确 {@code kkstudio:/resources/<canonical-uuid>}。
 *
 * <p>只接受完整且规范的形态（固定前缀、单个规范小写 UUID、无其余成分）。任何近似但非法的形态（大写 UUID、查询参数、多余路径、其它 scheme）都返回空，由调用方按自己的
 * 协议语义报错，绝不被宽容解析成某个 blob 而放大越权面。
 *
 * <p>URI 只是指向全局 Blob 的标识，不是权限凭据：读取与公开都必须另经 owner 引用与 Blob 状态核验（Session 引用或 Issue 已发布证据）。
 */
public final class SessionResourceUri {

  /** 规范前缀。 */
  public static final String PREFIX = "kkstudio:/resources/";

  /** 规范小写 UUID 的精确形态。 */
  private static final Pattern CANONICAL_UUID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  /**
   * 自由文本中内嵌的规范 URI：前缀前不得紧邻 URL/标识符字符（避免把更长的 URL 片段误认成平台 URI），UUID 之后不得再跟 hex 或连字符（避免截断更长 token）。
   */
  private static final Pattern EMBEDDED =
      Pattern.compile(
          "(?<![A-Za-z0-9._/-])" + PREFIX + CANONICAL_UUID.pattern() + "(?![0-9a-fA-F-])");

  private SessionResourceUri() {}

  /** 严格解析为 blob id；不是规范形态时返回空。 */
  public static Optional<UUID> parse(String value) {
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

  /** 规范 URI 形态（由 blob id 派生，绝不持久化）。 */
  public static String format(UUID blobId) {
    return PREFIX + Objects.requireNonNull(blobId, "blobId");
  }

  /** 自由文本中出现的全部规范 URI 指向的 blob id，按首次出现顺序去重。 */
  public static List<UUID> scan(String text) {
    if (text == null || text.isEmpty()) {
      return List.of();
    }
    LinkedHashSet<UUID> blobIds = new LinkedHashSet<>();
    Matcher matcher = EMBEDDED.matcher(text);
    while (matcher.find()) {
      parse(matcher.group()).ifPresent(blobIds::add);
    }
    return List.copyOf(blobIds);
  }
}
