package fun.fengwk.kkstudio.agent.tool.schema;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * ToolParamsSchema 表示工具顶层参数对象的输入结构。
 *
 * 语义说明：
 * - 它只用于 ToolInfo.inputSchema。
 * - 嵌套 object 字段继续使用 ToolObjectSchema。
 *
 * @author fengwk
 */
@Builder
@Data
public class ToolParamsSchema {

    /**
     * 顶层参数对象描述。
     */
    private final String description;

    /**
     * 顶层参数属性定义表。
     */
    private final Map<String, ToolSchemaElement> properties;

    /**
     * 顶层必填属性名列表。
     */
    private final List<String> required;

    /**
     * 是否允许未声明属性。
     */
    private final Boolean additionalProperties;

}
