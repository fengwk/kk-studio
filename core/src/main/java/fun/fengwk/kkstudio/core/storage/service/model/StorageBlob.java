package fun.fengwk.kkstudio.core.storage.service.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** {@code storage_blob} 行映射：去重后的不可变内容地址及其引用生命周期。 */
@Data
public class StorageBlob {

  /** 业务主键（uuid，应用生成）。 */
  private UUID id;

  /** 小写十六进制 SHA-256（64 字符）。 */
  private String sha256;

  /** 原始内容字节大小（非负）。 */
  private long sizeBytes;

  /** complete 时探针记录的权威媒体类型。 */
  private String mediaType;

  /** 不可变像素宽度；未探针时为 null。 */
  private Long width;

  /** 不可变像素高度；未探针时为 null。 */
  private Long height;

  /** 不可变媒体时长（毫秒）；静止媒体为 null。 */
  private Long durationMs;

  /** 活跃引用计数（READY 上传及后续 Canvas/Harness 消费者）。 */
  private long refCount;

  /** 生命周期状态：ACTIVE 或 DELETING。 */
  private StorageBlobState state;

  /** 创建时间（映射 {@code created_at} timestamptz，毫秒精度）。 */
  private Instant createTime;

  /** 更新时间（映射 {@code updated_at} timestamptz，应用侧维护，毫秒精度）。 */
  private Instant updateTime;
}
