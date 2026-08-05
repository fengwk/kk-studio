package fun.fengwk.kkstudio.core.ai.chat.repo;

import java.util.List;

/** Idempotent persistence boundary for Chat↔Thread historical associations. */
public interface ChatThreadRepository {

  /**
   * Returns {@code true} for a newly inserted association and {@code false} for an existing one.
   */
  boolean associate(long chatId, long threadId);

  /** Returns the associated Thread ids ordered newest association first (immutable list). */
  List<Long> listThreadIds(long chatId);
}
