package fun.fengwk.kkstudio.core.ai.image.model;

import lombok.Data;

/**
 * GPT Image 2 响应.
 *
 * @author fengwk
 */
@Data
public class GptImage2Response {

  /** 生成的图片 */
  private ImageData image;

  /** 对话文本 */
  private String conversationText;
}
