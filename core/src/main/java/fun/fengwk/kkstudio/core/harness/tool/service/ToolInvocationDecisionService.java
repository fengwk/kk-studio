package fun.fengwk.kkstudio.core.harness.tool.service;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.model.ToolInvocationDO;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventPayloads;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventType;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadKick;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolPermissionDecision;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolResultJsonCodec;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.share.model.ToolInvocationDTO;
import fun.fengwk.kkstudio.share.model.ToolInvocationDecisionDTO;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ConcurrentModificationException;
import java.util.Objects;

/** 权限决策。锁序：非锁 peek → 锁 Thread → 锁 invocation，与 processor release 一致，避免死锁。 */
@Service
public class ToolInvocationDecisionService {
  private final ToolInvocationMapper invocationMapper;
  private final HarnessThreadMapper threadMapper;
  private final ThreadEventStore eventStore;
  private final ThreadKick threadKick;

  public ToolInvocationDecisionService(
      ToolInvocationMapper invocationMapper,
      HarnessThreadMapper threadMapper,
      ThreadEventStore eventStore,
      @Lazy ThreadKick threadKick) {
    this.invocationMapper = Objects.requireNonNull(invocationMapper, "invocationMapper");
    this.threadMapper = Objects.requireNonNull(threadMapper, "threadMapper");
    this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
    this.threadKick = Objects.requireNonNull(threadKick, "threadKick");
  }

  @Transactional
  public ToolInvocationDTO decide(long invocationId, ToolInvocationDecisionDTO decisionDTO) {
    Objects.requireNonNull(decisionDTO, "decisionDTO");
    ToolPermissionDecision decision =
        ToolPermissionDecision.fromApiValue(decisionDTO.getDecision());
    ToolInvocationDO peek = invocationMapper.find(invocationId);
    if (peek == null) {
      throw new IllegalArgumentException("unknown tool invocation: " + invocationId);
    }
    long threadId = peek.getThreadId();
    if (threadMapper.findForUpdate(threadId) == null) {
      throw new IllegalStateException("owning thread missing for invocation " + invocationId);
    }
    ToolInvocationDO row = invocationMapper.findForUpdate(invocationId);
    if (row == null || !Objects.equals(row.getThreadId(), threadId)) {
      throw new IllegalArgumentException("unknown tool invocation: " + invocationId);
    }
    if (!ToolInvocationStatus.WAITING_APPROVAL.name().equals(row.getStatus())) {
      throw new IllegalStateException("invocation is not waiting approval");
    }
    Instant instant = Instant.now();
    LocalDateTime now = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    if (decision == ToolPermissionDecision.ALLOW) {
      int updated =
          invocationMapper.resolvePermission(
              invocationId,
              ToolInvocationStatus.WAITING_APPROVAL.name(),
              decision.name(),
              ToolInvocationStatus.QUEUED.name(),
              row.getDeadlineAt(),
              null,
              null,
              null,
              now);
      if (updated != 1) {
        throw new ConcurrentModificationException(
            "permission decision lost race for invocation " + invocationId);
      }
      eventStore.append(
          row.getThreadId(),
          row.getAssistantEntryId(),
          ThreadEventType.PERMISSION_RESOLVED,
          ThreadEventPayloads.of(
              "invocationId",
              Long.toString(row.getId()),
              "decision",
              decision.name(),
              "status",
              ToolInvocationStatus.QUEUED.name()),
          instant);
    } else {
      ToolResult denied = ToolResult.error(row.getToolCallId(), "Permission denied by user.");
      int updated =
          invocationMapper.resolvePermission(
              invocationId,
              ToolInvocationStatus.WAITING_APPROVAL.name(),
              decision.name(),
              ToolInvocationStatus.FAILED.name(),
              row.getDeadlineAt(),
              ToolResultJsonCodec.encode(denied),
              "Permission denied by user.",
              now,
              now);
      if (updated != 1) {
        throw new ConcurrentModificationException(
            "permission decision lost race for invocation " + invocationId);
      }
      eventStore.append(
          row.getThreadId(),
          row.getAssistantEntryId(),
          ThreadEventType.PERMISSION_RESOLVED,
          ThreadEventPayloads.of(
              "invocationId",
              Long.toString(row.getId()),
              "decision",
              decision.name(),
              "status",
              ToolInvocationStatus.FAILED.name()),
          instant);
      eventStore.append(
          row.getThreadId(),
          row.getAssistantEntryId(),
          ThreadEventType.TOOL_COMPLETED,
          ThreadEventPayloads.of(
              "invocationId",
              Long.toString(row.getId()),
              "toolCallId",
              row.getToolCallId(),
              "status",
              ToolInvocationStatus.FAILED.name(),
              "error",
              true,
              "errorMessage",
              "Permission denied by user."),
          instant);
    }
    afterCommitKick(row.getThreadId());
    ToolInvocationDO refreshed = invocationMapper.find(invocationId);
    ToolInvocationDTO dto = new ToolInvocationDTO();
    dto.setId(Long.toString(refreshed.getId()));
    dto.setThreadId(Long.toString(refreshed.getThreadId()));
    dto.setStatus(refreshed.getStatus());
    dto.setPermissionDecision(refreshed.getPermissionDecision());
    return dto;
  }

  private void afterCommitKick(long threadId) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      threadKick.kick(threadId);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            threadKick.kick(threadId);
          }
        });
  }
}
