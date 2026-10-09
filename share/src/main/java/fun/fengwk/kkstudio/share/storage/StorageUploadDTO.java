package fun.fengwk.kkstudio.share.storage;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 上传公开表示。
 *
 * <p>PENDING 时 {@code blobId} 为 null 且 {@code presignedPut} 非空；READY 时 {@code blobId} 非空且 {@code
 * presignedPut} 为 null。响应刻意不暴露 bucket 与对象物理 key。
 *
 * @author fengwk
 */
@Data
@Builder
public class StorageUploadDTO {

  /** 上传 id（uuid 字符串）。 */
  private String id;

  /** 当前状态：PENDING 待上传 / READY 已绑定 blob。 */
  private StorageUploadState state;

  /** READY 时绑定的 blob id（uuid 字符串）；PENDING 时为 null，required-nullable 显式输出。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String blobId;

  /** PENDING 时的浏览器直传预签名 PUT；READY 时为 null，required-nullable 显式输出。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private StoragePresignedUrlDTO presignedPut;

  /**
   * 上传清理截止时刻快照：按响应时刻的 upload TTL 与 {@code created_at} 换算的展示值（UTC Instant）。
   *
   * <p>它只是快照，不是过期权威事实；服务端始终以 {@code created_at + 当下 upload TTL} 判定过期，因此之后修改 TTL 会立即改变实际上传有效期， 但不会
   * 回改这里已经返回的值。
   */
  private Instant expiresAt;
}
