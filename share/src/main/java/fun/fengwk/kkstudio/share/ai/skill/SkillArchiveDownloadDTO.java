package fun.fengwk.kkstudio.share.ai.skill;

import lombok.Data;

import fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO;

/** 当前版本归档的下载信息：预签名 GET 由浏览器直连对象存储。 */
@Data
public class SkillArchiveDownloadDTO {

  /** 归档文件名（{@code <package>-<version>.zip}）。 */
  private String filename;

  /** 归档的小写十六进制 SHA-256。 */
  private String sha256;

  /** 归档字节大小。 */
  private long sizeBytes;

  /** 预签名下载地址。 */
  private StoragePresignedUrlDTO download;
}
