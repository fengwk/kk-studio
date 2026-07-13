package fun.fengwk.kkstudio.harness.runtime.context;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import java.util.List;

/** 可直接交给 Agent Turn 的上下文与当前配置。 */
public record SessionContext(AgentRuntimeConfig config, List<AgentMessage> messages) {
  public SessionContext {
    messages = List.copyOf(messages);
  }
}
