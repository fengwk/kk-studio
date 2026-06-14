package fun.fengwk.kkstudio.core.agent.runtime.tool;

import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class ToolCallResponse {

    /** tool call id。 */
    private String id;

    /** 工具执行完整结果。 */
    private String result;

}
