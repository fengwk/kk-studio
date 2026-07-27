package fun.fengwk.kkstudio.core.harness.thread.reconcile;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.harness.configuration.HarnessRuntimeProperties;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.continuation.ContinuationRef;
import fun.fengwk.kkstudio.harness.runtime.entry.AssistantErrorEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.EntryType;
import fun.fengwk.kkstudio.harness.runtime.entry.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.RuntimeEntryPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.execution.Lease;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationErrorJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.model.plan.ModelInvocationPlanner;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderRequestJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderResponseJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.port.HarnessIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.reconcile.ApplyOutcome;
import fun.fengwk.kkstudio.harness.runtime.reconcile.ModelCreationOutcome;
import fun.fengwk.kkstudio.harness.runtime.reconcile.ModelInvocationPlan;
import fun.fengwk.kkstudio.harness.runtime.reconcile.QuiesceOutcome;
import fun.fengwk.kkstudio.harness.runtime.reconcile.SuspendOutcome;
import fun.fengwk.kkstudio.harness.runtime.reconcile.ThreadOwnership;
import fun.fengwk.kkstudio.harness.runtime.reconcile.ThreadReconcileSnapshot;
import fun.fengwk.kkstudio.harness.runtime.reconcile.ThreadReconcileTransactions;
import fun.fengwk.kkstudio.harness.runtime.reconcile.TurnBoundary;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ArtifactMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.HarnessThread;
import fun.fengwk.kkstudio.harness.runtime.thread.InputStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.RuntimeConfigInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.RuntimeEntryInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationErrorJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolResultJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageDraft;
import fun.fengwk.kkstudio.harness.tool.ArtifactToolContent;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL final-schema {@link ThreadReconcileTransactions} implementation.
 *
 * <p>每个 mutation 先锁 Thread，再读取/修改 Invocation；完整 epoch/token lease fence 在 SQL 中重验。配置仅来自 Entry/Input
 * 快照，terminal model apply 会从 immutable source-head path 重算并比对冻结 request。
 */
@Service
public class PostgresqlThreadReconcileTransactions implements ThreadReconcileTransactions {

  private static final ProviderRequestJsonCodec REQUEST_CODEC = new ProviderRequestJsonCodec();
  private static final ProviderResponseJsonCodec RESPONSE_CODEC = new ProviderResponseJsonCodec();
  private static final ModelInvocationErrorJsonCodec MODEL_ERROR_CODEC =
      new ModelInvocationErrorJsonCodec();
  private static final ToolInvocationErrorJsonCodec TOOL_ERROR_CODEC =
      new ToolInvocationErrorJsonCodec();
  private static final RuntimeEntryPayloadJsonCodec ENTRY_CODEC =
      new RuntimeEntryPayloadJsonCodec();
  private static final ThreadInputPayloadJsonCodec INPUT_CODEC = new ThreadInputPayloadJsonCodec();
  private static final ToolDescriptorJsonCodec TOOL_DESCRIPTOR_CODEC =
      new ToolDescriptorJsonCodec();

  private final ThreadReconcileMapper mapper;
  private final HarnessIdGenerator ids;
  private final ModelInvocationPlanner planner;
  private final Duration leaseDuration;

  @Autowired
  public PostgresqlThreadReconcileTransactions(
      ThreadReconcileMapper mapper, HarnessIdGenerator ids, HarnessRuntimeProperties properties) {
    this(mapper, ids, new ModelInvocationPlanner(), properties.getThreadReconcileLeaseDuration());
  }

  PostgresqlThreadReconcileTransactions(
      ThreadReconcileMapper mapper,
      HarnessIdGenerator ids,
      ModelInvocationPlanner planner,
      Duration leaseDuration) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.ids = Objects.requireNonNull(ids, "ids");
    this.planner = Objects.requireNonNull(planner, "planner");
    this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
    if (leaseDuration.isZero() || leaseDuration.isNegative()) {
      throw new IllegalArgumentException("thread reconcile lease duration must be positive");
    }
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Optional<ThreadOwnership> claim(long threadId, String processorToken, Instant now) {
    requirePositive(threadId, "threadId");
    requireToken(processorToken);
    OffsetDateTime timestamp = timestamp(now);
    Long epoch =
        mapper.claim(threadId, processorToken, timestamp(now.plus(leaseDuration)), timestamp);
    return epoch == null
        ? Optional.empty()
        : Optional.of(new ThreadOwnership(threadId, epoch, processorToken));
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public boolean renew(ThreadOwnership ownership, Instant now) {
    Objects.requireNonNull(ownership, "ownership");
    return mapper.renew(
            ownership.threadId(),
            ownership.executionEpoch(),
            ownership.processorToken(),
            timestamp(now.plus(leaseDuration)),
            timestamp(now))
        == 1;
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Optional<ThreadReconcileSnapshot> loadOwnedSnapshot(
      ThreadOwnership ownership, Instant now) {
    ThreadReconcileRow thread = owned(ownership, now);
    if (thread == null) return Optional.empty();
    ThreadReconcileRow terminal =
        mapper.findTerminalModel(
            thread.getId(), thread.getHeadEntryId(), ownership.executionEpoch());
    Optional<Long> terminalId = Optional.ofNullable(terminal).map(ThreadReconcileRow::getId);
    List<ThreadReconcileRow> siblings =
        terminal == null
            ? mapper.listToolSiblings(
                thread.getId(), thread.getHeadEntryId(), ownership.executionEpoch())
            : List.of();
    boolean someToolsApplied =
        siblings.stream().anyMatch(sibling -> sibling.getAppliedAt() != null);
    if (someToolsApplied && siblings.stream().anyMatch(sibling -> sibling.getAppliedAt() == null)) {
      throw new IllegalStateException("tool sibling batch has partially applied terminal state");
    }
    boolean readyTools =
        !siblings.isEmpty()
            && !someToolsApplied
            && siblings.stream().allMatch(PostgresqlThreadReconcileTransactions::terminal);
    Optional<ContinuationRef> blocker =
        terminal == null && !readyTools
            ? blocker(thread, ownership.executionEpoch())
            : Optional.empty();
    List<ThreadInput> queued = toInputs(mapper.listQueuedInputs(thread.getId()));
    Optional<ModelInvocationPlan> plan =
        terminal == null && !readyTools && blocker.isEmpty() ? plan(thread) : Optional.empty();
    return Optional.of(
        new ThreadReconcileSnapshot(
            ownership,
            toThread(thread),
            terminalId,
            readyTools ? Optional.of(thread.getHeadEntryId()) : Optional.empty(),
            blocker,
            queued,
            plan));
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ApplyOutcome applyTerminalModel(
      ThreadOwnership ownership, long modelInvocationId, Instant now) {
    ThreadReconcileRow thread = owned(ownership, now);
    if (thread == null) return ApplyOutcome.LOST_OWNERSHIP;
    ThreadReconcileRow invocation =
        mapper.findTerminalModel(
            thread.getId(), thread.getHeadEntryId(), ownership.executionEpoch());
    if (invocation == null || invocation.getId() != modelInvocationId)
      return ApplyOutcome.LOST_OWNERSHIP;
    EntryPayload payload;
    long sourceHeadEntryId = invocation.getSourceHeadEntryId();
    long entryId;
    if ("SUCCEEDED".equals(invocation.getStatus())) {
      ProviderRequest request = REQUEST_CODEC.decode(invocation.getRequestJson());
      Optional<ModelInvocationPlan> replanned = planAt(thread, sourceHeadEntryId);
      if (replanned.isEmpty() || !request.equals(replanned.get().request())) {
        throw new IllegalStateException(
            "terminal model request no longer matches immutable source-head plan");
      }
      ProviderResponse response = RESPONSE_CODEC.decode(invocation.getResultJson());
      payload = assistantPayload(response);
      entryId = ids.nextEntryId();
      insertEntry(thread, entryId, payload, now);
      insertUsage(thread, entryId, ModelUsageDraft.from(request, response), now);
      materializeTools(
          thread,
          entryId,
          ownership.executionEpoch(),
          response,
          replanned.get().configSnapshot(),
          now);
      advance(thread, ownership, entryId, now);
    } else {
      payload = new AssistantErrorEntryPayload(modelError(invocation));
      entryId = ids.nextEntryId();
      insertEntry(thread, entryId, payload, now);
      advance(thread, ownership, entryId, now);
    }
    if (mapper.applyModel(
            modelInvocationId,
            ownership.threadId(),
            sourceHeadEntryId,
            entryId,
            ownership.executionEpoch(),
            ownership.processorToken(),
            timestamp(now))
        != 1) {
      throw new IllegalStateException("terminal model disappeared while applying");
    }
    return ApplyOutcome.PROGRESSED;
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ApplyOutcome applyTerminalToolResults(
      ThreadOwnership ownership, long assistantEntryId, Instant now) {
    ThreadReconcileRow thread = owned(ownership, now);
    if (thread == null || thread.getHeadEntryId() != assistantEntryId)
      return ApplyOutcome.LOST_OWNERSHIP;
    List<ThreadReconcileRow> tools =
        mapper.listToolSiblings(thread.getId(), assistantEntryId, ownership.executionEpoch());
    if (tools.isEmpty()) return ApplyOutcome.LOST_OWNERSHIP;
    boolean someToolsApplied = tools.stream().anyMatch(tool -> tool.getAppliedAt() != null);
    if (someToolsApplied) {
      if (tools.stream().allMatch(tool -> tool.getAppliedAt() != null)) {
        return ApplyOutcome.LOST_OWNERSHIP;
      }
      throw new IllegalStateException("tool sibling batch has partially applied terminal state");
    }
    if (tools.stream().anyMatch(tool -> !terminal(tool))) return ApplyOutcome.LOST_OWNERSHIP;
    long parent = assistantEntryId;
    for (ThreadReconcileRow tool : tools) {
      ToolDescriptor descriptor = TOOL_DESCRIPTOR_CODEC.decode(tool.getDescriptorJson());
      ToolResultMessageContent content = toolResult(tool, descriptor);
      long entryId = ids.nextEntryId();
      EntryPayload payload =
          new MessageEntryPayload(new AgentMessage(AgentMessageRole.TOOL, List.of(content)));
      if (mapper.insertEntry(
              entryId,
              thread.getSessionId(),
              parent,
              payload.type().name(),
              ENTRY_CODEC.encode(payload),
              timestamp(now))
          != 1) throw new IllegalStateException("cannot insert tool result entry");
      parent = entryId;
    }
    advance(thread, ownership, parent, now);
    if (mapper.applyTools(
            thread.getId(),
            assistantEntryId,
            parent,
            ownership.executionEpoch(),
            ownership.processorToken(),
            timestamp(now))
        != tools.size())
      throw new IllegalStateException("terminal tool siblings changed while applying");
    return ApplyOutcome.PROGRESSED;
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public SuspendOutcome suspendAndRecheck(
      ThreadOwnership ownership, ContinuationRef expectedBlocker, Instant now) {
    ThreadReconcileRow thread = owned(ownership, now);
    if (thread == null) return SuspendOutcome.LOST_OWNERSHIP;
    Optional<ContinuationRef> current = blocker(thread, ownership.executionEpoch());
    // The expected blocker is not immediate work by itself. Only its replacement/disappearance,
    // terminal siblings, or new mailbox work means this activation must continue.
    if (!expectedBlocker.equals(current.orElse(null))
        || hasWorkExceptExpected(thread, expectedBlocker)) {
      return SuspendOutcome.WORK_AVAILABLE;
    }
    return release(ownership, false, now)
        ? SuspendOutcome.SUSPENDED
        : SuspendOutcome.LOST_OWNERSHIP;
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ApplyOutcome harvestBoundary(
      ThreadOwnership ownership, TurnBoundary boundary, Instant now) {
    ThreadReconcileRow thread = owned(ownership, now);
    if (thread == null) return ApplyOutcome.LOST_OWNERSHIP;
    List<ThreadInput> durable = toInputs(mapper.listQueuedInputs(thread.getId()));
    if (!sameBoundary(boundary, durable)) return ApplyOutcome.LOST_OWNERSHIP;
    long parent = thread.getHeadEntryId();
    for (ThreadInput input : boundary.inputs()) {
      EntryPayload payload = inputPayload(input);
      long entryId = ids.nextEntryId();
      if (mapper.insertEntry(
                  entryId,
                  thread.getSessionId(),
                  parent,
                  payload.type().name(),
                  ENTRY_CODEC.encode(payload),
                  timestamp(now))
              != 1
          || mapper.applyInput(
                  input.id(),
                  input.threadId(),
                  input.sequence(),
                  input.type().name(),
                  timestamp(now))
              != 1) {
        throw new IllegalStateException("cannot atomically harvest queued input");
      }
      parent = entryId;
    }
    advance(thread, ownership, parent, now);
    return ApplyOutcome.PROGRESSED;
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ModelCreationOutcome createModelInvocationAndRelease(
      ThreadOwnership ownership, ModelInvocationPlan plan, Instant now) {
    ThreadReconcileRow thread = owned(ownership, now);
    if (thread == null || thread.getHeadEntryId() != plan.sourceHeadEntryId()) {
      return new ModelCreationOutcome.LostOwnership();
    }
    ModelInvocationPlan durablePlan =
        plan(thread)
            .orElseThrow(
                () -> new IllegalStateException("current thread head has no response debt"));
    if (!durablePlan.equals(plan)) {
      throw new IllegalStateException("model invocation plan does not match durable thread path");
    }
    long id = ids.nextModelInvocationId();
    if (mapper.insertModelInvocation(
            id,
            thread.getId(),
            thread.getHeadEntryId(),
            ownership.executionEpoch(),
            REQUEST_CODEC.encode(plan.request()),
            timestamp(now))
        != 1) {
      throw new IllegalStateException("cannot create model invocation");
    }
    if (!release(ownership, false, now))
      throw new IllegalStateException("cannot release created model invocation");
    return new ModelCreationOutcome.Created(
        new ExecutionTarget(ExecutionTargetKind.MODEL_INVOCATION, id));
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public QuiesceOutcome quiesceAndRecheck(ThreadOwnership ownership, Instant now) {
    ThreadReconcileRow thread = owned(ownership, now);
    if (thread == null) return QuiesceOutcome.LOST_OWNERSHIP;
    if (hasAnyWork(thread) || plan(thread).isPresent()) return QuiesceOutcome.WORK_AVAILABLE;
    return release(ownership, false, now)
        ? QuiesceOutcome.QUIESCENT
        : QuiesceOutcome.LOST_OWNERSHIP;
  }

  @Override
  public void bestEffortRelease(ThreadOwnership ownership, Instant now) {
    try {
      release(Objects.requireNonNull(ownership, "ownership"), true, now);
    } catch (RuntimeException ignored) {
      // Explicitly best-effort; the expiry/recovery path remains available.
    }
  }

  private ThreadReconcileRow owned(ThreadOwnership ownership, Instant now) {
    Objects.requireNonNull(ownership, "ownership");
    ThreadReconcileRow thread = mapper.lockThread(ownership.threadId());
    if (thread == null
        || !thread.isRunnable()
        || thread.getExecutionEpoch() != ownership.executionEpoch()
        || !ownership.processorToken().equals(thread.getProcessorToken())
        || thread.getProcessorUntil() == null
        || !thread.getProcessorUntil().isAfter(timestamp(now))) return null;
    return thread;
  }

  private Optional<ModelInvocationPlan> plan(ThreadReconcileRow thread) {
    return planAt(thread, thread.getHeadEntryId());
  }

  private Optional<ModelInvocationPlan> planAt(ThreadReconcileRow thread, long headEntryId) {
    return planner.plan(
        thread.getSessionId(), headEntryId, path(thread.getSessionId(), headEntryId));
  }

  private List<SessionEntry> path(long sessionId, long headEntryId) {
    return mapper.loadPath(sessionId, headEntryId).stream()
        .map(
            row ->
                new SessionEntry(
                    row.getId(),
                    row.getParentEntryId(),
                    ENTRY_CODEC.decode(
                        EntryType.valueOf(row.getEntryType()), row.getPayloadJson())))
        .toList();
  }

  private Optional<ContinuationRef> blocker(ThreadReconcileRow thread, long epoch) {
    ThreadReconcileRow model =
        mapper.findModelBlocker(thread.getId(), thread.getHeadEntryId(), epoch);
    if (model != null)
      return Optional.of(ref(thread.getId(), ExecutionTargetKind.MODEL_INVOCATION, model.getId()));
    ThreadReconcileRow tool =
        mapper.findToolBlocker(thread.getId(), thread.getHeadEntryId(), epoch);
    return tool == null
        ? Optional.empty()
        : Optional.of(ref(thread.getId(), ExecutionTargetKind.TOOL_INVOCATION, tool.getId()));
  }

  private boolean hasAnyWork(ThreadReconcileRow thread) {
    return mapper.findTerminalModel(
                thread.getId(), thread.getHeadEntryId(), thread.getExecutionEpoch())
            != null
        || mapper
            .listToolSiblings(thread.getId(), thread.getHeadEntryId(), thread.getExecutionEpoch())
            .stream()
            .anyMatch(sibling -> sibling.getAppliedAt() == null)
        || blocker(thread, thread.getExecutionEpoch()).isPresent()
        || !mapper.listQueuedInputs(thread.getId()).isEmpty();
  }

  private boolean hasWorkExceptExpected(ThreadReconcileRow thread, ContinuationRef expected) {
    if (mapper.findTerminalModel(
            thread.getId(), thread.getHeadEntryId(), thread.getExecutionEpoch())
        != null) return true;
    List<ThreadReconcileRow> siblings =
        mapper.listToolSiblings(
            thread.getId(), thread.getHeadEntryId(), thread.getExecutionEpoch());
    if (!siblings.isEmpty()
        && siblings.stream().allMatch(PostgresqlThreadReconcileTransactions::terminal)
        && siblings.stream().anyMatch(sibling -> sibling.getAppliedAt() == null)) return true;
    return !expected.equals(blocker(thread, thread.getExecutionEpoch()).orElse(null));
  }

  private void materializeTools(
      ThreadReconcileRow thread,
      long assistantEntryId,
      long epoch,
      ProviderResponse response,
      RuntimeConfigSnapshot config,
      Instant now) {
    for (int ordinal = 0; ordinal < response.toolCalls().size(); ordinal++) {
      var call = response.toolCalls().get(ordinal);
      ToolBinding binding =
          config.tools().stream()
              .filter(candidate -> candidate.descriptor().name().equals(call.name()))
              .findFirst()
              .orElseThrow(
                  () -> new IllegalStateException("model called an unbound tool: " + call.name()));
      if (mapper.insertToolInvocation(
              ids.nextToolInvocationId(),
              thread.getId(),
              thread.getSessionId(),
              assistantEntryId,
              ordinal,
              call.id(),
              TOOL_DESCRIPTOR_CODEC.encode(binding.descriptor()),
              call.argumentsJson(),
              binding.location().name(),
              binding.environmentName(),
              epoch,
              timestamp(now))
          != 1) {
        throw new IllegalStateException("cannot materialize tool invocation");
      }
    }
  }

  private static EntryPayload assistantPayload(ProviderResponse response) {
    List<AgentMessageContent> contents = new ArrayList<>();
    if (!response.thinking().isEmpty())
      contents.add(new ThinkingMessageContent(response.thinking()));
    if (!response.text().isEmpty()) contents.add(new TextMessageContent(response.text()));
    response
        .toolCalls()
        .forEach(
            call ->
                contents.add(
                    new ToolCallMessageContent(call.id(), call.name(), call.argumentsJson())));
    if (contents.isEmpty()) contents.add(new TextMessageContent(""));
    return new MessageEntryPayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, contents),
        new AssistantMessageMetadata(response.stopReason(), response.usage(), response.cost()));
  }

  private static ModelInvocationError modelError(ThreadReconcileRow invocation) {
    if ("CANCELLED".equals(invocation.getStatus()))
      return new ModelInvocationError(ProviderErrorKind.CANCELLED, "model invocation cancelled");
    return MODEL_ERROR_CODEC.decode(
        Objects.requireNonNull(invocation.getErrorJson(), "terminal model error"));
  }

  private static ToolResultMessageContent toolResult(
      ThreadReconcileRow row, ToolDescriptor descriptor) {
    if ("SUCCEEDED".equals(row.getStatus())) {
      ToolResult result = ToolResultJsonCodec.decode(row.getResultJson());
      if (!row.getToolCallId().equals(result.toolCallId())) {
        throw new IllegalStateException("tool result toolCallId does not match invocation");
      }
      return new ToolResultMessageContent(
          result.toolCallId(),
          descriptor.name(),
          contents(result.contents()),
          result.error(),
          result.detailsJson());
    }
    ToolInvocationError error =
        "CANCELLED".equals(row.getStatus())
            ? new ToolInvocationError("CANCELLED", "tool invocation cancelled")
            : TOOL_ERROR_CODEC.decode(
                Objects.requireNonNull(row.getErrorJson(), "terminal tool error"));
    return new ToolResultMessageContent(
        row.getToolCallId(),
        descriptor.name(),
        List.of(new TextMessageContent(error.message())),
        true,
        TOOL_ERROR_CODEC.encode(error));
  }

  private static List<AgentMessageContent> contents(List<ToolContent> contents) {
    List<AgentMessageContent> mapped = new ArrayList<>();
    for (ToolContent content : contents) {
      if (content instanceof TextToolContent text) mapped.add(new TextMessageContent(text.text()));
      else if (content instanceof JsonToolContent json)
        mapped.add(new JsonMessageContent(json.json()));
      else if (content instanceof ArtifactToolContent artifact)
        mapped.add(
            new ArtifactMessageContent(
                artifact.artifact().artifactId(), artifact.artifact().mediaType(), null));
      else throw new IllegalArgumentException("unsupported tool content: " + content.getClass());
    }
    return mapped.isEmpty() ? List.of(new TextMessageContent("")) : List.copyOf(mapped);
  }

  private static List<ThreadInput> toInputs(List<ThreadReconcileRow> rows) {
    return rows.stream().map(PostgresqlThreadReconcileTransactions::toInput).toList();
  }

  private static ThreadInput toInput(ThreadReconcileRow row) {
    ThreadInputType type = ThreadInputType.valueOf(row.getInputType());
    return new ThreadInput(
        row.getId(),
        row.getThreadId(),
        row.getSequence(),
        type,
        INPUT_CODEC.decode(type, row.getPayloadJson()),
        row.getIdempotencyKey(),
        InputStatus.valueOf(row.getStatus()),
        row.getCreatedAt().toInstant(),
        row.getAppliedAt() == null ? null : row.getAppliedAt().toInstant());
  }

  private static EntryPayload inputPayload(ThreadInput input) {
    if (input.payload() instanceof RuntimeConfigInputPayload config) return config.snapshot();
    if (input.payload() instanceof RuntimeEntryInputPayload entry) return entry.payload();
    throw new IllegalArgumentException(
        "unsupported typed input payload: " + input.payload().getClass());
  }

  private static HarnessThread toThread(ThreadReconcileRow row) {
    Lease lease =
        row.getProcessorToken() == null
            ? null
            : new Lease(row.getProcessorToken(), row.getProcessorUntil().toInstant());
    return new HarnessThread(
        row.getId(),
        row.getHeadEntryId(),
        row.getInputSequence(),
        row.isRunnable(),
        row.getExecutionEpoch(),
        lease,
        row.getCreatedAt().toInstant(),
        row.getUpdatedAt().toInstant());
  }

  private void insertEntry(ThreadReconcileRow thread, long id, EntryPayload payload, Instant now) {
    if (mapper.insertEntry(
            id,
            thread.getSessionId(),
            thread.getHeadEntryId(),
            payload.type().name(),
            ENTRY_CODEC.encode(payload),
            timestamp(now))
        != 1) throw new IllegalStateException("cannot insert entry");
  }

  private void insertUsage(
      ThreadReconcileRow thread, long assistantEntryId, ModelUsageDraft draft, Instant now) {
    if (mapper.insertModelUsage(
            thread.getSessionId(), thread.getId(), assistantEntryId, draft, timestamp(now))
        != 1) {
      throw new IllegalStateException("cannot insert model usage");
    }
  }

  private void advance(
      ThreadReconcileRow thread, ThreadOwnership ownership, long newHead, Instant now) {
    if (mapper.advanceHead(
            thread.getId(),
            thread.getHeadEntryId(),
            newHead,
            ownership.executionEpoch(),
            ownership.processorToken(),
            timestamp(now))
        != 1) throw new IllegalStateException("cannot advance fenced thread head");
  }

  private boolean release(ThreadOwnership ownership, boolean runnable, Instant now) {
    return mapper.release(
            ownership.threadId(),
            ownership.executionEpoch(),
            ownership.processorToken(),
            runnable,
            timestamp(now))
        == 1;
  }

  private static boolean terminal(ThreadReconcileRow row) {
    return switch (row.getStatus()) {
      case "SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN" -> true;
      default -> false;
    };
  }

  private static ContinuationRef ref(long threadId, ExecutionTargetKind kind, long id) {
    return new ContinuationRef(
        new ExecutionTarget(ExecutionTargetKind.THREAD, threadId), new ExecutionTarget(kind, id));
  }

  private static boolean sameBoundary(TurnBoundary expected, List<ThreadInput> actual) {
    if (expected.threadId() <= 0 || actual.size() < expected.inputs().size()) return false;
    for (int index = 0; index < expected.inputs().size(); index++) {
      ThreadInput expectedInput = expected.inputs().get(index);
      ThreadInput actualInput = actual.get(index);
      if (expectedInput.threadId() != expected.threadId()
          || actualInput.threadId() != expected.threadId()
          || !expectedInput.equals(actualInput)) return false;
    }
    return true;
  }

  private static OffsetDateTime timestamp(Instant instant) {
    return OffsetDateTime.ofInstant(
        Objects.requireNonNull(instant, "now").truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
  }

  private static void requirePositive(long value, String name) {
    if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
  }

  private static void requireToken(String value) {
    if (value == null || value.isBlank() || value.length() > 128)
      throw new IllegalArgumentException("processor token must be non-blank and <= 128 chars");
  }
}
