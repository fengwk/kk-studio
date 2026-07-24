package fun.fengwk.kkstudio.harness.runtime.interaction;

/** Strictly validated raw JSON value safe for generic interaction query consumers. */
public record InteractionProjection(String json) {
  public InteractionProjection {
    json = InteractionJsonValue.validate(json, "interaction projection");
  }
}
