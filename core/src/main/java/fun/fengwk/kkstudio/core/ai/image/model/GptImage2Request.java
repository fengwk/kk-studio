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

    /**
     * 提示词
     */
    private String prompt;

    /**
     * 图片尺寸
     */
    private GptImage2Size size = GptImage2Size.AUTO;

    /**
     * 参考图片列表，留空则文生图，上传图片则按编辑图处理
     */
    private List<ImageData> images;

}
