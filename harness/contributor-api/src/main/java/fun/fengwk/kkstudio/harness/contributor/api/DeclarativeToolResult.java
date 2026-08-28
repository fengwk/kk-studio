package fun.fengwk.kkstudio.harness.contributor.api;

import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.List;
import java.util.Objects;

/** Declarative Tool 的纯返回值：模型可见 ToolResult 与待由 Harness Core 校验、持久化的 intents。 */
public record DeclarativeToolResult(ToolResult result, List<AppendCustomEntry> intents) {

  public DeclarativeToolResult {
    result = Objects.requireNonNull(result, "result");
    intents = List.copyOf(Objects.requireNonNull(intents, "intents"));
    for (AppendCustomEntry intent : intents) {
      Objects.requireNonNull(intent, "intents[]");
    }
    if (result.error() && !intents.isEmpty()) {
      throw new IllegalArgumentException("an error ToolResult must not carry intents");
    }
  }

  public static DeclarativeToolResult withoutIntents(ToolResult result) {
    return new DeclarativeToolResult(result, List.of());
  }
}
