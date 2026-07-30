package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/** 向 Thread 队列提交用户消息。 */
@Data
public class HarnessThreadMessageCreateDTO {
  private Long expectedExecutionEpoch;
  private String content;

  /** 客户端幂等键。 */
  private String clientMessageId;
}
