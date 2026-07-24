package fun.fengwk.kkstudio.harness.runtime.tool.worker;

/** Finite result of one fenced ToolInvocation mutation. */
public enum ToolInvocationUpdateOutcome {
  APPLIED,
  LOST_OWNERSHIP
}
