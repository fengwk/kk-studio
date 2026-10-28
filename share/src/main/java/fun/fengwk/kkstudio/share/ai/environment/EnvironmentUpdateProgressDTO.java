package fun.fengwk.kkstudio.share.ai.environment;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;

/** 当前受管更新的目标版本与发行归档下载进度；字节计数不超过 JavaScript 安全整数上界。 */
@Data
public class EnvironmentUpdateProgressDTO {
  private String targetVersion;
  private long downloadedBytes;

  /** 总长度未知时必须为显式 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable Long totalBytes;
}
