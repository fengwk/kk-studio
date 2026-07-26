package fun.fengwk.kkstudio.share.model;

import lombok.Data;

/** 向 Thread 队列提交受限的自定义上下文消息。 */
@Data
public class HarnessThreadCustomMessageCreateDTO {
  private Long expectedExecutionEpoch;

  /** 仅允许 {@code system} 或 {@code user}，禁止客户端构造 assistant/tool 消息。 */
  private String role;

  private String content;

  /** 客户端幂等键。 */
  private String clientMessageId;
}
