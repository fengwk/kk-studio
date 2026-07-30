package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/** 排队 SET_MODEL 输入。 */
@Data
public class HarnessThreadModelSetDTO {
  private Long expectedExecutionEpoch;
  private String modelId;
  private String variant;
  private String clientMessageId;
}
