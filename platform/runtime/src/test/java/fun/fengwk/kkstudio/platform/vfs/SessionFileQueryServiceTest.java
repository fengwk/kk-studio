package fun.fengwk.kkstudio.platform.vfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwner;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwnership;
import fun.fengwk.kkstudio.platform.storage.model.SessionFile;
import fun.fengwk.kkstudio.platform.storage.service.SessionFileStore;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class SessionFileQueryServiceTest {

  private final UUID session = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private final SessionOwner owner = new SessionOwner("CHAT", "chat-1");
  private final SessionOwnership ownership = mock(SessionOwnership.class);
  private final SessionFileStore store = mock(SessionFileStore.class);
  private final SessionFileQueryService service =
      new SessionFileQueryService(List.of(ownership), store, mock(StorageBlobManager.class));

  @Test
  void accessDenialAndMissingFileShareNotFoundCategory() {
    // 未授权不能触碰存储；已授权但文件缺失仍保持同一 HTTP 错误类别。
    when(ownership.kind()).thenReturn("CHAT");
    when(ownership.ownsSession(owner, session)).thenReturn(false, true);
    DomainException denied =
        assertThrows(DomainException.class, () -> service.list(owner, session, null));
    assertEquals(DomainErrorCode.NOT_FOUND, denied.code());
    verifyNoInteractions(store);
    when(store.find(session, "missing.txt")).thenReturn(Optional.empty());
    DomainException missing =
        assertThrows(
            DomainException.class,
            () -> service.readText(owner, session, path("missing.txt"), null, null, null));
    assertEquals(DomainErrorCode.NOT_FOUND, missing.code());
    assertEquals("session_file", missing.resource());
  }

  @Test
  void unreadableContentIsValidation() {
    // 非文本输入必须由领域错误拒绝，不能伪造文本内容。
    when(ownership.kind()).thenReturn("CHAT");
    when(ownership.ownsSession(owner, session)).thenReturn(true);
    when(store.find(session, "binary"))
        .thenReturn(
            Optional.of(new SessionFile(session, "binary", UUID.randomUUID(), 1, Instant.EPOCH)));
    when(store.read(session, "binary")).thenReturn(Optional.of(new byte[] {(byte) 0xff}));
    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.readText(owner, session, path("binary"), null, null, null));
    assertEquals(DomainErrorCode.VALIDATION, error.code());
    assertEquals("session_file", error.resource());
  }

  private String path(String name) {
    return "vfs:/sessions/" + session + "/" + name;
  }
}
