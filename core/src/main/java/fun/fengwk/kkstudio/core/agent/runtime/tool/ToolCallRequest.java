package fun.fengwk.kkstudio.core.agent.runtime.tool;

import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class ToolCallRequest {

    /** 模型生成的 tool call id，用于回填工具结果。 */
    private String id;

    /** 工具名称。 */
    private String name;

    /** 工具参数 JSON。 */
    private String arguments;

}
