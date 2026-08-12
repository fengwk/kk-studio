package fun.fengwk.kkstudio.core.studio.thread;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasDocumentMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasDocumentDO;
import fun.fengwk.kkstudio.harness.runtime.CreateThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.CreatedThread;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandBatch;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.studio.canvas.CanvasDocument;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Canvas 根 Thread 首次发送实现：单事务（document 行锁 + Harness {@code PROPAGATION_REQUIRED} 加入）原子完成
 * 创建/入队/绑定；重复提交（threadId 已绑定）原样重放既有 Thread 与 document。
 */
@Service
public class CanvasThreadServiceImpl implements CanvasThreadService {

  private final CanvasDocumentMapper documentMapper;
  private final ObjectProvider<HarnessRuntime> runtimes;

  public CanvasThreadServiceImpl(
      CanvasDocumentMapper documentMapper, ObjectProvider<HarnessRuntime> runtimes) {
    this.documentMapper = Objects.requireNonNull(documentMapper, "documentMapper");
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
  }

  @Override
  @Transactional
  public CanvasFirstSendResult sendFirstMessage(UUID canvasId, CanvasFirstSendCommand command) {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(command, "command");
    CanvasDocumentDO document = documentMapper.getByIdForUpdate(canvasId);
    if (document == null) {
      throw new IllegalArgumentException("Canvas not found: " + canvasId);
    }
    HarnessRuntime runtime =
        Objects.requireNonNull(
            runtimes.getIfAvailable(), "HarnessRuntime is required for Canvas thread first send");
    if (document.getThreadId() != null) {
      return replayExisting(runtime, document, command);
    }
    CreatedThread created =
        runtime.createThread(
            new CreateThreadCommand(command.branchSettings(), command.yoloEnabled()));
    UUID threadId = created.thread().id();
    runtime.enqueueCommands(
        new ThreadCommandBatch(
            threadId,
            created.rootEntry().id(),
            1L,
            List.of(
                new NewThreadCommand(
                    new UserMessageCommandPayload(
                        new AgentMessage(AgentMessageRole.USER, command.contents())),
                    UUID.fromString(command.commandId())))));
    if (documentMapper.bindThreadIfAbsent(canvasId, threadId) != 1) {
      throw new IllegalStateException(
          "canvas thread binding failed for canvas " + canvasId + " and thread " + threadId);
    }
    CanvasDocumentDO bound = documentMapper.getById(canvasId);
    if (bound == null || bound.getThreadId() == null) {
      throw new IllegalStateException("canvas document disappeared after thread binding");
    }
    return new CanvasFirstSendResult(threadId, toDomain(bound));
  }

  private static CanvasFirstSendResult replayExisting(
      HarnessRuntime runtime, CanvasDocumentDO document, CanvasFirstSendCommand command) {
    UUID threadId = document.getThreadId();
    UUID clientCommandId = UUID.fromString(command.commandId());
    var existing =
        runtime
            .findThreadCommand(threadId, clientCommandId)
            .orElseThrow(
                () ->
                    conflict("Canvas already has a bound Thread with a different first commandId"));
    UserMessageCommandPayload expected =
        new UserMessageCommandPayload(new AgentMessage(AgentMessageRole.USER, command.contents()));
    if (!existing.payload().equals(expected)) {
      throw conflict("Canvas first message commandId was reused with different message contents");
    }
    return new CanvasFirstSendResult(threadId, toDomain(document));
  }

  private static HarnessRuntimeConflictException conflict(String message) {
    return new HarnessRuntimeConflictException(
        HarnessRuntimeConflictException.Reason.COMMAND_ID_REUSED, message);
  }

  private static CanvasDocument toDomain(CanvasDocumentDO document) {
    return new CanvasDocument(
        document.getId(),
        document.getTitle(),
        document.getVersion(),
        document.getThreadId(),
        document.getCreatedAt().toInstant(),
        document.getUpdatedAt().toInstant());
  }
}
