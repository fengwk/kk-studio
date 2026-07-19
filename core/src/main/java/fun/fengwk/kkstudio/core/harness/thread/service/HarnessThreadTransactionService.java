package fun.fengwk.kkstudio.core.harness.thread.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.agent.support.AgentIdGenerator;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionEntryMapper;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionDO;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionEntryDO;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadInputMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadDO;
import fun.fengwk.kkstudio.core.harness.tool.service.ToolPolicyResolver;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.model.ToolInvocationDO;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.ArtifactMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.CompactionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.CustomMessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.ModelChangeEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryType;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolsetChangeEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.YoloChangeEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.AgentThread;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventDraft;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventPayloads;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventType;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadStop;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadStopStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadTransactions;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadTurnAdmission;
import fun.fengwk.kkstudio.harness.runtime.tool.PreparedToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolPreparationService;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolResultJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageDraft;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageRecord;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageRecordIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageRecordStore;
import fun.fengwk.kkstudio.harness.tool.ArtifactToolContent;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Thread/Input/Entry/Tool/Usage 原子事务边界。 */
@Service
public class HarnessThreadTransactionService implements ThreadTransactions {
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final HarnessThreadMapper threadMapper;
  private final HarnessThreadInputMapper inputMapper;
  private final HarnessSessionMapper sessionMapper;
  private final HarnessSessionEntryMapper entryMapper;
  private final ToolInvocationMapper invocationMapper;
  private final ThreadStore threadStore;
  private final ThreadInputStore inputStore;
  private final ThreadStopStore stopStore;
  private final ThreadEventStore eventStore;
  private final ThreadIdGenerator idGenerator;
  private final ModelUsageRecordStore usageRecordStore;
  private final ModelUsageRecordIdGenerator usageRecordIdGenerator;
  private final ToolPreparationService toolPreparationService;
  private final ToolPolicyResolver policyResolver;
  private final ThreadTurnAdmission turnAdmission;
  private final SessionEntryJsonCodec payloadCodec = new SessionEntryJsonCodec();

  public HarnessThreadTransactionService(
      HarnessThreadMapper threadMapper,
      HarnessThreadInputMapper inputMapper,
      HarnessSessionMapper sessionMapper,
      HarnessSessionEntryMapper entryMapper,
      ToolInvocationMapper invocationMapper,
      ThreadStore threadStore,
      ThreadInputStore inputStore,
      ThreadStopStore stopStore,
      ThreadEventStore eventStore,
      ThreadIdGenerator idGenerator,
      ModelUsageRecordStore usageRecordStore,
      ModelUsageRecordIdGenerator usageRecordIdGenerator,
      ToolPreparationService toolPreparationService,
      ToolPolicyResolver policyResolver,
      ThreadTurnAdmission turnAdmission) {
    this.threadMapper = Objects.requireNonNull(threadMapper, "threadMapper");
    this.inputMapper = Objects.requireNonNull(inputMapper, "inputMapper");
    this.sessionMapper = Objects.requireNonNull(sessionMapper, "sessionMapper");
    this.entryMapper = Objects.requireNonNull(entryMapper, "entryMapper");
    this.invocationMapper = Objects.requireNonNull(invocationMapper, "invocationMapper");
    this.threadStore = Objects.requireNonNull(threadStore, "threadStore");
    this.inputStore = Objects.requireNonNull(inputStore, "inputStore");
    this.stopStore = Objects.requireNonNull(stopStore, "stopStore");
    this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    this.usageRecordStore = Objects.requireNonNull(usageRecordStore, "usageRecordStore");
    this.usageRecordIdGenerator =
        Objects.requireNonNull(usageRecordIdGenerator, "usageRecordIdGenerator");
    this.toolPreparationService =
        Objects.requireNonNull(toolPreparationService, "toolPreparationService");
    this.policyResolver = Objects.requireNonNull(policyResolver, "policyResolver");
    this.turnAdmission = Objects.requireNonNull(turnAdmission, "turnAdmission");
  }

  @Override
  @Transactional
  public SessionCreateResult createSession(
      long agentDefinitionId,
      String title,
      AgentSnapshot snapshot,
      boolean yoloEnabled,
      Instant now) {
    Objects.requireNonNull(snapshot, "snapshot");
    long sessionId = AgentIdGenerator.nextHarnessSessionId();
    long snapshotEntryId = idGenerator.newSessionEntryId();
    long threadId = idGenerator.newThreadId();
    LocalDateTime timestamp = utc(now);

    HarnessSessionDO sessionRow = new HarnessSessionDO();
    sessionRow.setId(sessionId);
    sessionRow.setTitle(title);
    sessionRow.setMainThreadId(threadId);
    sessionRow.setParentSessionId(null);
    sessionRow.setRootSessionId(sessionId);
    sessionRow.setParentInvocationId(null);
    sessionRow.setDepth(0);
    sessionRow.setVersion(0L);
    sessionRow.setCreateTime(timestamp);
    sessionRow.setUpdateTime(timestamp);
    sessionMapper.insert(sessionRow);

    AgentSnapshotEntryPayload payload = new AgentSnapshotEntryPayload(agentDefinitionId, snapshot);
    insertEntry(snapshotEntryId, sessionId, null, payload, timestamp);

    long headEntryId = snapshotEntryId;
    if (yoloEnabled) {
      headEntryId = idGenerator.newSessionEntryId();
      insertEntry(
          headEntryId, sessionId, snapshotEntryId, new YoloChangeEntryPayload(true), timestamp);
    }

    AgentThread thread =
        new AgentThread(
            threadId, sessionId, headEntryId, ThreadStatus.IDLE, 0L, null, null, 0L, now, now);
    threadStore.create(thread);
    eventStore.append(
        threadId,
        headEntryId,
        ThreadEventType.THREAD_STARTED,
        ThreadEventPayloads.of(
            "sessionId", Long.toString(sessionId), "headEntryId", Long.toString(headEntryId)),
        now);
    return new SessionCreateResult(sessionId, thread);
  }

  @Override
  @Transactional
  public AgentThread createThreadFromEntry(long sessionId, long fromEntryId, Instant now) {
    if (sessionMapper.find(sessionId) == null) {
      throw new IllegalArgumentException("unknown session: " + sessionId);
    }
    if (entryMapper.find(sessionId, fromEntryId) == null) {
      throw new IllegalArgumentException("unknown entry: " + fromEntryId);
    }
    long threadId = idGenerator.newThreadId();
    AgentThread thread =
        new AgentThread(
            threadId, sessionId, fromEntryId, ThreadStatus.IDLE, 0L, null, null, 0L, now, now);
    threadStore.create(thread);
    eventStore.append(
        threadId,
        fromEntryId,
        ThreadEventType.THREAD_STARTED,
        ThreadEventPayloads.of(
            "sessionId",
            Long.toString(sessionId),
            "headEntryId",
            Long.toString(fromEntryId),
            "fork",
            true),
        now);
    return thread;
  }

  @Override
  @Transactional
  public ThreadInput submitUserMessage(
      long threadId, AgentMessage userMessage, String clientMessageId, Instant now) {
    Objects.requireNonNull(userMessage, "userMessage");
    if (userMessage.role() != AgentMessageRole.USER) {
      throw new IllegalArgumentException("submitted message must have USER role");
    }
    return enqueue(
        threadId,
        ThreadInputType.USER_MESSAGE,
        encodeUserMessage(userMessage),
        clientMessageId,
        now);
  }

  @Override
  @Transactional
  public ThreadInput submitCustomMessage(
      long threadId, AgentMessage customMessage, String clientMessageId, Instant now) {
    return enqueue(
        threadId,
        ThreadInputType.CUSTOM_MESSAGE,
        payloadCodec.encode(new CustomMessageEntryPayload(Objects.requireNonNull(customMessage))),
        clientMessageId,
        now);
  }

  @Override
  @Transactional
  public ThreadInput submitSetYolo(
      long threadId, boolean yoloEnabled, String clientMessageId, Instant now) {
    return enqueue(
        threadId,
        ThreadInputType.SET_YOLO,
        encodeSimple("yoloEnabled", yoloEnabled),
        clientMessageId,
        now);
  }

  @Override
  @Transactional
  public ThreadInput submitSetAgent(
      long threadId,
      long agentDefinitionId,
      AgentSnapshot snapshot,
      String clientMessageId,
      Instant now) {
    Objects.requireNonNull(snapshot, "snapshot");
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    node.put("agentDefinitionId", agentDefinitionId);
    node.set("snapshot", OBJECT_MAPPER.valueToTree(snapshot));
    return enqueue(threadId, ThreadInputType.SET_AGENT, write(node), clientMessageId, now);
  }

  @Override
  @Transactional
  public ThreadInput submitSetModel(
      long threadId, String modelId, String variant, String clientMessageId, Instant now) {
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    node.put("modelId", requireText(modelId, "modelId"));
    node.put("variant", requireText(variant, "variant"));
    return enqueue(threadId, ThreadInputType.SET_MODEL, write(node), clientMessageId, now);
  }

  @Override
  @Transactional
  public ThreadInput submitSetToolset(
      long threadId, List<String> tools, String clientMessageId, Instant now) {
    ArrayList<String> copy = new ArrayList<>(Objects.requireNonNull(tools, "tools"));
    if (copy.stream().anyMatch(tool -> tool == null || tool.isBlank())) {
      throw new IllegalArgumentException("tools must only contain non-blank values");
    }
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    node.set("tools", OBJECT_MAPPER.valueToTree(copy));
    return enqueue(threadId, ThreadInputType.SET_TOOLSET, write(node), clientMessageId, now);
  }

  @Override
  @Transactional
  public HarvestResult harvestQueuedInputs(long threadId, String processorToken, Instant now) {
    HarnessThreadDO thread = requireOwnedThread(threadId, processorToken);
    List<ThreadInput> queued = inputStore.listQueuedUpTo(threadId, thread.getInputSequence());
    if (queued.isEmpty()) {
      return HarvestResult.none();
    }
    LocalDateTime timestamp = utc(now);
    long parent = thread.getHeadEntryId();
    boolean hasMessage = false;
    List<ThreadInput> applied = new ArrayList<>();
    for (ThreadInput input : queued) {
      long entryId = idGenerator.newSessionEntryId();
      insertEntry(entryId, thread.getSessionId(), parent, decodeInput(input), timestamp);
      if (!inputStore.markApplied(input.id(), entryId, now)) {
        throw new ConcurrentModificationException("input already resolved");
      }
      eventStore.append(
          threadId,
          entryId,
          ThreadEventType.INPUT_APPLIED,
          ThreadEventPayloads.of(
              "inputId",
              Long.toString(input.id()),
              "sequence",
              input.sequence(),
              "type",
              input.inputType().value()),
          now);
      applied.add(
          new ThreadInput(
              input.id(),
              input.threadId(),
              input.sequence(),
              input.inputType(),
              input.payloadJson(),
              input.clientMessageId(),
              ThreadInputStatus.APPLIED,
              entryId,
              now,
              null,
              input.createdAt()));
      hasMessage |= input.inputType().isMessage();
      parent = entryId;
    }
    if (!threadStore.advanceHead(threadId, processorToken, thread.getHeadEntryId(), parent, now)) {
      throw new ConcurrentModificationException("cannot advance head");
    }
    return new HarvestResult(true, hasMessage, applied, parent);
  }

  @Override
  @Transactional
  public boolean commitFinalAssistant(
      long threadId,
      String processorToken,
      long plannedAssistantEntryId,
      MessageEntryPayload assistant,
      ModelUsageDraft usageDraft,
      List<ThreadEventDraft> events,
      Instant now) {
    HarnessThreadDO thread = findOwnedThread(threadId, processorToken);
    if (thread == null) {
      return false;
    }
    LocalDateTime timestamp = utc(now);
    insertEntry(
        plannedAssistantEntryId,
        thread.getSessionId(),
        thread.getHeadEntryId(),
        assistant,
        timestamp);
    usageRecordStore.insert(
        new ModelUsageRecord(
            usageRecordIdGenerator.newModelUsageRecordId(),
            thread.getSessionId(),
            threadId,
            plannedAssistantEntryId,
            usageDraft,
            now));
    if (!threadStore.advanceHead(
        threadId, processorToken, thread.getHeadEntryId(), plannedAssistantEntryId, now)) {
      // 已插入 entry/usage：必须回滚，禁止返回 false 提交孤儿行。
      throw new ConcurrentModificationException("cannot advance head for final assistant");
    }
    appendEventsInternal(threadId, events, now);
    return true;
  }

  @Override
  @Transactional
  public boolean prepareTools(
      long threadId,
      String processorToken,
      long plannedAssistantEntryId,
      MessageEntryPayload assistant,
      ModelUsageDraft usageDraft,
      List<ToolCall> toolCalls,
      List<ToolBinding> bindings,
      Path workdir,
      Path environmentRoot,
      boolean yoloEnabled,
      List<ThreadEventDraft> events,
      Instant now) {
    HarnessThreadDO thread = findOwnedThread(threadId, processorToken);
    if (thread == null) {
      return false;
    }
    LocalDateTime timestamp = utc(now);
    insertEntry(
        plannedAssistantEntryId,
        thread.getSessionId(),
        thread.getHeadEntryId(),
        assistant,
        timestamp);
    usageRecordStore.insert(
        new ModelUsageRecord(
            usageRecordIdGenerator.newModelUsageRecordId(),
            thread.getSessionId(),
            threadId,
            plannedAssistantEntryId,
            usageDraft,
            now));
    ToolPolicyResolver.ResolvedPolicy policy = policyResolver.resolve(yoloEnabled);
    List<PreparedToolInvocation> prepared =
        toolPreparationService.prepare(
            toolCalls,
            bindings,
            policy.settings(),
            policy.yoloEnabled(),
            workdir,
            environmentRoot,
            now);
    for (PreparedToolInvocation item : prepared) {
      invocationMapper.insert(toInvocationDO(threadId, plannedAssistantEntryId, item, timestamp));
    }
    if (!threadStore.advanceHead(
        threadId, processorToken, thread.getHeadEntryId(), plannedAssistantEntryId, now)) {
      throw new ConcurrentModificationException("cannot advance head for tool preparation");
    }
    List<ThreadEventDraft> all = new ArrayList<>(events);
    for (PreparedToolInvocation item : prepared) {
      all.add(
          new ThreadEventDraft(
              ThreadEventType.TOOL_PREPARED,
              plannedAssistantEntryId,
              ThreadEventPayloads.of(
                  "invocationId",
                  Long.toString(item.id()),
                  "toolCallId",
                  item.call().id(),
                  "toolName",
                  item.binding().descriptor().name(),
                  "argumentsJson",
                  item.call().argumentsJson(),
                  "status",
                  item.initialStatus().name(),
                  "ordinal",
                  item.ordinal())));
      if (item.permissionAction() == PermissionAction.ASK) {
        all.add(
            new ThreadEventDraft(
                ThreadEventType.PERMISSION_REQUESTED,
                plannedAssistantEntryId,
                ThreadEventPayloads.of(
                    "invocationId",
                    Long.toString(item.id()),
                    "tool",
                    item.promptPreview().tool(),
                    "workdir",
                    item.promptPreview().workdir(),
                    "arguments",
                    item.promptPreview().arguments())));
      }
      if (item.initialStatus().isTerminal()) {
        all.add(
            new ThreadEventDraft(
                ThreadEventType.TOOL_COMPLETED,
                plannedAssistantEntryId,
                ThreadEventPayloads.of(
                    "invocationId",
                    Long.toString(item.id()),
                    "toolCallId",
                    item.call().id(),
                    "status",
                    item.initialStatus().name(),
                    "error",
                    true,
                    "errorMessage",
                    item.errorMessage())));
      }
    }
    appendEventsInternal(threadId, all, now);
    return true;
  }

  @Override
  @Transactional
  public boolean applyTerminalToolResults(long threadId, String processorToken, Instant now) {
    HarnessThreadDO thread = findOwnedThread(threadId, processorToken);
    if (thread == null) {
      return false;
    }
    if (hasNonTerminalInvocationsForHead(thread)) {
      return false;
    }
    List<ToolInvocationDO> invocations = invocationMapper.listByThread(threadId);
    // Only apply results for the current head assistant that still need entries.
    List<ToolInvocationDO> forHead =
        invocations.stream()
            .filter(inv -> Objects.equals(inv.getAssistantEntryId(), thread.getHeadEntryId()))
            .sorted((a, b) -> Integer.compare(a.getOrdinal(), b.getOrdinal()))
            .toList();
    if (forHead.isEmpty()) {
      return false;
    }
    long parent = thread.getHeadEntryId();
    LocalDateTime timestamp = utc(now);
    for (ToolInvocationDO invocation : forHead) {
      if (invocation.getResultJson() == null) {
        throw new IllegalStateException("terminal invocation lacks result");
      }
      ToolResult result = ToolResultJsonCodec.decode(invocation.getResultJson());
      List<AgentMessageContent> contents = new ArrayList<>();
      for (ToolContent content : result.contents()) {
        contents.add(toMessageContent(content));
      }
      ToolResultMessageContent toolResult =
          new ToolResultMessageContent(
              invocation.getToolCallId(),
              invocation.getToolName(),
              contents,
              result.error(),
              result.detailsJson());
      MessageEntryPayload payload =
          new MessageEntryPayload(new AgentMessage(AgentMessageRole.TOOL, List.of(toolResult)));
      long entryId = idGenerator.newSessionEntryId();
      insertEntry(entryId, thread.getSessionId(), parent, payload, timestamp);
      if (!threadStore.advanceHead(threadId, processorToken, parent, entryId, now)) {
        throw new ConcurrentModificationException("cannot advance head for tool result");
      }
      parent = entryId;
    }
    eventStore.append(
        threadId,
        parent,
        ThreadEventType.TOOL_RESULTS_APPLIED,
        ThreadEventPayloads.of("count", forHead.size()),
        now);
    return true;
  }

  @Override
  @Transactional
  public boolean compact(
      long threadId,
      String processorToken,
      CompactionEntryPayload compaction,
      List<ThreadEventDraft> events,
      Instant now) {
    HarnessThreadDO thread = findOwnedThread(threadId, processorToken);
    if (thread == null) {
      return false;
    }
    long entryId = idGenerator.newSessionEntryId();
    insertEntry(entryId, thread.getSessionId(), thread.getHeadEntryId(), compaction, utc(now));
    if (!threadStore.advanceHead(threadId, processorToken, thread.getHeadEntryId(), entryId, now)) {
      throw new ConcurrentModificationException("cannot advance head for compaction");
    }
    appendEventsInternal(threadId, events, now);
    return true;
  }

  @Override
  @Transactional
  public boolean appendEvents(
      long threadId, String processorToken, List<ThreadEventDraft> events, Instant now) {
    if (findOwnedThread(threadId, processorToken) == null) {
      return false;
    }
    appendEventsInternal(threadId, events, now);
    return true;
  }

  @Override
  @Transactional
  public boolean fail(
      long threadId, String processorToken, List<ThreadEventDraft> events, Instant now) {
    if (findOwnedThread(threadId, processorToken) == null) {
      return false;
    }
    appendEventsInternal(threadId, events, now);
    threadMapper.forceStatusAndClearProcessor(threadId, ThreadStatus.FAILED.name(), utc(now));
    return true;
  }

  @Override
  @Transactional
  public AgentThread retry(long threadId, Instant now) {
    HarnessThreadDO thread = threadMapper.findForUpdate(threadId);
    if (thread == null) {
      throw new IllegalArgumentException("unknown thread: " + threadId);
    }
    if (!ThreadStatus.FAILED.name().equals(thread.getStatus())) {
      throw new IllegalStateException("thread is not failed");
    }
    threadMapper.updateStatusDirect(threadId, ThreadStatus.RETRYING.name(), utc(now));
    eventStore.append(
        threadId,
        thread.getHeadEntryId(),
        ThreadEventType.THREAD_RETRYING,
        ThreadEventPayloads.of("at", now),
        now);
    return threadStore
        .find(threadId)
        .orElseThrow(() -> new IllegalStateException("thread disappeared"));
  }

  @Override
  @Transactional
  public StopResult stop(long threadId, String clientRequestId, Instant now) {
    requireText(clientRequestId, "clientRequestId");
    ThreadStop replay = stopStore.findByClientRequestId(threadId, clientRequestId).orElse(null);
    if (replay != null) {
      return stopResult(replay);
    }
    HarnessThreadDO thread = threadMapper.findForUpdate(threadId);
    if (thread == null) {
      throw new IllegalArgumentException("unknown thread: " + threadId);
    }
    replay = stopStore.findByClientRequestId(threadId, clientRequestId).orElse(null);
    if (replay != null) {
      return stopResult(replay);
    }
    ThreadStop stop = new ThreadStop(idGenerator.newThreadStopId(), threadId, clientRequestId, now);
    try {
      stopStore.insert(stop);
    } catch (DataIntegrityViolationException duplicate) {
      return stopResult(
          stopStore.findByClientRequestId(threadId, clientRequestId).orElseThrow(() -> duplicate));
    }
    for (ThreadInput input : inputStore.listQueued(threadId)) {
      if (!inputStore.markCancelled(input.id(), stop.id(), now)) {
        throw new ConcurrentModificationException("input was resolved while stopping");
      }
      eventStore.append(
          threadId,
          null,
          ThreadEventType.INPUT_CANCELLED,
          ThreadEventPayloads.of(
              "inputId", Long.toString(input.id()), "sequence", input.sequence()),
          now);
    }
    invocationMapper.requestCancelByThread(threadId, utc(now));
    threadMapper.forceStatusAndClearProcessor(threadId, ThreadStatus.IDLE.name(), utc(now));
    eventStore.append(
        threadId,
        thread.getHeadEntryId(),
        ThreadEventType.THREAD_STOPPED,
        ThreadEventPayloads.of("stopId", Long.toString(stop.id())),
        now);
    return stopResult(stop);
  }

  @Override
  @Transactional
  public BeginTurnResult beginTurn(long threadId, String processorToken, Instant now) {
    // Locks Thread for update; admission locks child task (if any). cancelTree uses the same
    // Thread-then-task order so CANCELLED and TURN_STARTED never race across separate commits.
    if (findOwnedThread(threadId, processorToken) == null) {
      return BeginTurnResult.lostOwnership();
    }
    Optional<String> rejection = turnAdmission.evaluate(threadId);
    if (rejection.isPresent()) {
      String reason = rejection.get();
      appendEventsInternal(
          threadId,
          List.of(
              new ThreadEventDraft(
                  ThreadEventType.ASSISTANT_FAILED,
                  ThreadEventPayloads.of(
                      "kind", ProviderErrorKind.INVALID_REQUEST.name(), "message", reason)),
              new ThreadEventDraft(
                  ThreadEventType.THREAD_FAILED,
                  ThreadEventPayloads.of("reason", "turn_admission_rejected", "message", reason))),
          now);
      threadMapper.forceStatusAndClearProcessor(threadId, ThreadStatus.FAILED.name(), utc(now));
      return BeginTurnResult.rejected(reason);
    }
    appendEventsInternal(
        threadId,
        List.of(
            new ThreadEventDraft(ThreadEventType.TURN_STARTED, ThreadEventPayloads.of("at", now))),
        now);
    return BeginTurnResult.admitted();
  }

  @Override
  @Transactional
  public boolean completeRetriedTurn(long threadId, String processorToken, Instant now) {
    HarnessThreadDO thread = findOwnedThread(threadId, processorToken);
    if (thread == null || !ThreadStatus.RETRYING.name().equals(thread.getStatus())) {
      return false;
    }
    return threadStore.updateStatus(threadId, processorToken, ThreadStatus.RUNNING, now);
  }

  @Override
  @Transactional
  public boolean waitForExternal(long threadId, String processorToken, String reason, Instant now) {
    HarnessThreadDO thread = findOwnedThread(threadId, processorToken);
    if (thread == null) {
      return false;
    }
    if (!hasNonTerminalInvocationsForHead(thread)) {
      return false;
    }
    appendEventsInternal(
        threadId,
        List.of(
            new ThreadEventDraft(
                ThreadEventType.THREAD_WAITING, ThreadEventPayloads.of("reason", reason))),
        now);
    ThreadStatus waitingStatus =
        ThreadStatus.RETRYING.name().equals(thread.getStatus())
            ? ThreadStatus.RETRYING
            : ThreadStatus.WAITING;
    threadMapper.forceStatusAndClearProcessor(threadId, waitingStatus.name(), utc(now));
    return true;
  }

  @Override
  @Transactional
  public QuiescenceResult quiesce(long threadId, String processorToken, Instant now) {
    HarnessThreadDO thread = findOwnedThread(threadId, processorToken);
    if (thread == null) {
      return QuiescenceResult.LOST_OWNERSHIP;
    }
    if (hasImmediateDurableWork(thread)) {
      return QuiescenceResult.WORK_REMAINS;
    }
    appendEventsInternal(
        threadId,
        List.of(
            new ThreadEventDraft(
                ThreadEventType.THREAD_IDLE, ThreadEventPayloads.of("reason", "queue_empty"))),
        now);
    threadMapper.forceStatusAndClearProcessor(threadId, ThreadStatus.IDLE.name(), utc(now));
    return QuiescenceResult.IDLE;
  }

  /**
   * 当前 thread 是否仍有本节点应继续推进的 durable work。调用方已持有 thread 行锁；与 allocateInputSequence / tool
   * 状态提交在同一行上串行。
   */
  private boolean hasImmediateDurableWork(HarnessThreadDO thread) {
    long threadId = thread.getId();
    if (!inputStore.listQueued(threadId).isEmpty()) {
      return true;
    }
    if (hasNonTerminalInvocationsForHead(thread)) {
      return true;
    }
    return hasTerminalResultsPendingApply(thread);
  }

  private boolean hasTerminalResultsPendingApply(HarnessThreadDO thread) {
    long headEntryId = thread.getHeadEntryId();
    return invocationMapper.listByThread(thread.getId()).stream()
        .anyMatch(
            inv ->
                Objects.equals(inv.getAssistantEntryId(), headEntryId)
                    && isTerminalStatus(inv.getStatus()));
  }

  private boolean hasNonTerminalInvocationsForHead(HarnessThreadDO thread) {
    long headEntryId = thread.getHeadEntryId();
    return invocationMapper.listByThread(thread.getId()).stream()
        .anyMatch(
            invocation ->
                Objects.equals(invocation.getAssistantEntryId(), headEntryId)
                    && !isTerminalStatus(invocation.getStatus()));
  }

  private static boolean isTerminalStatus(String status) {
    if (status == null) {
      return false;
    }
    return switch (status) {
      case "SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN" -> true;
      default -> false;
    };
  }

  private ThreadInput enqueue(
      long threadId,
      ThreadInputType inputType,
      String payloadJson,
      String clientMessageId,
      Instant now) {
    requireText(clientMessageId, "clientMessageId");
    ThreadInput existing = inputStore.findByClientMessageId(threadId, clientMessageId).orElse(null);
    if (existing != null) {
      return existing;
    }
    HarnessThreadDO thread = threadMapper.findForUpdate(threadId);
    if (thread == null) {
      throw new IllegalArgumentException("unknown thread: " + threadId);
    }
    existing = inputStore.findByClientMessageId(threadId, clientMessageId).orElse(null);
    if (existing != null) {
      return existing;
    }
    if (threadMapper.allocateInputSequence(threadId, utc(now)) != 1) {
      throw new IllegalStateException("cannot allocate input sequence");
    }
    ThreadInput input =
        new ThreadInput(
            idGenerator.newThreadInputId(),
            threadId,
            thread.getInputSequence() + 1,
            inputType,
            payloadJson,
            clientMessageId,
            ThreadInputStatus.QUEUED,
            null,
            null,
            null,
            now);
    try {
      inputStore.insert(input);
    } catch (DataIntegrityViolationException duplicate) {
      return inputStore
          .findByClientMessageId(threadId, clientMessageId)
          .orElseThrow(() -> duplicate);
    }
    if (ThreadStatus.IDLE.name().equals(thread.getStatus())) {
      threadMapper.updateStatusDirect(threadId, ThreadStatus.RUNNING.name(), utc(now));
      eventStore.append(
          threadId,
          null,
          ThreadEventType.THREAD_RUNNING,
          ThreadEventPayloads.of("reason", "input_queued"),
          now);
    }
    return input;
  }

  private SessionEntryPayload decodeInput(ThreadInput input) {
    try {
      return switch (input.inputType()) {
        case USER_MESSAGE -> payloadCodec.decode(SessionEntryType.MESSAGE, input.payloadJson());
        case CUSTOM_MESSAGE -> payloadCodec.decode(
            SessionEntryType.CUSTOM_MESSAGE, input.payloadJson());
        case SET_YOLO -> new YoloChangeEntryPayload(readYolo(input.payloadJson()));
        case SET_AGENT -> {
          var node = OBJECT_MAPPER.readTree(input.payloadJson());
          yield new AgentSnapshotEntryPayload(
              node.path("agentDefinitionId").asLong(),
              OBJECT_MAPPER.treeToValue(node.get("snapshot"), AgentSnapshot.class));
        }
        case SET_MODEL -> {
          var node = OBJECT_MAPPER.readTree(input.payloadJson());
          yield new ModelChangeEntryPayload(
              node.path("modelId").asText(), node.path("variant").asText());
        }
        case SET_TOOLSET -> {
          var node = OBJECT_MAPPER.readTree(input.payloadJson());
          List<String> tools = new ArrayList<>();
          node.path("tools").forEach(value -> tools.add(value.asText()));
          yield new ToolsetChangeEntryPayload(tools);
        }
      };
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException(
          "cannot decode " + input.inputType().value() + " payload", error);
    }
  }

  private StopResult stopResult(ThreadStop stop) {
    List<ThreadInput> cancelled = inputStore.listCancelledByStop(stop.threadId(), stop.id());
    List<String> restored =
        cancelled.stream()
            .filter(input -> input.inputType().isMessage())
            .map(this::messageText)
            .toList();
    return new StopResult(stop, cancelled, restored);
  }

  private String messageText(ThreadInput input) {
    SessionEntryPayload payload = decodeInput(input);
    AgentMessage message =
        payload instanceof MessageEntryPayload value
            ? value.message()
            : ((CustomMessageEntryPayload) payload).message();
    return message.contents().stream()
        .filter(TextMessageContent.class::isInstance)
        .map(TextMessageContent.class::cast)
        .map(TextMessageContent::text)
        .reduce("", String::concat);
  }

  private void appendEventsInternal(long threadId, List<ThreadEventDraft> events, Instant now) {
    for (ThreadEventDraft draft : events) {
      eventStore.append(threadId, draft.subjectEntryId(), draft.type(), draft.payloadJson(), now);
    }
  }

  private HarnessThreadDO requireThread(long threadId) {
    HarnessThreadDO thread = threadMapper.find(threadId);
    if (thread == null) {
      throw new IllegalArgumentException("unknown thread: " + threadId);
    }
    return thread;
  }

  private HarnessThreadDO requireOwnedThread(long threadId, String processorToken) {
    HarnessThreadDO thread = findOwnedThread(threadId, processorToken);
    if (thread == null) {
      HarnessThreadDO existing = threadMapper.find(threadId);
      if (existing == null) {
        throw new IllegalArgumentException("unknown thread: " + threadId);
      }
      throw new ConcurrentModificationException("processor token mismatch");
    }
    return thread;
  }

  private HarnessThreadDO findOwnedThread(long threadId, String processorToken) {
    HarnessThreadDO thread = threadMapper.findForUpdate(threadId);
    if (thread == null) {
      return null;
    }
    if (!Objects.equals(thread.getProcessorToken(), processorToken)) {
      return null;
    }
    return thread;
  }

  private void insertEntry(
      long entryId,
      long sessionId,
      Long parentEntryId,
      SessionEntryPayload payload,
      LocalDateTime timestamp) {
    HarnessSessionEntryDO entry = new HarnessSessionEntryDO();
    entry.setId(entryId);
    entry.setSessionId(sessionId);
    entry.setParentEntryId(parentEntryId);
    entry.setEntryType(payload.type().value());
    entry.setPayloadJson(payloadCodec.encode(payload));
    entry.setCreateTime(timestamp);
    if (entryMapper.insert(entry) != 1) {
      throw new IllegalStateException("cannot insert session entry");
    }
  }

  private ToolInvocationDO toInvocationDO(
      long threadId, long assistantEntryId, PreparedToolInvocation prepared, LocalDateTime now) {
    ToolInvocationDO row = new ToolInvocationDO();
    row.setId(prepared.id());
    row.setThreadId(threadId);
    row.setAssistantEntryId(assistantEntryId);
    row.setOrdinal(prepared.ordinal());
    row.setToolCallId(prepared.call().id());
    row.setToolName(prepared.binding().descriptor().name());
    row.setToolVersion(prepared.binding().descriptor().version());
    row.setTargetType(prepared.binding().targetType().name());
    row.setEnvironmentId(prepared.binding().environmentId());
    row.setArgumentsJson(prepared.call().argumentsJson());
    row.setStatus(prepared.initialStatus().name());
    row.setPermissionAction(prepared.permissionAction().name());
    row.setSideEffect(prepared.binding().descriptor().sideEffect().name());
    row.setDeadlineAt(utc(prepared.deadlineAt()));
    row.setResultJson(prepared.resultJson());
    row.setErrorMessage(prepared.errorMessage());
    row.setCreateTime(now);
    row.setUpdateTime(now);
    if (prepared.initialStatus().isTerminal()) {
      row.setFinishedAt(now);
    }
    return row;
  }

  private static boolean readYolo(String payloadJson) {
    try {
      return OBJECT_MAPPER.readTree(payloadJson).path("yoloEnabled").asBoolean();
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("cannot decode set_yolo payload", error);
    }
  }

  private static AgentSetPayload readAgentSet(String payloadJson) {
    try {
      var node = OBJECT_MAPPER.readTree(payloadJson);
      long agentDefinitionId = node.path("agentDefinitionId").asLong();
      String runtimeConfigJson = node.path("runtimeConfigJson").asText();
      AgentSnapshot snapshot = OBJECT_MAPPER.treeToValue(node.get("snapshot"), AgentSnapshot.class);
      return new AgentSetPayload(agentDefinitionId, runtimeConfigJson, snapshot);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("cannot decode set_agent payload", error);
    }
  }

  private record AgentSetPayload(
      long agentDefinitionId, String runtimeConfigJson, AgentSnapshot snapshot) {}

  private static AgentMessageContent toMessageContent(ToolContent content) {
    if (content instanceof TextToolContent text) {
      return new TextMessageContent(text.text());
    }
    if (content instanceof JsonToolContent json) {
      return new JsonMessageContent(json.json());
    }
    if (content instanceof ArtifactToolContent artifact) {
      return new ArtifactMessageContent(
          artifact.artifact().artifactId(), artifact.artifact().mediaType(), null);
    }
    throw new IllegalArgumentException("unsupported ToolResult content: " + content.getClass());
  }

  private String encodeUserMessage(AgentMessage message) {
    return payloadCodec.encode(new MessageEntryPayload(message));
  }

  private AgentMessage decodeUserMessage(String json) {
    SessionEntryPayload payload = payloadCodec.decode(SessionEntryType.MESSAGE, json);
    if (!(payload instanceof MessageEntryPayload message)) {
      throw new IllegalArgumentException("user message payload must be MESSAGE type");
    }
    return message.message();
  }

  private static String encodeSimple(String key, boolean value) {
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    node.put(key, value);
    return write(node);
  }

  private static String write(ObjectNode node) {
    try {
      return OBJECT_MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("cannot encode payload", error);
    }
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static LocalDateTime utc(Instant value) {
    return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
  }
}
