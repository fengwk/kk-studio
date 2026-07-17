package fun.fengwk.kkstudio.core.harness.tool.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fun.fengwk.kkstudio.core.harness.run.service.HarnessRunTransactionService;
import fun.fengwk.kkstudio.core.harness.run.store.HarnessRunEventWriter;
import fun.fengwk.kkstudio.core.harness.run.store.mapper.HarnessRunMapper;
import fun.fengwk.kkstudio.core.harness.run.store.model.HarnessRunDO;
import fun.fengwk.kkstudio.core.harness.tool.store.MysqlToolInvocationStore;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.model.ToolInvocationDO;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventDraft;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventPayloads;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventType;
import fun.fengwk.kkstudio.harness.runtime.run.RunStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolPermissionDecision;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** WAITING_APPROVAL allow/deny 决策事务。 */
@Service
public class ToolInvocationDecisionService {
  private final ToolInvocationMapper invocationMapper;
  private final HarnessRunMapper runMapper;
  private final HarnessRunEventWriter eventWriter;
  private final MysqlToolInvocationStore invocationStore;
  private final HarnessRunTransactionService runTransactions;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public ToolInvocationDecisionService(
      ToolInvocationMapper invocationMapper,
      HarnessRunMapper runMapper,
      HarnessRunEventWriter eventWriter,
      MysqlToolInvocationStore invocationStore,
      HarnessRunTransactionService runTransactions,
      ObjectMapper objectMapper,
      Clock harnessRunClock) {
    this.invocationMapper = Objects.requireNonNull(invocationMapper, "invocationMapper");
    this.runMapper = Objects.requireNonNull(runMapper, "runMapper");
    this.eventWriter = Objects.requireNonNull(eventWriter, "eventWriter");
    this.invocationStore = Objects.requireNonNull(invocationStore, "invocationStore");
    this.runTransactions = Objects.requireNonNull(runTransactions, "runTransactions");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.clock = Objects.requireNonNull(harnessRunClock, "harnessRunClock");
  }

  @Transactional
  public ToolInvocation decide(long invocationId, ToolPermissionDecision decision) {
    Objects.requireNonNull(decision, "decision");
    ToolInvocationDO observed = invocationMapper.find(invocationId);
    if (observed == null) {
      throw new IllegalArgumentException("tool invocation not found");
    }
    HarnessRunDO run = runMapper.findForUpdate(observed.getRunId());
    if (run == null) {
      throw new IllegalStateException("tool invocation run does not exist: " + observed.getRunId());
    }
    // Run is locked; Session/Root before Invocation.
    eventWriter.lockSessionAndRoot(run.getSessionId());
    ToolInvocationDO current = invocationMapper.findForUpdate(invocationId);
    if (current == null || !Objects.equals(current.getRunId(), run.getId())) {
      throw new IllegalStateException("tool invocation changed while acquiring decision locks");
    }
    if (current.getPermissionDecision() != null) {
      if (!PermissionAction.ASK.name().equals(current.getPermissionAction())) {
        throw new IllegalStateException("decided invocation must have ASK permission action");
      }
      if (current.getPermissionDecision().equals(decision.name())) {
        return invocationStore.toInvocation(current);
      }
      throw new ToolDecisionConflictException(invocationId);
    }
    if (!RunStatus.WAITING_TOOLS.name().equals(run.getStatus())) {
      throw new ToolDecisionConflictException(invocationId);
    }
    if (!ToolInvocationStatus.WAITING_APPROVAL.name().equals(current.getStatus())) {
      throw new ToolDecisionConflictException(invocationId);
    }
    if (!PermissionAction.ASK.name().equals(current.getPermissionAction())) {
      throw new IllegalStateException(
          "WAITING_APPROVAL invocation must have ASK permission action");
    }

    Instant now = clock.instant();
    ToolInvocationStatus status =
        decision == ToolPermissionDecision.ALLOW
            ? ToolInvocationStatus.QUEUED
            : ToolInvocationStatus.FAILED;
    ToolInvocationStatus.WAITING_APPROVAL.requireTransitionTo(status);
    String errorMessage =
        decision == ToolPermissionDecision.DENY
            ? "Permission denied by user for " + current.getToolName() + "."
            : null;
    String resultJson =
        decision == ToolPermissionDecision.DENY
            ? deniedResult(current.getToolCallId(), errorMessage)
            : null;
    LocalDateTime deadlineAt =
        decision == ToolPermissionDecision.ALLOW
            ? resumedDeadline(current, now)
            : current.getDeadlineAt();
    LocalDateTime finishedAt = status.isTerminal() ? utc(now) : null;
    if (invocationMapper.resolvePermission(
            invocationId,
            ToolInvocationStatus.WAITING_APPROVAL.name(),
            decision.name(),
            status.name(),
            deadlineAt,
            resultJson,
            errorMessage,
            finishedAt,
            utc(now))
        != 1) {
      throw new ToolDecisionConflictException(invocationId);
    }
    runTransactions.appendExternalEvent(
        current.getRunId(),
        new RunEventDraft(
            RunEventType.PERMISSION_RESOLVED,
            RunEventPayloads.of(
                "invocationId",
                invocationId,
                "decision",
                decision.name(),
                "status",
                status.name())),
        now);
    return invocationStore.find(invocationId).orElseThrow();
  }

  private LocalDateTime resumedDeadline(ToolInvocationDO invocation, Instant now) {
    Duration timeout =
        Duration.between(instant(invocation.getCreateTime()), instant(invocation.getDeadlineAt()));
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalStateException("tool invocation timeout budget must be positive");
    }
    return utc(now.plus(timeout));
  }

  private String deniedResult(String toolCallId, String message) {
    ObjectNode result = objectMapper.createObjectNode();
    result.put("toolCallId", toolCallId);
    ObjectNode text = result.putArray("contents").addObject();
    text.put("type", "text");
    text.put("text", message);
    result.put("error", true);
    result.set("details", objectMapper.createObjectNode());
    try {
      return objectMapper.writeValueAsString(result);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("cannot encode denied tool result", error);
    }
  }

  private static LocalDateTime utc(Instant value) {
    return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
  }

  private static Instant instant(LocalDateTime value) {
    return value.toInstant(ZoneOffset.UTC);
  }
}
