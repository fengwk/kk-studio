package fun.fengwk.kkstudio.harness.runtime;

/**
 * Durable entity referenced by a Harness control/query request does not exist (Thread, Entry
 * target). Business-inapplicable states are conflicts ({@link HarnessRuntimeConflictException}),
 * not not-found.
 */
public final class HarnessRuntimeNotFoundException extends RuntimeException {

  public HarnessRuntimeNotFoundException(String message) {
    super(message);
  }
}
