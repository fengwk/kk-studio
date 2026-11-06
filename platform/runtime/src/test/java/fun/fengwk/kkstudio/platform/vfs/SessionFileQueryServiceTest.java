package fun.fengwk.kkstudio.platform.vfs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.storage.model.SessionFile;
import fun.fengwk.kkstudio.platform.storage.service.SessionFileStore;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

class SessionFileQueryServiceTest {

  private final UUID session = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private final HarnessRuntime runtime = mock(HarnessRuntime.class);

  @SuppressWarnings("unchecked")
  private final ObjectProvider<HarnessRuntime> runtimes = mock(ObjectProvider.class);

  private final SessionFileStore store = mock(SessionFileStore.class);
  private final SessionFileQueryService service =
      new SessionFileQueryService(runtimes, store, mock(StorageBlobManager.class));

  @Test
  void missingSessionAndMissingFileShareNotFoundCategory() {
    // 不存在的会话不能触碰存储；会话存在但文件缺失仍保持同一错误类别。
    when(runtimes.getIfAvailable()).thenReturn(runtime);
    when(runtime.sessionExists(session)).thenReturn(false, true);
    DomainException denied = assertThrows(DomainException.class, () -> service.list(session, null));
    assertEquals(DomainErrorCode.NOT_FOUND, denied.code());
    verifyNoInteractions(store);
    when(store.find(session, "missing.txt")).thenReturn(Optional.empty());
    DomainException missing =
        assertThrows(
            DomainException.class,
            () -> service.readText(session, path("missing.txt"), null, null, null));
    assertEquals(DomainErrorCode.NOT_FOUND, missing.code());
    assertEquals("session_file", missing.resource());
  }

  @Test
  void unreadableContentIsValidationWithNotTextReason() {
    // 非文本输入必须由领域错误拒绝，不能伪造文本内容；reason 让面板只停用预览、保留下载。
    when(runtimes.getIfAvailable()).thenReturn(runtime);
    when(runtime.sessionExists(session)).thenReturn(true);
    when(store.find(session, "binary"))
        .thenReturn(
            Optional.of(new SessionFile(session, "binary", UUID.randomUUID(), 1, Instant.EPOCH)));
    when(store.read(session, "binary")).thenReturn(Optional.of(new byte[] {(byte) 0xff}));
    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.readText(session, path("binary"), null, null, null));
    assertEquals(DomainErrorCode.VALIDATION, error.code());
    assertEquals(SessionFileQueryService.NOT_TEXT_REASON, error.reason());
  }

  @Test
  void invalidWindowParameterIsValidationWithoutNotTextReason() {
    // 非法 offset 属于请求参数错误，不是“文件不可作为文本”，不能携带 SESSION_FILE_NOT_TEXT。
    stubExistingFile("notes");
    when(store.read(session, "notes"))
        .thenReturn(Optional.of("hello".getBytes(StandardCharsets.UTF_8)));
    DomainException error =
        assertThrows(
            DomainException.class, () -> service.readText(session, path("notes"), 0, null, null));
    assertEquals(DomainErrorCode.VALIDATION, error.code());
    assertNull(error.reason());
  }

  @Test
  void interruptedReadIsNotReportedAsValidation() {
    // 读取超时/中断是瞬态不可用，不能伪装成请求校验失败。
    stubExistingFile("large");
    when(store.read(session, "large")).thenReturn(Optional.of(new byte[] {1}));
    Thread.currentThread().interrupt();
    try {
      DomainException error =
          assertThrows(
              DomainException.class,
              () -> service.readText(session, path("large"), null, null, null));
      assertEquals(DomainErrorCode.UNAVAILABLE, error.code());
      assertEquals("SESSION_FILE_READ_TIMEOUT", error.reason());
    } finally {
      Thread.interrupted();
    }
  }

  private void stubExistingFile(String name) {
    when(runtimes.getIfAvailable()).thenReturn(runtime);
    when(runtime.sessionExists(session)).thenReturn(true);
    when(store.find(session, name))
        .thenReturn(
            Optional.of(new SessionFile(session, name, UUID.randomUUID(), 1, Instant.EPOCH)));
  }

  private String path(String name) {
    return "vfs:/sessions/" + session + "/" + name;
  }
}
