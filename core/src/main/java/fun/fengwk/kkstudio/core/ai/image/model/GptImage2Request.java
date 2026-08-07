package fun.fengwk.kkstudio.core.ai.image.model;

import lombok.Data;

import java.util.List;

/**
 * GPT Image 2 请求.
 *
 * @author fengwk
 */
@Data
public class GptImage2Request {

  /** 提示词，必填：服务端 generate 强制非 null；描述期望生成的图片内容。 */
  private String prompt;

  /** 图片尺寸，默认 {@link GptImage2Size#AUTO}；传 null 或 AUTO 均按服务端自动处理。 */
  private GptImage2Size size = GptImage2Size.AUTO;

  /** 参考图片列表，可空：留空（null/空列表）则文生图，上传图片则按编辑图处理。 */
  private List<ImageData> images;
}
