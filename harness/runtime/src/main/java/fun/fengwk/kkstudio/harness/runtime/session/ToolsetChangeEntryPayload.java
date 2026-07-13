package fun.fengwk.kkstudio.harness.runtime.session;

import java.util.List;
import java.util.Objects;

public record ToolsetChangeEntryPayload(List<String> tools) implements SessionEntryPayload {
  public ToolsetChangeEntryPayload {
    tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
    if (tools.stream().anyMatch(tool -> tool == null || tool.isBlank())) {
      throw new IllegalArgumentException("tools must only contain non-blank values");
    }
  }

  @Override
  public SessionEntryType type() {
    return SessionEntryType.TOOLSET_CHANGE;
  }
}
