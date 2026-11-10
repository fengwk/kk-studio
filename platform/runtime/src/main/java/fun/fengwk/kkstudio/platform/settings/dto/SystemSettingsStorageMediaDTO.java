package fun.fengwk.kkstudio.platform.settings.dto;

import lombok.Data;

/** storageMedia section：上传/S3 预签名、Canvas 媒体处理、请求时图片档位与受控临时资源的非敏感预算。 */
@Data
public class SystemSettingsStorageMediaDTO {

  private Long uploadExpiresSeconds;

  private Long s3PresignDefaultExpiresSeconds;

  private Long s3PresignMaxExpiresSeconds;

  private Long canvasMediaProcessTimeoutMillis;

  private Integer thumbnailMaxDimension;

  private Integer thumbnailQuality;

  /** 请求时图片输入档位的规范 wire 名称（720P / 1080P / ORIGINAL）。 */
  private String imageTier;

  private Long temporaryResourceTtlSeconds;

  private Long temporaryResourceCleanupIntervalSeconds;
}
