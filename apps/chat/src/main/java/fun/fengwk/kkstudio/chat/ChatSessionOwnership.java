package fun.fengwk.kkstudio.chat;

import lombok.AllArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwner;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwnerDisplay;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwnership;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Chat session ownership; locks are held by the caller's transaction. */
@Service
@AllArgsConstructor
public class ChatSessionOwnership implements SessionOwnership {
  private final ChatRepository chats;
  private final ChatSessionRepository sessions;

  @Override
  public String kind() {
    return "CHAT";
  }

  @Override
  public boolean allowsFork() {
    return true;
  }

  @Override
  public boolean allowsDirectCommands() {
    return true;
  }

  private UUID id(SessionOwner owner) {
    if (!kind().equals(owner.kind())) {
      throw new IllegalArgumentException("Invalid owner kind");
    }
    UUID id = UUID.fromString(owner.id());
    if (!id.toString().equals(owner.id())) {
      throw new IllegalArgumentException("Chat id must be a canonical UUID");
    }
    return id;
  }

  @Override
  public void lockForAcceptance(SessionOwner owner, UUID newRootThreadId) {
    if (chats.lockForKeyShare(id(owner)) == null) {
      throw new AiResourceNotFoundException("chat");
    }
  }

  @Override
  public boolean ownsSession(SessionOwner owner, UUID sessionId) {
    UUID chatId = id(owner);
    ChatSession relation = sessions.findBySessionId(sessionId);
    return relation != null && relation.chatId().equals(chatId);
  }

  @Override
  public void sessionCreated(SessionOwner owner, UUID sessionId) {
    try {
      if (!sessions.insert(sessionId, id(owner))) {
        throw new IllegalStateException("Session is already owned");
      }
    } catch (DataIntegrityViolationException e) {
      throw new IllegalStateException("Session is already owned", e);
    }
  }

  @Override
  public List<UUID> listSessions(SessionOwner owner) {
    UUID chatId = id(owner);
    if (chats.getById(chatId) == null) {
      throw new AiResourceNotFoundException("chat");
    }
    return sessions.listSessionIds(chatId);
  }

  @Override
  public List<UUID> lockForDeletion(SessionOwner owner) {
    UUID chatId = id(owner);
    return chats.lockById(chatId) == null ? List.of() : sessions.listSessionIds(chatId);
  }

  @Override
  public void beforeSessionDeletion(SessionOwner owner, UUID sessionId) {
    if (!ownsSession(owner, sessionId) || sessions.deleteBySessionId(sessionId) != 1) {
      throw new IllegalStateException("Session ownership is inconsistent");
    }
  }

  @Override
  public Optional<SessionOwnerDisplay> findOwner(UUID rootThreadId, UUID rootSessionId) {
    ChatSession relation = sessions.findBySessionId(rootSessionId);
    if (relation == null) {
      return Optional.empty();
    }
    Chat chat = chats.getById(relation.chatId());
    if (chat == null) {
      throw new IllegalStateException("Chat owner does not exist");
    }
    String id = relation.chatId().toString();
    return Optional.of(new SessionOwnerDisplay(kind(), id, chat.getTitle(), "/chats/" + id));
  }

  @Override
  public void lockForInteraction(UUID rootThreadId, UUID rootSessionId) {
    ChatSession relation = sessions.findBySessionId(rootSessionId);
    if (relation != null) {
      if (chats.lockForKeyShare(relation.chatId()) == null
          || !relation.equals(sessions.findBySessionId(rootSessionId))) {
        throw new IllegalStateException("Chat ownership is inconsistent");
      }
    }
  }
}
