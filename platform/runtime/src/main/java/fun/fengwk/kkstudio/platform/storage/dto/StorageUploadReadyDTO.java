package fun.fengwk.kkstudio.platform.storage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/** 已就绪：上传已绑定有效 blob（sha256 命中或 complete 成功），调用方无需直传。 */
@Data
@AllArgsConstructor
public final class StorageUploadReadyDTO implements StorageUploadDTO {

  private String id;

  private String blobId;

  private Instant expiresAt;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String format;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable Integer originalWidth;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable Integer originalHeight;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable Integer storedWidth;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable Integer storedHeight;

  public StorageUploadReadyDTO(String id, String blobId, Instant expiresAt) {
    this(id, blobId, expiresAt, null, null, null, null, null);
  }
}
