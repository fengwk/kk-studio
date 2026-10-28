package fun.fengwk.kkstudio.share.ai.environment;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;

/** 当前实际运行的 Daemon 版本与可空的更新进度；{@code update} 只在正在更新时存在。 */
@Data
public class EnvironmentRuntimeDTO {
  private String daemonVersion;

  /** 无更新时必须为显式 null，而不是省略字段。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable EnvironmentUpdateProgressDTO update;
}
