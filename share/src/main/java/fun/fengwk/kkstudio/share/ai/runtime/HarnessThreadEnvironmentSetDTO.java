package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/** 排队 SET_ENVIRONMENT 输入；null 清除 Environment target。 */
@Data
public class HarnessThreadEnvironmentSetDTO {
  private Long expectedExecutionEpoch;
  private String environmentName;
  private String clientMessageId;
}
