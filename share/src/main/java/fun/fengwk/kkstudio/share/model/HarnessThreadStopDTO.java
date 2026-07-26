package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/** Thread stop 请求。 */
@Data
public class HarnessThreadStopDTO {
  private Long expectedExecutionEpoch;
}
