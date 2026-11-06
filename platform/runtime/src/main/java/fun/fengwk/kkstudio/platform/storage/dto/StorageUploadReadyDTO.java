package fun.fengwk.kkstudio.platform.storage.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/** 已就绪：上传已绑定有效 blob（sha256 命中或 complete 成功），调用方无需直传。 */
@Data
@AllArgsConstructor
public final class StorageUploadReadyDTO implements StorageUploadDTO {

  private String id;

  private String blobId;

  private Instant expiresAt;
}
