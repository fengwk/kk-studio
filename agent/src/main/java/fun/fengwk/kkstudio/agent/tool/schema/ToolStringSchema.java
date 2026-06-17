package fun.fengwk.kkstudio.agent.tool.schema;

import lombok.Builder;
import lombok.Data;

/**
 * ToolStringSchema 表示字符串类型参数。
 *
 * @author fengwk
 */
@Builder
@Data
public final class ToolStringSchema implements ToolSchemaElement {

    /**
     * 参数描述。
     */
    private final String description;

}
