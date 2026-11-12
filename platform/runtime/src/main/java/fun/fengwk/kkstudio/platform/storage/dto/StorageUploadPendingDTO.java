package fun.fengwk.kkstudio.platform.storage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/** 待上传：内容尚未绑定 blob，客户端必须先按 {@code presignedPut} 直传对象再 complete。 */
@Data
@AllArgsConstructor
public final class StorageUploadPendingDTO implements StorageUploadDTO {

  private String id;

  private StoragePresignedUrlDTO presignedPut;

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

  public StorageUploadPendingDTO(
      String id, StoragePresignedUrlDTO presignedPut, Instant expiresAt) {
    this(id, presignedPut, expiresAt, null, null, null, null, null);
  }
}
