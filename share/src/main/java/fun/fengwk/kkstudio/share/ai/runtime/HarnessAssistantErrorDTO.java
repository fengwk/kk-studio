package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/** 公共 Assistant 终止错误：稳定错误码与用户可读消息。 */
@Data
public class HarnessAssistantErrorDTO {

  /** 稳定错误码（大写字母、数字与下划线）。 */
  private String code;

  /** 用户可读错误消息，原样保留上游正文。 */
  private String message;
}
