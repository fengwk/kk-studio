package fun.fengwk.kkstudio.core.ai.environment.registry;

/** Live Environment connection lifecycle as observed by the in-memory registry. */
public enum LiveEnvironmentStatus {
  /** HELLO accepted; READY not yet complete. */
  CONNECTING,
  /** READY and eligible for Environment tool dispatch and skill load. */
  READY
}
