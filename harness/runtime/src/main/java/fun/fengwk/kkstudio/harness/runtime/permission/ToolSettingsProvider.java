package fun.fengwk.kkstudio.harness.runtime.permission;

/**
 * Supplies the single deployment-wide normalized Tool settings snapshot. Defined under {@code
 * harness/runtime/.../permission} so the Runtime Tool worker can inject it without depending on the
 * Core deployment module.
 */
public interface ToolSettingsProvider {
  ToolSettings get();
}
