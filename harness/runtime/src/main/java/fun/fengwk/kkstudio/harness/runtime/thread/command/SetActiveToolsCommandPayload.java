package fun.fengwk.kkstudio.harness.runtime.thread.command;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Typed SET_ACTIVE_TOOLS payload with ordered, canonical, duplicate-free tool names. */
public record SetActiveToolsCommandPayload(List<String> activeTools)
    implements ThreadCommandPayload {

  public SetActiveToolsCommandPayload {
    Objects.requireNonNull(activeTools, "activeTools");
    Set<String> uniqueTools = new LinkedHashSet<>();
    for (String activeTool : activeTools) {
      uniqueTools.add(
          CommandValueValidation.requireCanonicalName(activeTool, "activeTools element"));
    }
    activeTools = List.copyOf(uniqueTools);
  }

  @Override
  public ThreadCommandType type() {
    return ThreadCommandType.SET_ACTIVE_TOOLS;
  }
}
