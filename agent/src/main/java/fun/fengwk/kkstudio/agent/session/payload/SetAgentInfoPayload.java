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

  /** 当前生效的 agent 名称。 */
  private String agentName;

  /** 当前生效的 system prompt。 */
  private String systemPrompt;

  /** 当前 Agent 声明的工具名称列表。 */
  private List<String> tools;
}
