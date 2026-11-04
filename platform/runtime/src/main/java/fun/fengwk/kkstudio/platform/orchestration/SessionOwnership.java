package fun.fengwk.kkstudio.platform.orchestration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Product ownership operations. Lock and write hooks execute in the caller's transaction. */
public interface SessionOwnership {
  String kind();

  boolean allowsFork();

  boolean allowsDirectCommands();

  /** newRootThreadId is non-null only for a new root. */
  void lockForAcceptance(SessionOwner owner, UUID newRootThreadId);

  boolean ownsSession(SessionOwner owner, UUID sessionId);

  void sessionCreated(SessionOwner owner, UUID sessionId);

  List<UUID> listSessions(SessionOwner owner);

  List<UUID> lockForDeletion(SessionOwner owner);

  void beforeSessionDeletion(SessionOwner owner, UUID sessionId);

  Optional<SessionOwnerDisplay> findOwner(UUID rootThreadId, UUID rootSessionId);

  void lockForInteraction(UUID rootThreadId, UUID rootSessionId);
}
