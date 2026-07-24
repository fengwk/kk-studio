package fun.fengwk.kkstudio.harness.runtime.interaction;

/** Strictly validated raw JSON response value. JSON {@code null} is a valid explicit value. */
public record InteractionResponse(String json) {
  public InteractionResponse {
    json = InteractionJsonValue.validate(json, "interaction response");
  }
}
