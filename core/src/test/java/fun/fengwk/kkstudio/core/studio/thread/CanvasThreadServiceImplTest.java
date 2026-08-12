package fun.fengwk.kkstudio.core.studio.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasDocumentMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasDocumentDO;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 已绑定 Canvas Thread 的首次发送只接受同 clientCommandId + 同 payload 精确重放。 */
class CanvasThreadServiceImplTest {

  private static final UUID CANVAS = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID THREAD = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID COMMAND = UUID.fromString("00000000-0000-0000-0000-000000000003");
  private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");

  @Test
  void exactReplayReturnsBoundThreadWithoutCreatingOrEnqueuing() {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    CanvasDocumentMapper documents = mock(CanvasDocumentMapper.class);
    when(documents.getByIdForUpdate(CANVAS)).thenReturn(boundDocument());
    when(runtime.findThreadCommand(THREAD, COMMAND))
        .thenReturn(Optional.of(storedCommand("hello")));
    CanvasThreadServiceImpl service = new CanvasThreadServiceImpl(documents, provider(runtime));

    CanvasThreadService.CanvasFirstSendResult result =
        service.sendFirstMessage(CANVAS, command(COMMAND, "hello"));

    assertEquals(THREAD, result.threadId());
    assertEquals(THREAD, result.document().threadId());
    verify(runtime, never()).createThread(any());
    verify(runtime, never()).enqueueCommands(any());
  }

  @Test
  void missingOrDifferentFirstCommandIsRejectedAsIdReuse() {
    HarnessRuntime runtime = mock(HarnessRuntime.class);
    CanvasDocumentMapper documents = mock(CanvasDocumentMapper.class);
    when(documents.getByIdForUpdate(CANVAS)).thenReturn(boundDocument());
    CanvasThreadServiceImpl service = new CanvasThreadServiceImpl(documents, provider(runtime));

    when(runtime.findThreadCommand(THREAD, COMMAND)).thenReturn(Optional.empty());
    HarnessRuntimeConflictException missing =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> service.sendFirstMessage(CANVAS, command(COMMAND, "hello")));
    assertEquals(HarnessRuntimeConflictException.Reason.COMMAND_ID_REUSED, missing.reason());

    when(runtime.findThreadCommand(THREAD, COMMAND))
        .thenReturn(Optional.of(storedCommand("different")));
    HarnessRuntimeConflictException changed =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> service.sendFirstMessage(CANVAS, command(COMMAND, "hello")));
    assertEquals(HarnessRuntimeConflictException.Reason.COMMAND_ID_REUSED, changed.reason());
  }

  private static CanvasThreadService.CanvasFirstSendCommand command(UUID commandId, String text) {
    return new CanvasThreadService.CanvasFirstSendCommand(
        commandId.toString(),
        new BranchSettings(
            null, "assistant", new ModelSelection("stub", "model", "default"), List.of()),
        false,
        List.of(new TextMessageContent(text)));
  }

  private static ThreadCommand storedCommand(String text) {
    return new ThreadCommand(
        THREAD,
        1L,
        new UserMessageCommandPayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)))),
        COMMAND,
        null,
        null,
        NOW);
  }

  private static CanvasDocumentDO boundDocument() {
    CanvasDocumentDO document = new CanvasDocumentDO();
    document.setId(CANVAS);
    document.setTitle("canvas");
    document.setVersion(0L);
    document.setThreadId(THREAD);
    document.setCreatedAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    document.setUpdatedAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    return document;
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(T value) {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(value);
    return provider;
  }
}
