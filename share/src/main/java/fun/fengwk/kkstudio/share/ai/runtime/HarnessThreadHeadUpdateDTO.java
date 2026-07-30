package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/** Thread head bind/rebind/unbind 请求。 */
@Data
public class HarnessThreadHeadUpdateDTO {
  private String headEntryId;
  private Long expectedExecutionEpoch;
}
