package fun.fengwk.kkstudio.harness.runtime.model.plan;

/** Typed causes that can be persisted when a turn cannot produce a provider request. */
public enum PlanningFailureKind {
  MISSING_TURN_SETTINGS,
  AGENT_NOT_FOUND,
  PROVIDER_NOT_FOUND,
  MODEL_NOT_FOUND,
  VARIANT_NOT_FOUND,
  ENVIRONMENT_REQUIRED,
  ENVIRONMENT_NOT_FOUND,
  TOOL_NOT_FOUND,
  SKILL_NOT_FOUND,
  INVALID_TURN_SETTINGS
}
