package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;

/** 按当前 branch 最新状态现算的系统提示词预览。 */
@Data
public class HarnessSystemPromptPreviewDTO {
  /** 组合后的系统提示词全文；无法组合时为空字符串。 */
  private String text;
}
