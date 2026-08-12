package fun.fengwk.kkstudio.core.storage.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 全局 Blob 存储配置属性.
 *
 * @author fengwk
 */
@ConfigurationProperties(prefix = "kk-studio.storage")
@Data
public class StorageProperties {

  /** 上传默认有效期（秒）：过期后由机会式批次与启动恢复回收。 */
  public static final long DEFAULT_UPLOAD_EXPIRES_SECONDS = 3600L;

  /** 上传有效期（秒），未设置时取 {@link #DEFAULT_UPLOAD_EXPIRES_SECONDS}. */
  private Long uploadExpiresSeconds;

  /** 获取上传有效期（秒），未设置时回退到 {@link #DEFAULT_UPLOAD_EXPIRES_SECONDS}. */
  public long getEffectiveUploadExpiresSeconds() {
    return uploadExpiresSeconds != null ? uploadExpiresSeconds : DEFAULT_UPLOAD_EXPIRES_SECONDS;
  }
}
