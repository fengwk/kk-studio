package fun.fengwk.kkstudio.harness.runtime.thread;

/** Cancels a provider call owned by this JVM after durable Thread fencing has committed. */
@FunctionalInterface
public interface ThreadProviderCancellation {
  /** Best-effort only: durable fencing remains the correctness boundary. */
  void cancelLocalProvider(long threadId);
}
