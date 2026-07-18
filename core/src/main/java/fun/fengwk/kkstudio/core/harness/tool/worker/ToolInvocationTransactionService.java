package fun.fengwk.kkstudio.core.harness.tool.worker;

import com.fasterxml.jackson.databind.JsonNode;
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
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ClaimedToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationTransactions;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolResultJsonCodec;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/** 原子写入 Tool 状态。锁序：非锁 peek → 锁 Thread → 锁 invocation → 校验仍属该 Thread，避免与 processor 死锁。 */
@Service
public class ToolInvocationTransactionService implements ToolInvocationTransactions {
  private final ToolInvocationMapper invocationMapper;
  private final HarnessThreadMapper threadMapper;
  private final ThreadEventStore eventStore;
  private final ThreadKick threadKick;

  public ToolInvocationTransactionService(
      ToolInvocationMapper invocationMapper,
      HarnessThreadMapper threadMapper,
      ThreadEventStore eventStore,
      @Lazy ThreadKick threadKick) {
    this.invocationMapper = Objects.requireNonNull(invocationMapper, "invocationMapper");
    this.threadMapper = Objects.requireNonNull(threadMapper, "threadMapper");
    this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
    this.threadKick = Objects.requireNonNull(threadKick, "threadKick");
  }

  @Override
  @Transactional
  public boolean start(ClaimedToolInvocation claimed, Instant now) {
    ToolInvocationDO row = lockOwnedClaim(claimed);
    if (row == null) {
      return false;
    }
    eventStore.append(
        row.getThreadId(),
        row.getAssistantEntryId(),
        ThreadEventType.TOOL_STARTED,
        ThreadEventPayloads.of(
            "invocationId",
            Long.toString(row.getId()),
            "toolCallId",
            row.getToolCallId(),
            "toolName",
            row.getToolName()),
        now);
    return true;
  }

  @Override
  @Transactional
  public boolean appendPartial(
      ClaimedToolInvocation claimed, List<ToolResult> partials, Instant now) {
    ToolInvocationDO row = lockOwnedClaim(claimed);
    if (row == null) {
      return false;
    }
    if (partials == null || partials.isEmpty()) {
      return true;
    }
    eventStore.append(
        row.getThreadId(),
        row.getAssistantEntryId(),
        ThreadEventType.TOOL_DELTA_BATCH,
        ThreadEventPayloads.of(
            "invocationId",
            Long.toString(row.getId()),
            "toolCallId",
            row.getToolCallId(),
            "partialResults",
            canonicalResults(partials)),
        now);
    return true;
  }

  @Override
  @Transactional
  public boolean terminate(
      ClaimedToolInvocation claimed,
      ToolInvocationStatus terminalStatus,
      ToolResult result,
      String errorMessage,
      Instant now) {
    ToolInvocationDO row = lockInvocationOwningThreadFirst(claimed.invocation().id());
    if (row == null) {
      return false;
    }
    if (ToolInvocationStatus.valueOf(row.getStatus()).isTerminal()) {
      // 已终态：幂等观察，kick 以便 processor 收敛。
      afterCommitKick(row.getThreadId());
      return true;
    }
    // 非终态必须仍由 claim 的 lease owner 持有；禁止用当前行 owner 代替 stale claim。
    if (!ownerMatches(claimed, row)) {
      return false;
    }
    String claimedOwner =
        blankToNull(claimed.invocation().leaseOwner()) == null
            ? ""
            : claimed.invocation().leaseOwner();
    String resultJson = result == null ? null : ToolResultJsonCodec.encode(result);
    int updated =
        invocationMapper.terminateOwned(
            row.getId(), claimedOwner, terminalStatus.name(), resultJson, errorMessage, utc(now));
    if (updated != 1) {
      afterCommitKick(row.getThreadId());
      return false;
    }
    if (result != null) {
      eventStore.append(
          row.getThreadId(),
          row.getAssistantEntryId(),
          ThreadEventType.TOOL_DELTA_BATCH,
          ThreadEventPayloads.of(
              "invocationId",
              Long.toString(row.getId()),
              "toolCallId",
              row.getToolCallId(),
              "partialResults",
              List.of(ToolResultJsonCodec.encodeNode(result))),
          now);
    }
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
            terminalStatus.name(),
            "error",
            terminalStatus != ToolInvocationStatus.SUCCEEDED,
            "errorMessage",
            errorMessage),
        now);
    afterCommitKick(row.getThreadId());
    return true;
  }

  private static List<JsonNode> canonicalResults(List<ToolResult> partials) {
    return partials.stream().map(ToolResultJsonCodec::encodeNode).toList();
  }

  /** start/partial：校验 durable lease owner/status 仍匹配 claim。 */
  private ToolInvocationDO lockOwnedClaim(ClaimedToolInvocation claimed) {
    ToolInvocationDO row = lockInvocationOwningThreadFirst(claimed.invocation().id());
    if (row == null) {
      return null;
    }
    if (!ownerMatches(claimed, row)) {
      return null;
    }
    if (ToolInvocationStatus.valueOf(row.getStatus()).isTerminal()) {
      return null;
    }
    return row;
  }

  private static boolean ownerMatches(ClaimedToolInvocation claimed, ToolInvocationDO row) {
    return Objects.equals(
        blankToNull(claimed.invocation().leaseOwner()), blankToNull(row.getLeaseOwner()));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  /** 锁序固定：非锁查找 threadId → 锁 Thread → 锁 invocation → 校验仍属于该 Thread。 */
  private ToolInvocationDO lockInvocationOwningThreadFirst(long invocationId) {
    ToolInvocationDO peek = invocationMapper.find(invocationId);
    if (peek == null) {
      return null;
    }
    long threadId = peek.getThreadId();
    if (threadMapper.findForUpdate(threadId) == null) {
      throw new IllegalStateException("owning thread missing for invocation " + invocationId);
    }
    ToolInvocationDO row = invocationMapper.findForUpdate(invocationId);
    if (row == null || !Objects.equals(row.getThreadId(), threadId)) {
      return null;
    }
    return row;
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

  private static LocalDateTime utc(Instant value) {
    return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
  }
}
