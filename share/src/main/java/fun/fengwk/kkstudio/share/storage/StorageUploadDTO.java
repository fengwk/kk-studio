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

  /** 上传清理截止时刻：过期后由服务端回收（UTC Instant）。 */
  private Instant expiresAt;
}
