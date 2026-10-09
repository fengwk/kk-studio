package fun.fengwk.kkstudio.platform.storage.service.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** {@code storage_upload} 行映射：浏览器与服务端之间的上传契约。 */
@Data
public class StorageUpload {

  /** 上传主键（uuid，应用生成）；PENDING 直传键 {@code uploads/{id}/original} 由其推导。 */
  private UUID id;

  /** 服务端为 PENDING→READY 过渡预分配的 blob id（无 FK，complete 时才建行）。 */
  private UUID candidateBlobId;

  /** READY 时绑定的 blob id；PENDING 时为 null。 */
  private UUID blobId;

  /** 客户端声明的原始文件名。 */
  private String filename;

  /** 客户端声明的媒体类型。 */
  private String declaredMediaType;

  /** 客户端声明的字节大小。 */
  private long declaredSize;

  /** 客户端声明的小写十六进制 SHA-256（64 字符）。 */
  private String declaredSha256;

  /** 显式删除/消费的耐久请求时刻；非 null 后由后台清理并最终删除整行。 */
  private Instant cleanupRequestedAt;

  /** 后台对象清理所有权 token；未 claim 时为 null。 */
  private String cleanupToken;

  /** cleanupToken 的 lease 截止时刻；与 cleanupToken 成对为空或非空。 */
  private Instant cleanupUntil;

  /**
   * 创建时间（映射 {@code created_at} timestamptz，毫秒精度）。
   *
   * <p>过期权威事实：未显式请求清理、未被 claim 的上传在 {@code created_at + 当前 upload TTL} 之后过期，TTL 每次操作从 {@code
   * SystemSettingsSnapshot} 现读，因此缩短 TTL 会立即让存量过期、延长 TTL 会立即让未 claim 的存量继续有效。
   */
  private Instant createTime;
}
