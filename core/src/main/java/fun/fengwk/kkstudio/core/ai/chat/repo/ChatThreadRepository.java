package fun.fengwk.kkstudio.core.ai.chat.repo;

/** Idempotent persistence boundary for Chat↔Thread historical associations. */
public interface ChatThreadRepository {

  /**
   * Returns {@code true} for a newly inserted association and {@code false} for an existing one.
   */
  boolean associate(long chatId, long threadId);
}
