package fun.fengwk.kkstudio.platform.storage.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/** 待上传：内容尚未绑定 blob，客户端必须先按 {@code presignedPut} 直传对象再 complete。 */
@Data
@AllArgsConstructor
public final class StorageUploadPendingDTO implements StorageUploadDTO {

  private String id;

  private StoragePresignedUrlDTO presignedPut;

  private Instant expiresAt;
}
