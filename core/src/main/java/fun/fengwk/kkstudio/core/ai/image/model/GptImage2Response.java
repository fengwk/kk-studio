package fun.fengwk.kkstudio.core.ai.image.model;

import lombok.Data;

/**
 * GPT Image 2 响应.
 *
 * @author fengwk
 */
@Data
public class GptImage2Response {

  /** 生成的图片（含 MIME 类型与 Base64 编码的图片字节）。 */
  private ImageData image;

  /** 对话文本，可空：模型返回的伴随文本消息。 */
  private String conversationText;
}
