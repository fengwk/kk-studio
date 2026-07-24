package fun.fengwk.kkstudio.harness.runtime.interaction;

/**
 * Durable owner mutation selected by Interaction creation or deterministic resolution.
 *
 * <p>Actions are deliberately finite: an adapter must reject an owner/action combination it cannot
 * prove atomically, rather than treating an Interaction transition as a successful no-op.
 */
public enum InteractionOwnerAction {
  SUSPEND_THREAD,
  SUSPEND_TOOL_INVOCATION,
  RESUME_THREAD,
  RESUME_TOOL_TO_QUEUED,
  REJECT_TOOL_TO_FAILED
}
