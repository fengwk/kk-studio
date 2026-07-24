package fun.fengwk.kkstudio.harness.runtime.interaction;

/** Strictly validated raw JSON request value. JSON {@code null} is a valid explicit value. */
public record InteractionRequest(String json) {
  public InteractionRequest {
    json = InteractionJsonValue.validate(json, "interaction request");
  }
}
