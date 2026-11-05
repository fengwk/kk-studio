package fun.fengwk.kkstudio.platform.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import fun.fengwk.kkstudio.platform.error.DomainException;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwner;

import java.util.List;
import java.util.UUID;

class ChatSessionOwnershipTest {
  @Test
  void ownershipAndLocks() {
    // Verify relation-based authorization, ordering and locking.
    ChatRepository chats = mock(ChatRepository.class);
    ChatSessionRepository sessions = mock(ChatSessionRepository.class);
    ChatSessionOwnership spi = new ChatSessionOwnership(chats, sessions);
    UUID id = UUID.randomUUID(), session = UUID.randomUUID();
    SessionOwner owner = new SessionOwner("CHAT", id.toString());
    Chat chat = new Chat();
    chat.setTitle("title");
    when(chats.lockForKeyShare(id)).thenReturn(chat);
    when(chats.getById(id)).thenReturn(chat);
    when(chats.lockById(id)).thenReturn(chat);
    when(sessions.findBySessionId(session)).thenReturn(new ChatSession(session, id));
    when(sessions.listSessionIds(id)).thenReturn(List.of(session));
    assertTrue(spi.allowsFork());
    assertTrue(spi.allowsDirectCommands());
    spi.lockForAcceptance(owner, UUID.randomUUID());
    assertTrue(spi.ownsSession(owner, session));
    assertEquals(List.of(session), spi.listSessions(owner));
    assertEquals(List.of(session), spi.lockForDeletion(owner));
    assertEquals("/chats/" + id, spi.findOwner(UUID.randomUUID(), session).orElseThrow().link());
    spi.lockForInteraction(UUID.randomUUID(), session);
    verify(chats, times(2)).lockForKeyShare(id);
    when(sessions.deleteBySessionId(session)).thenReturn(1);
    spi.beforeSessionDeletion(owner, session);
  }

  @Test
  void rejectsInvalidAndConflictingOwnership() {
    // Invalid identities and duplicate relation inserts fail closed.
    ChatRepository chats = mock(ChatRepository.class);
    ChatSessionRepository sessions = mock(ChatSessionRepository.class);
    ChatSessionOwnership spi = new ChatSessionOwnership(chats, sessions);
    UUID id = UUID.randomUUID(), session = UUID.randomUUID();
    SessionOwner owner = new SessionOwner("CHAT", id.toString());
    assertThrows(DomainException.class, () -> spi.listSessions(owner));
    assertThrows(
        IllegalArgumentException.class,
        () -> spi.lockForAcceptance(new SessionOwner("CHAT", "1-1-1-1-1"), null));
    when(sessions.insert(session, id)).thenThrow(new DuplicateKeyException("duplicate"));
    assertThrows(IllegalStateException.class, () -> spi.sessionCreated(owner, session));
    assertThrows(IllegalArgumentException.class, () -> new SessionOwner(" ", "id"));
  }

  @Test
  void damagedAndAbsentRelationsFailClosed() {
    // Exercise missing owners, deletion races and successful creation.
    ChatRepository chats = mock(ChatRepository.class);
    ChatSessionRepository sessions = mock(ChatSessionRepository.class);
    ChatSessionOwnership spi = new ChatSessionOwnership(chats, sessions);
    UUID id = UUID.randomUUID(), session = UUID.randomUUID(), thread = UUID.randomUUID();
    SessionOwner owner = new SessionOwner("CHAT", id.toString());
    assertEquals("CHAT", spi.kind());
    assertThrows(DomainException.class, () -> spi.lockForAcceptance(owner, null));
    assertEquals(List.of(), spi.lockForDeletion(owner));
    assertTrue(spi.findOwner(thread, session).isEmpty());
    spi.lockForInteraction(thread, session);
    assertThrows(IllegalStateException.class, () -> spi.beforeSessionDeletion(owner, session));
    when(sessions.insert(session, id)).thenReturn(true, false);
    spi.sessionCreated(owner, session);
    assertThrows(IllegalStateException.class, () -> spi.sessionCreated(owner, session));
    when(sessions.findBySessionId(session)).thenReturn(new ChatSession(session, id));
    assertThrows(IllegalStateException.class, () -> spi.findOwner(thread, session));
    assertThrows(IllegalStateException.class, () -> spi.lockForInteraction(thread, session));
    when(chats.lockForKeyShare(id)).thenReturn(new Chat());
    when(sessions.findBySessionId(session))
        .thenReturn(new ChatSession(session, id), (ChatSession) null);
    assertThrows(IllegalStateException.class, () -> spi.lockForInteraction(thread, session));
    assertThrows(
        IllegalArgumentException.class,
        () -> spi.ownsSession(new SessionOwner("OTHER", "x"), session));
  }
}
