package fun.fengwk.kkstudio.share.systemsettings;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/** storageMedia section：上传/S3 预签名与 Canvas 媒体处理的非敏感预算。 */
@Data
public class SystemSettingsStorageMediaDTO {

  private Long uploadExpiresSeconds;

  private Long s3PresignDefaultExpiresSeconds;

  private Long s3PresignMaxExpiresSeconds;

  private Long canvasMediaProcessTimeoutMillis;

  private Integer thumbnailMaxDimension;

  private Integer thumbnailQuality;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown system settings storageMedia field: " + name);
  }
}
