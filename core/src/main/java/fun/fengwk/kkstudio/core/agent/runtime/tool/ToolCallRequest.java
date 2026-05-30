package fun.fengwk.kkstudio.core.agent.runtime.tool;

import lombok.Data;

/**
 * @author fengwk
 */
@Data
public class ToolCallRequest {

    private String id;
    private String name;
    private String arguments;

}
