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
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadInputDO;
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
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryType;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.AgentThread;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventDraft;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventPayloads;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventType;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;
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
  public AgentThread createRootThread(
      long agentDefinitionId,
      String title,
      AgentSnapshot snapshot,
      String runtimeConfigJson,
      boolean yoloEnabled,
      Instant now) {
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(runtimeConfigJson, "runtimeConfigJson");
    long sessionId = AgentIdGenerator.nextHarnessSessionId();
    long snapshotEntryId = idGenerator.newSessionEntryId();
    long threadId = idGenerator.newThreadId();
    LocalDateTime timestamp = utc(now);

    Session session = Session.root(sessionId, agentDefinitionId, title, now);
    HarnessSessionDO sessionRow = new HarnessSessionDO();
    sessionRow.setId(session.id());
    sessionRow.setAgentDefinitionId(session.agentDefinitionId());
    sessionRow.setTitle(session.title());
    sessionRow.setParentSessionId(null);
    sessionRow.setRootSessionId(session.rootSessionId());
    sessionRow.setParentInvocationId(null);
    sessionRow.setDepth(0);
    sessionRow.setVersion(0L);
    sessionRow.setCreateTime(timestamp);
    sessionRow.setUpdateTime(timestamp);
    sessionMapper.insert(sessionRow);

    AgentSnapshotEntryPayload payload = new AgentSnapshotEntryPayload(snapshot);
    insertEntry(snapshotEntryId, sessionId, null, payload, timestamp);

    AgentThread thread =
        new AgentThread(
            threadId,
            sessionId,
            snapshotEntryId,
            agentDefinitionId,
            runtimeConfigJson,
            yoloEnabled,
            0L,
            null,
            null,
            0L,
            now,
            now);
    threadStore.create(thread);
    eventStore.append(
        threadId,
        snapshotEntryId,
        ThreadEventType.THREAD_STARTED,
        ThreadEventPayloads.of(
            "sessionId", Long.toString(sessionId), "headEntryId", Long.toString(snapshotEntryId)),
        now);
    return thread;
  }

  @Override
  @Transactional
  public AgentThread createThreadFromEntry(
      long sessionId,
      long fromEntryId,
      Long agentDefinitionId,
      String runtimeConfigJson,
      boolean yoloEnabled,
      Instant now) {
    Objects.requireNonNull(runtimeConfigJson, "runtimeConfigJson");
    if (sessionMapper.find(sessionId) == null) {
      throw new IllegalArgumentException("unknown session: " + sessionId);
    }
    if (entryMapper.find(sessionId, fromEntryId) == null) {
      throw new IllegalArgumentException("unknown entry: " + fromEntryId);
    }
    long threadId = idGenerator.newThreadId();
    AgentThread thread =
        new AgentThread(
            threadId,
            sessionId,
            fromEntryId,
            agentDefinitionId,
            runtimeConfigJson,
            yoloEnabled,
            0L,
            null,
            null,
            0L,
            now,
            now);
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
    if (clientMessageId != null) {
      ThreadInput existing =
          inputStore.findByClientMessageId(threadId, clientMessageId).orElse(null);
      if (existing != null) {
        return existing;
      }
    }
    requireThread(threadId);
    long sequence = threadStore.allocateInputSequence(threadId, now);
    long inputId = idGenerator.newThreadInputId();
    String payload = encodeUserMessage(userMessage);
    ThreadInput input =
        new ThreadInput(
            inputId,
            threadId,
            sequence,
            ThreadInputType.USER_MESSAGE,
            payload,
            clientMessageId,
            null,
            null,
            now);
    try {
      inputStore.insert(input);
      return input;
    } catch (DataIntegrityViolationException race) {
      // 并发双 miss findByClientMessageId 后唯一键冲突：返回已存在行，不分配第二条语义输入。
      if (clientMessageId == null) {
        throw race;
      }
      return inputStore.findByClientMessageId(threadId, clientMessageId).orElseThrow(() -> race);
    }
  }

  @Override
  @Transactional
  public ThreadInput submitSetYolo(long threadId, boolean yoloEnabled, Instant now) {
    requireThread(threadId);
    long sequence = threadStore.allocateInputSequence(threadId, now);
    long inputId = idGenerator.newThreadInputId();
    String payload = encodeSimple("yoloEnabled", yoloEnabled);
    ThreadInput input =
        new ThreadInput(
            inputId, threadId, sequence, ThreadInputType.SET_YOLO, payload, null, null, null, now);
    inputStore.insert(input);
    return input;
  }

  @Override
  @Transactional
  public ThreadInput submitSetAgent(
      long threadId,
      long agentDefinitionId,
      AgentSnapshot snapshot,
      String runtimeConfigJson,
      Instant now) {
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(runtimeConfigJson, "runtimeConfigJson");
    requireThread(threadId);
    long sequence = threadStore.allocateInputSequence(threadId, now);
    long inputId = idGenerator.newThreadInputId();
    ObjectNode node = OBJECT_MAPPER.createObjectNode();
    node.put("agentDefinitionId", agentDefinitionId);
    node.put("runtimeConfigJson", runtimeConfigJson);
    node.set("snapshot", OBJECT_MAPPER.valueToTree(snapshot));
    ThreadInput input =
        new ThreadInput(
            inputId,
            threadId,
            sequence,
            ThreadInputType.SET_AGENT,
            write(node),
            null,
            null,
            null,
            now);
    inputStore.insert(input);
    return input;
  }

  @Override
  @Transactional
  public ApplyInputResult applyNextInput(long threadId, String processorToken, Instant now) {
    HarnessThreadDO thread = requireOwnedThread(threadId, processorToken);
    HarnessThreadInputDO pending = inputMapper.findNextPending(threadId);
    if (pending == null) {
      return ApplyInputResult.none();
    }
    HarnessThreadInputDO locked = inputMapper.findForUpdate(pending.getId());
    if (locked == null || locked.getAppliedEntryId() != null) {
      return ApplyInputResult.none();
    }
    long entryId = idGenerator.newSessionEntryId();
    LocalDateTime timestamp = utc(now);
    ThreadInputType type = ThreadInputType.fromValue(locked.getInputType());
    switch (type) {
      case USER_MESSAGE -> {
        AgentMessage message = decodeUserMessage(locked.getPayloadJson());
        MessageEntryPayload payload = new MessageEntryPayload(message);
        insertEntry(entryId, thread.getSessionId(), thread.getHeadEntryId(), payload, timestamp);
      }
      case SET_YOLO -> {
        boolean yolo = readYolo(locked.getPayloadJson());
        if (!threadStore.updateYolo(threadId, processorToken, yolo, now)) {
          throw new ConcurrentModificationException("lost processor ownership");
        }
        // no entry; mark applied to head itself
        entryId = thread.getHeadEntryId();
      }
      case SET_AGENT -> {
        AgentSetPayload agentSet = readAgentSet(locked.getPayloadJson());
        AgentSnapshotEntryPayload payload = new AgentSnapshotEntryPayload(agentSet.snapshot());
        insertEntry(entryId, thread.getSessionId(), thread.getHeadEntryId(), payload, timestamp);
        if (!threadStore.updateAgent(
            threadId,
            processorToken,
            agentSet.agentDefinitionId(),
            agentSet.runtimeConfigJson(),
            now)) {
          throw new ConcurrentModificationException("lost processor ownership");
        }
      }
    }
    if (inputMapper.markApplied(locked.getId(), entryId, timestamp) != 1) {
      throw new ConcurrentModificationException("input already applied");
    }
    if (type != ThreadInputType.SET_YOLO
        && !threadStore.advanceHead(
            threadId, processorToken, thread.getHeadEntryId(), entryId, now)) {
      throw new ConcurrentModificationException("cannot advance head");
    }
    eventStore.append(
        threadId,
        entryId,
        ThreadEventType.INPUT_APPLIED,
        ThreadEventPayloads.of(
            "inputId",
            Long.toString(locked.getId()),
            "sequence",
            locked.getSequence(),
            "type",
            type.value()),
        now);
    ThreadInput applied =
        inputStore
            .findById(locked.getId())
            .orElseThrow(() -> new IllegalStateException("applied input missing"));
    return new ApplyInputResult(true, applied, entryId);
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
    ToolPolicyResolver.ResolvedPolicy policy =
        policyResolver.resolve(Boolean.TRUE.equals(thread.getYoloEnabled()));
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
    if (invocationMapper.countNonTerminalByThread(threadId) != 0) {
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
    // If tool result entries already exist as children of head, skip.
    if (!entryMapper.listChildren(thread.getSessionId(), thread.getHeadEntryId()).isEmpty()) {
      // may already applied; treat as progressed if head advanced externally
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
    return true;
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
  public boolean releaseIfIdle(long threadId, String processorToken, Instant now) {
    HarnessThreadDO thread = threadMapper.findForUpdate(threadId);
    if (thread == null || !Objects.equals(thread.getProcessorToken(), processorToken)) {
      return true;
    }
    if (hasImmediateDurableWork(thread)) {
      return false;
    }
    return threadMapper.release(threadId, processorToken, utc(now)) == 1;
  }

  @Override
  @Transactional
  public boolean releaseForExternalWait(long threadId, String processorToken, Instant now) {
    HarnessThreadDO thread = threadMapper.findForUpdate(threadId);
    if (thread == null || !Objects.equals(thread.getProcessorToken(), processorToken)) {
      return true;
    }
    // 任意非终态 tool（含 WAITING_APPROVAL）仍需外部推进：释放 token。pending input 不阻止释放。
    if (invocationMapper.countNonTerminalByThread(threadId) != 0) {
      return threadMapper.release(threadId, processorToken, utc(now)) == 1;
    }
    // 无非终态 tool，但当前 head 有可 apply 的终态结果：保留 token 继续。
    if (hasTerminalResultsPendingApply(thread)) {
      return false;
    }
    return false;
  }

  /**
   * 当前 thread 是否仍有本节点应继续推进的 durable work。调用方已持有 thread 行锁；与 allocateInputSequence / tool
   * 状态提交在同一行上串行。
   */
  private boolean hasImmediateDurableWork(HarnessThreadDO thread) {
    long threadId = thread.getId();
    if (inputMapper.findNextPending(threadId) != null) {
      return true;
    }
    if (invocationMapper.countNonTerminalByThread(threadId) != 0) {
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

  private static boolean isTerminalStatus(String status) {
    if (status == null) {
      return false;
    }
    return switch (status) {
      case "SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN" -> true;
      default -> false;
    };
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

  private static LocalDateTime utc(Instant value) {
    return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
  }
}
