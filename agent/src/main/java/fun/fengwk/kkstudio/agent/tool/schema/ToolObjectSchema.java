package fun.fengwk.kkstudio.agent.tool.schema;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * ToolObjectSchema 表示对象类型输入结构。
 *
 * @author fengwk
 */
@Builder
@Data
public final class ToolObjectSchema implements ToolSchemaElement {

    /**
     * 对象描述。
     */
    private final String description;

    /**
     * 属性定义表。
     */
    private final Map<String, ToolSchemaElement> properties;

    /**
     * 必填属性名列表。
     */
    private final List<String> required;

    /**
     * 是否允许额外属性。
     */
    private final Boolean additionalProperties;

}
