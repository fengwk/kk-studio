package fun.fengwk.kkstudio.agent.session.projection;

import fun.fengwk.kkstudio.agent.message.AgentMessage;
import fun.fengwk.kkstudio.agent.session.payload.SetAgentInfoPayload;
import fun.fengwk.kkstudio.agent.session.payload.SetModelInfoPayload;

import java.util.List;

/**
 * SessionEventProjection 表示 branch 重放后的配置与消息上下文。
 *
 * @author fengwk
 */
public record SessionEventProjection(SetAgentInfoPayload agentInfo,
                                      SetModelInfoPayload modelInfo,
                                      List<AgentMessage> messages) {
}
