package fun.fengwk.kkstudio.harness.runtime.session;

/** 原样保存的 JSON 内容。 */
public record JsonMessageContent(String json) implements AgentMessageContent {
  public JsonMessageContent {
    if (json == null || json.isBlank()) {
      throw new IllegalArgumentException("json must not be blank");
    }
  }
}
