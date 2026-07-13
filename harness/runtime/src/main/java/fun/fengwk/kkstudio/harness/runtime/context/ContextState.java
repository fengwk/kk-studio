package fun.fengwk.kkstudio.harness.runtime.context;

import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import java.util.List;

/** 默认 transform 的中间结果，扩展可在投影为 AgentMessage 前变换它。 */
public record ContextState(AgentRuntimeConfig config, List<SessionEntry> entries) {
  public ContextState {
    entries = List.copyOf(entries);
  }
}
