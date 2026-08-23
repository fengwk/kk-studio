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

  /** 清理截止时刻：过期后由过期批次回收。 */
  private Instant expiresAt;

  /** 创建时间（映射 {@code created_at} timestamptz，毫秒精度）。 */
  private Instant createTime;
}
