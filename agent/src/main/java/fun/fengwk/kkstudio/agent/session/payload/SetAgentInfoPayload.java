package fun.fengwk.kkstudio.agent.session.payload;

import lombok.Data;

import java.util.List;

/**
 * set_agent_info 事件的持久化内容。
 *
 * @author fengwk
 */
@Data
public class SetAgentInfoPayload implements Payload {

    /**
     * 当前生效的 agent 名称。
     */
    private String agentName;

    /**
     * 当前生效的 system prompt。
     */
    private String systemPrompt;

    /**
     * 当前可用工具名称列表。
     */
    private List<String> tools;

    /**
     * 当前可用子代理名称列表。
     */
    private List<String> subagents;

    /**
     * 当前启用技能名称列表。
     */
    private List<String> skills;

}
