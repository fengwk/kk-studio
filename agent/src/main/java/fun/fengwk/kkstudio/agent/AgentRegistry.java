package fun.fengwk.kkstudio.agent;

/**
 * AgentRegistry 负责 agent 信息的注册与查询。
 *
 * @author fengwk
 */
public interface AgentRegistry {

  /** 注册 agent 信息。 */
  void registerAgent(AgentInfo agentInfo);

  /** 按名称查询 agent 信息。 */
  AgentInfo getAgent(String name);
}
