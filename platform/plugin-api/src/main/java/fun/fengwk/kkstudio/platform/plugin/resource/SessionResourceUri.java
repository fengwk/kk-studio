package fun.fengwk.kkstudio.platform.plugin.resource;

import fun.fengwk.kkstudio.common.id.Uuids;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 平台托管资源 URI 的规范形态：精确 {@code vfs:/resources/<canonical-uuid>}。
 *
 * <p>只接受完整且规范的形态（固定前缀、单个规范小写 UUID、无其余成分）。任何近似但非法的形态（大写 UUID、查询参数、多余路径、其它 scheme）都返回空，由调用方按自己的
 * 协议语义报错，绝不被宽容解析成某个 blob 而放大越权面。
 *
 * <p>URI 只是指向全局 Blob 的标识，不是权限凭据：读取与公开都必须另经 owner 引用与 Blob 状态核验（Session 引用或 Issue 已发布证据）。
 */
public final class SessionResourceUri {

  /** 规范前缀。 */
  public static final String PREFIX = "vfs:/resources/";

  private SessionResourceUri() {}

  /** 严格解析为 blob id；不是规范形态时返回空。 */
  public static Optional<UUID> parse(String value) {
    if (value == null || !value.startsWith(PREFIX)) {
      return Optional.empty();
    }
    String candidate = value.substring(PREFIX.length());
    try {
      return Optional.of(Uuids.parseCanonical(candidate, "blobId"));
    } catch (IllegalArgumentException error) {
      return Optional.empty();
    }
  }

  /** 规范 URI 形态（由 blob id 派生，绝不持久化）。 */
  public static String format(UUID blobId) {
    return PREFIX + Objects.requireNonNull(blobId, "blobId");
  }
}
