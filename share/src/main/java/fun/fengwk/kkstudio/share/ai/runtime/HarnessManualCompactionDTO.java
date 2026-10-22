package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;

/** Thread 快照中的手动压缩可用性投影。 */
@Data
public class HarnessManualCompactionDTO {

  private Boolean available;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String disabledReason;
}
