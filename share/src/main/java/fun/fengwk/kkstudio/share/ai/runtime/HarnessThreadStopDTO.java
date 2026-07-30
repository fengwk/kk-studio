package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/** Thread stop 请求。 */
@Data
public class HarnessThreadStopDTO {
  private Long expectedExecutionEpoch;
}
