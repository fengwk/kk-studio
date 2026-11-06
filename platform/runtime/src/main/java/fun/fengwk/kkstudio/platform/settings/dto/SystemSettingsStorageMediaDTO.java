package fun.fengwk.kkstudio.platform.settings.dto;

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

  private Long temporaryResourceTtlSeconds;

  private Long temporaryResourceCleanupIntervalSeconds;
}
