package fun.fengwk.kkstudio.harness.runtime.port;

/** PostgreSQL-backed id allocation port for durable Harness facts; sequence gaps are valid. */
public interface HarnessIdGenerator {
  long nextSessionId();

  long nextThreadId();

  long nextEntryId();

  long nextInputId();

  long nextModelInvocationId();

  long nextToolInvocationId();

  long nextInteractionId();
}
