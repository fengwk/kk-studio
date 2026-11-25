package fun.fengwk.kkstudio.platform.account;

import java.util.UUID;

/** Suspends schedules when their owner account is disabled. */
public interface AccountScheduleSuspension {
  void suspend(UUID userId);
}
