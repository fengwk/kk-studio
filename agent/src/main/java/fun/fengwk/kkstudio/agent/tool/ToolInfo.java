package fun.fengwk.kkstudio.agent.tool;

import lombok.Builder;
import lombok.Data;

/**
 * ToolInfo 表示一个工具的可执行描述信息。
 *
 * @author fengwk
 */
@Builder
@Data
public class ToolInfo {

    /**
     * 工具名称。
     */
    private final String name;

    /**
     * 工具描述。
     */
    private final String description;

    /**
     * 工具输入结构定义。
     */
    private final String inputSchema;

}
