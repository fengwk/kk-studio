package fun.fengwk.kkstudio.agent.session.payload;

import lombok.Data;

/**
 * 工具结果的统一内容结构。
 *
 * @author fengwk
 */
@Data
public class ToolContent {

    /**
     * 内容类型。
     */
    private ToolContentType type;

    /**
     * 文本内容。
     */
    private String text;

    /**
     * 媒体内容数据。
     */
    private String data;

    /**
     * 媒体 mime 类型。
     */
    private String mime;

    /**
     * 媒体展示名。
     */
    private String name;

}
