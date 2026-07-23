package fun.fengwk.kkstudio.core.harness.thread.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.agent.definition.repo.impl.mapper.AgentDefinitionMapper;
import fun.fengwk.kkstudio.core.agent.definition.repo.impl.model.AgentDefinitionDO;
import fun.fengwk.kkstudio.core.agent.model.repo.impl.mapper.AgentModelMapper;
import fun.fengwk.kkstudio.core.agent.model.repo.impl.model.AgentModelDO;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionEntryMapper;
import fun.fengwk.kkstudio.core.harness.session.store.model.HarnessSessionEntryDO;
import fun.fengwk.kkstudio.core.harness.thread.store.MysqlHarnessThreadStore;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadEventMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadInputMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadDO;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadEventDO;
import fun.fengwk.kkstudio.core.harness.thread.store.model.HarnessThreadInputDO;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.model.ToolInvocationDO;
import fun.fengwk.kkstudio.harness.runtime.session.AgentChangeEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantErrorEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.CompactionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.ModelChangeEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryType;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.AgentThread;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventDraft;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventPayloads;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadEventType;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadTransactions;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Durable transaction contracts for the Thread mailbox, cursor, lifecycle, and fencing boundaries.
 */
@SpringBootTest
@Transactional
class HarnessThreadTransactionServiceIntegrationTest {
  private static final SessionEntryJsonCodec ENTRY_CODEC = new SessionEntryJsonCodec();
  private static final long SEED_AGENT_ID = 1L;
  private static final String SEED_AGENT_NAME = "default-assistant";

  @Autowired private ThreadTransactions transactions;
  @Autowired private MysqlHarnessThreadStore threadStore;
  @Autowired private HarnessThreadMapper threadMapper;
  @Autowired private HarnessThreadInputMapper inputMapper;
  @Autowired private HarnessSessionEntryMapper entryMapper;
  @Autowired private HarnessThreadEventMapper eventMapper;
  @Autowired private ToolInvocationMapper invocationMapper;
  @Autowired private AgentDefinitionMapper agentDefinitionMapper;
  @Autowired private AgentModelMapper agentModelMapper;

  @Test
  void createsAgentlessRootAndRejectsUnknownBranchOrigins() {
    Instant now = Instant.parse("2026-02-01T00:00:00Z");
    ThreadTransactions.SessionCreateResult plain = transactions.createSession("plain", false, now);
    ThreadTransactions.SessionCreateResult yolo =
        transactions.createSession("yolo", true, now.plusSeconds(1));

    List<HarnessSessionEntryDO> plainEntries = entryMapper.listBySession(plain.sessionId());
    assertEquals(
        List.of("root"), plainEntries.stream().map(HarnessSessionEntryDO::getEntryType).toList());
    HarnessThreadDO plainThread = threadMapper.find(plain.mainThread().id());
    assertEquals(plainEntries.get(0).getId(), plainThread.getHeadEntryId());
    assertNull(plainThread.getActiveAgentDefinitionId());
    assertFalse(Boolean.TRUE.equals(plainThread.getYoloEnabled()));

    List<HarnessSessionEntryDO> yoloEntries = entryMapper.listBySession(yolo.sessionId());
    assertEquals(
        List.of("root"), yoloEntries.stream().map(HarnessSessionEntryDO::getEntryType).toList());
    HarnessThreadDO yoloThread = threadMapper.find(yolo.mainThread().id());
    assertEquals(yoloEntries.get(0).getId(), yoloThread.getHeadEntryId());
    assertTrue(Boolean.TRUE.equals(yoloThread.getYoloEnabled()));

    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.createThreadFromEntry(9_999_999L, plainEntries.get(0).getId(), now));
    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.createThreadFromEntry(plain.sessionId(), 9_999_998L, now));
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.createThreadFromEntry(plain.sessionId(), yoloEntries.get(0).getId(), now));
  }

  /**
   * 分支创建语义：无 Agent 路径保持空；路径取最后一次 AGENT_CHANGE + MODEL_CHANGE 写出当前生效配置；缺失 Definition 原子失败且不落
   * Thread；不追加合成 AGENT_CHANGE。
   */
  @Test
  void initializesBranchThreadCurrentConfigFromPathAgentAndModelChanges() {
    Instant now = Instant.parse("2026-02-08T00:00:00Z");
    ThreadTransactions.SessionCreateResult created =
        transactions.createSession("branch-agent", false, now);
    long sessionId = created.sessionId();
    long mainThreadId = created.mainThread().id();
    long rootEntryId = created.mainThread().headEntryId();

    // 无 AGENT_CHANGE 时 Secondary Thread 仍为空 Agent / 空 model / yolo=false。
    AgentThread agentlessBranch =
        transactions.createThreadFromEntry(sessionId, rootEntryId, now.plusSeconds(1));
    assertEquals(rootEntryId, agentlessBranch.headEntryId());
    assertNull(agentlessBranch.activeAgentDefinitionId());
    assertNull(agentlessBranch.activeAgentName());
    assertNull(agentlessBranch.modelId());
    assertNull(agentlessBranch.variant());
    assertFalse(agentlessBranch.yoloEnabled());
    assertEquals(
        1, eventMapper.countByType(agentlessBranch.id(), ThreadEventType.THREAD_STARTED.value()));
    assertEquals(
        0, eventMapper.countByType(agentlessBranch.id(), ThreadEventType.AGENT_CHANGED.value()));

    long secondModelId = 9_100_000L;
    AgentModelDO secondModel = new AgentModelDO();
    secondModel.setId(secondModelId);
    secondModel.setProviderId(1L);
    secondModel.setName("branch-second-model");
    secondModel.setDescription("model for branch definition selection");
    secondModel.setConfigJson(
        """
        {"limit":{"context":32768,"output":4096},"abilities":{"tools":true,"reasoning":false,"inputModalities":["TEXT"]},"defaultVariant":"initial","variants":[{"id":"initial"},{"id":"current"}],"pricing":{"currency":"USD","pricingTier":"test","serviceTier":"default","serviceTierMultiplier":1,"version":"v1","inputPerMillionTokens":0,"outputPerMillionTokens":0,"cacheReadPerMillionTokens":0,"cacheWritePerMillionTokens":0,"cacheWriteLongPerMillionTokens":0,"reasoningPerMillionTokens":0}}
        """);
    assertEquals(1, agentModelMapper.insert(secondModel));

    long secondAgentId = 9_100_001L;
    AgentDefinitionDO secondAgent = new AgentDefinitionDO();
    secondAgent.setId(secondAgentId);
    secondAgent.setName("branch-second-agent");
    secondAgent.setDescription("second agent for path selection");
    secondAgent.setSystemPrompt("second");
    secondAgent.setModelId(secondModelId);
    secondAgent.setVariant("initial");
    secondAgent.setConfigJson(
        "{\"tools\":[],\"skills\":[],\"allowedSubagents\":[],\"executionPolicy\":{}}");
    assertEquals(1, agentDefinitionMapper.insert(secondAgent));

    // enqueue 会把 IDLE 推到 RUNNING，之后才能 tryAcquire。
    transactions.submitSetAgent(
        mainThreadId, SEED_AGENT_ID, "captured-seed-name", "set-seed", now.plusSeconds(3));
    transactions.submitSetAgent(
        mainThreadId, secondAgentId, "captured-second-name", "set-second", now.plusSeconds(4));
    // 父 Thread 显式 model override 记录为 MODEL_CHANGE，分支从路径恢复该当前生效配置。
    transactions.submitSetModel(mainThreadId, "1", "default", "set-model", now.plusSeconds(5));
    assertTrue(
        threadStore
            .tryAcquire(mainThreadId, "branch-owner", now.plusSeconds(2), Duration.ofMinutes(1))
            .isPresent());
    ThreadTransactions.HarvestResult harvest =
        transactions.harvestQueuedInputs(mainThreadId, "branch-owner", now.plusSeconds(6));
    assertTrue(harvest.harvested());
    HarnessThreadDO mainAfter = threadMapper.find(mainThreadId);
    assertEquals(secondAgentId, mainAfter.getActiveAgentDefinitionId());
    assertEquals("1", mainAfter.getModelId());
    assertEquals("default", mainAfter.getVariant());

    List<HarnessSessionEntryDO> entriesBeforeBranch = entryMapper.listBySession(sessionId);
    // SET_AGENT → AGENT_CHANGE + MODEL_CHANGE；再 SET_MODEL → 额外 MODEL_CHANGE（配置变更记录）。
    assertEquals(
        List.of(
            "root", "agent_change", "model_change", "agent_change", "model_change", "model_change"),
        entriesBeforeBranch.stream().map(HarnessSessionEntryDO::getEntryType).toList());
    long headAfterConfig = mainAfter.getHeadEntryId();
    assertEquals(entriesBeforeBranch.get(5).getId(), headAfterConfig);

    // 更新当前 Definition 不得覆盖路径上已写入的 MODEL_CHANGE。
    secondAgent.setModelId(secondModelId);
    secondAgent.setVariant("current");
    assertEquals(1, agentDefinitionMapper.updateById(secondAgent));

    int threadCountBefore = threadMapper.listBySession(sessionId).size();
    int entryCountBefore = entryMapper.listBySession(sessionId).size();
    AgentThread branch =
        transactions.createThreadFromEntry(sessionId, headAfterConfig, now.plusSeconds(7));
    assertEquals(threadCountBefore + 1, threadMapper.listBySession(sessionId).size());
    assertEquals(entryCountBefore, entryMapper.listBySession(sessionId).size());
    assertEquals(headAfterConfig, branch.headEntryId());
    assertEquals(secondAgentId, branch.activeAgentDefinitionId());
    assertEquals("captured-second-name", branch.activeAgentName());
    // 从 head 分支：当前生效 model/variant 取路径 last MODEL_CHANGE（SET_MODEL），而非 Definition。
    assertEquals("1", branch.modelId());
    assertEquals("default", branch.variant());
    assertFalse(branch.yoloEnabled());
    assertEquals(1, eventMapper.countByType(branch.id(), ThreadEventType.THREAD_STARTED.value()));
    assertEquals(0, eventMapper.countByType(branch.id(), ThreadEventType.AGENT_CHANGED.value()));

    // 从 seed AGENT_CHANGE 分支：路径尚无 MODEL_CHANGE，回退 seed 当前 Definition。
    long seedAgentChangeId = entriesBeforeBranch.get(1).getId();
    AgentThread olderBranch =
        transactions.createThreadFromEntry(sessionId, seedAgentChangeId, now.plusSeconds(8));
    assertEquals(SEED_AGENT_ID, olderBranch.activeAgentDefinitionId());
    assertEquals("captured-seed-name", olderBranch.activeAgentName());
    assertEquals("1", olderBranch.modelId());
    assertEquals("default", olderBranch.variant());

    // 历史 Definition 删除后分支创建必须原子失败，不落 Thread。
    assertEquals(1, agentDefinitionMapper.deleteById(secondAgentId));
    int threadCountBeforeMissing = threadMapper.listBySession(sessionId).size();
    IllegalArgumentException missing =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                transactions.createThreadFromEntry(sessionId, headAfterConfig, now.plusSeconds(9)));
    assertTrue(missing.getMessage().startsWith("unknown agent definition:"));
    assertEquals(threadCountBeforeMissing, threadMapper.listBySession(sessionId).size());
    assertEquals(entryCountBefore, entryMapper.listBySession(sessionId).size());
  }

  @Test
  void harvestsConfigInputsOntoThreadStateWithoutFullAgentSecrets() {
    Instant now = Instant.parse("2026-02-02T00:00:00Z");
    ThreadTransactions.SessionCreateResult created =
        transactions.createSession("config", false, now);
    long threadId = created.mainThread().id();

    ThreadInput agent =
        transactions.submitSetAgent(threadId, SEED_AGENT_ID, SEED_AGENT_NAME, "agent", now);
    assertEquals(
        agent.id(),
        transactions
            .submitSetAgent(threadId, SEED_AGENT_ID, SEED_AGENT_NAME, "agent", now.plusSeconds(1))
            .id());
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.submitSetAgent(
                threadId, SEED_AGENT_ID, "other-name", "agent", now.plusSeconds(2)));

    ThreadInput model = transactions.submitSetModel(threadId, "model-2", "variant-2", "model", now);
    assertEquals(
        model.id(),
        transactions
            .submitSetModel(threadId, "model-2", "variant-2", "model", now.plusSeconds(1))
            .id());
    ThreadInput yolo = transactions.submitSetYolo(threadId, true, "yolo", now);
    assertEquals(
        yolo.id(), transactions.submitSetYolo(threadId, true, "yolo", now.plusSeconds(1)).id());

    assertTrue(
        threadStore.tryAcquire(threadId, "config-owner", now, Duration.ofMinutes(1)).isPresent());
    ThreadTransactions.HarvestResult harvest =
        transactions.harvestQueuedInputs(threadId, "config-owner", now.plusSeconds(3));

    assertTrue(harvest.harvested());
    assertFalse(harvest.hasMessage());
    assertEquals(
        List.of(agent.id(), model.id(), yolo.id()),
        harvest.applied().stream().map(ThreadInput::id).toList());

    HarnessThreadDO thread = threadMapper.find(threadId);
    assertEquals(SEED_AGENT_ID, thread.getActiveAgentDefinitionId());
    assertEquals(SEED_AGENT_NAME, thread.getActiveAgentName());
    assertEquals("model-2", thread.getModelId());
    assertEquals("variant-2", thread.getVariant());
    assertTrue(Boolean.TRUE.equals(thread.getYoloEnabled()));

    List<HarnessSessionEntryDO> entries = entryMapper.listBySession(created.sessionId());
    // SET_AGENT → agent_change + model_change；SET_MODEL → 再写 model_change；SET_YOLO 不写 Entry。
    assertEquals(
        List.of("root", "agent_change", "model_change", "model_change"),
        entries.stream().map(HarnessSessionEntryDO::getEntryType).toList());
    AgentChangeEntryPayload change = payload(entries.get(1), AgentChangeEntryPayload.class);
    assertEquals(SEED_AGENT_ID, change.agentDefinitionId());
    assertEquals(SEED_AGENT_NAME, change.agentName());
    assertFalse(entries.get(1).getPayloadJson().contains("systemPrompt"));
    assertFalse(entries.get(1).getPayloadJson().contains("snapshot"));
    assertFalse(entries.get(1).getPayloadJson().contains("tools"));
    assertFalse(entries.get(1).getPayloadJson().contains("executionPolicy"));
    ModelChangeEntryPayload setAgentModel = payload(entries.get(2), ModelChangeEntryPayload.class);
    assertEquals("1", setAgentModel.modelId());
    ModelChangeEntryPayload setModel = payload(entries.get(3), ModelChangeEntryPayload.class);
    assertEquals("model-2", setModel.modelId());
    assertEquals("variant-2", setModel.variant());

    // SET_AGENT 也会发 MODEL_CHANGED；再加一次 SET_MODEL。
    assertEquals(1, eventMapper.countByType(threadId, ThreadEventType.AGENT_CHANGED.value()));
    assertEquals(2, eventMapper.countByType(threadId, ThreadEventType.MODEL_CHANGED.value()));
    assertEquals(1, eventMapper.countByType(threadId, ThreadEventType.YOLO_CHANGED.value()));
  }

  @Test
  void harvestsOneMessagePerBatchAndDefersLaterConfigAfterMessage() {
    Instant now = Instant.parse("2026-02-03T00:00:00Z");
    ThreadTransactions.SessionCreateResult created =
        transactions.createSession("messages", false, now);
    long threadId = created.mainThread().id();
    long rootHead = created.mainThread().headEntryId();
    ThreadInput user = transactions.submitUserMessage(threadId, userMessage("user"), "user", now);
    ThreadInput custom =
        transactions.submitCustomMessage(threadId, customMessage("custom"), "custom", now);

    assertTrue(
        threadStore.tryAcquire(threadId, "message-owner", now, Duration.ofMinutes(1)).isPresent());
    ThreadTransactions.HarvestResult first =
        transactions.harvestQueuedInputs(threadId, "message-owner", now.plusSeconds(1));
    ThreadInput later =
        transactions.submitUserMessage(threadId, userMessage("later"), "later", now.plusSeconds(2));

    assertTrue(first.hasMessage());
    assertEquals(List.of(user.id()), first.applied().stream().map(ThreadInput::id).toList());
    assertEquals("queued", inputMapper.find(custom.id()).getStatus());
    assertEquals("queued", inputMapper.find(later.id()).getStatus());

    HarnessSessionEntryDO userEntry =
        entryMapper.find(created.sessionId(), first.applied().get(0).appliedEntryId());
    assertEquals(rootHead, userEntry.getParentEntryId());

    ThreadTransactions.HarvestResult second =
        transactions.harvestQueuedInputs(threadId, "message-owner", now.plusSeconds(3));
    assertTrue(second.hasMessage());
    assertEquals(List.of(custom.id()), second.applied().stream().map(ThreadInput::id).toList());
    assertEquals("queued", inputMapper.find(later.id()).getStatus());
  }

  /** M1 -> SET_AGENT(B) -> M2：M1 使用旧状态，SET_AGENT 与 M2 在后续安全边界应用。 */
  @Test
  void messageBoundaryKeepsLaterAgentChangeOffEarlierTurn() {
    Instant now = Instant.parse("2026-02-07T00:00:00Z");
    ThreadTransactions.SessionCreateResult created =
        transactions.createSession("order", false, now);
    long threadId = created.mainThread().id();
    ThreadInput m1 = transactions.submitUserMessage(threadId, userMessage("m1"), "m1", now);
    ThreadInput setAgent =
        transactions.submitSetAgent(
            threadId, SEED_AGENT_ID, SEED_AGENT_NAME, "set-agent", now.plusSeconds(1));
    ThreadInput m2 =
        transactions.submitUserMessage(threadId, userMessage("m2"), "m2", now.plusSeconds(2));

    assertTrue(
        threadStore.tryAcquire(threadId, "order-owner", now, Duration.ofMinutes(1)).isPresent());
    ThreadTransactions.HarvestResult first =
        transactions.harvestQueuedInputs(threadId, "order-owner", now.plusSeconds(3));
    assertEquals(List.of(m1.id()), first.applied().stream().map(ThreadInput::id).toList());
    assertNull(threadMapper.find(threadId).getActiveAgentDefinitionId());
    assertEquals("queued", inputMapper.find(setAgent.id()).getStatus());
    assertEquals("queued", inputMapper.find(m2.id()).getStatus());

    ThreadTransactions.HarvestResult second =
        transactions.harvestQueuedInputs(threadId, "order-owner", now.plusSeconds(4));
    assertEquals(
        List.of(setAgent.id(), m2.id()), second.applied().stream().map(ThreadInput::id).toList());
    HarnessThreadDO after = threadMapper.find(threadId);
    assertEquals(SEED_AGENT_ID, after.getActiveAgentDefinitionId());
    assertEquals(SEED_AGENT_NAME, after.getActiveAgentName());
    assertEquals("1", after.getModelId());
    assertEquals("default", after.getVariant());
    assertEquals(1, eventMapper.countByType(threadId, ThreadEventType.AGENT_CHANGED.value()));
    List<HarnessThreadEventDO> agentEvents =
        eventMapper.listAfter(threadId, 0L, 100).stream()
            .filter(e -> ThreadEventType.AGENT_CHANGED.value().equals(e.getEventType()))
            .toList();
    assertEquals(1, agentEvents.size());
  }

  @Test
  void stopsInInputOrderAndClearsRetryState() {
    Instant now = Instant.parse("2026-02-04T00:00:00Z");
    ThreadTransactions.SessionCreateResult created = transactions.createSession("stop", false, now);
    long threadId = created.mainThread().id();
    transactions.submitUserMessage(threadId, userMessage("first"), "first", now);
    transactions.submitSetModel(threadId, "stop-model", "stop-variant", "config", now);
    transactions.submitCustomMessage(threadId, customMessage("second"), "second", now);

    ThreadTransactions.StopResult stopped =
        transactions.stop(threadId, "stop-request", now.plusSeconds(1));
    ThreadTransactions.StopResult replayed =
        transactions.stop(threadId, "stop-request", now.plusSeconds(2));

    assertEquals(stopped.stop().id(), replayed.stop().id());
    assertEquals(List.of("first", "second"), stopped.restoredMessages());
    assertEquals(
        stopped.cancelledInputs().stream().map(ThreadInput::id).toList(),
        replayed.cancelledInputs().stream().map(ThreadInput::id).toList());
    List<HarnessThreadInputDO> cancelled = inputMapper.listByThread(threadId);
    assertEquals(3, cancelled.size());
    assertTrue(
        cancelled.stream()
            .allMatch(
                input ->
                    "cancelled".equals(input.getStatus())
                        && input.getResolvedAt() != null
                        && stopped.stop().id() == input.getCancelledByStopId()));
    assertEquals("IDLE", threadMapper.find(threadId).getStatus());
    assertNull(threadMapper.find(threadId).getProcessorToken());
    assertEquals(0, threadMapper.find(threadId).getRetryAttempt());
    assertNull(threadMapper.find(threadId).getRetryAt());
    assertEquals(3, eventMapper.countByType(threadId, ThreadEventType.INPUT_CANCELLED.value()));
  }

  /** FAILED 只接受消息类输入重新启动，避免配置命令意外重放失败 turn。 */
  @Test
  void failedThreadRestartsOnlyWhenANewMessageIsQueued() {
    Instant now = Instant.parse("2026-02-04T01:00:00Z");
    ThreadTransactions.SessionCreateResult created =
        transactions.createSession("failed-restart", false, now);
    long threadId = created.mainThread().id();
    threadMapper.updateStatusDirect(threadId, "FAILED", utc(now));

    ThreadInput configuration =
        transactions.submitSetYolo(threadId, true, "failed-config", now.plusSeconds(1));

    assertEquals("FAILED", threadMapper.find(threadId).getStatus());
    assertEquals("queued", inputMapper.find(configuration.id()).getStatus());
    assertEquals(0, eventMapper.countByType(threadId, ThreadEventType.THREAD_RUNNING.value()));

    ThreadInput message =
        transactions.submitCustomMessage(
            threadId, customMessage("resume"), "failed-custom", now.plusSeconds(2));

    assertEquals("RUNNING", threadMapper.find(threadId).getStatus());
    assertEquals("queued", inputMapper.find(message.id()).getStatus());
    assertEquals(1, eventMapper.countByType(threadId, ThreadEventType.THREAD_RUNNING.value()));
  }

  @Test
  void fencesLostOwnersAndPersistsWaitFailureCompactionAndQuiescenceStates() {
    Instant now = Instant.parse("2026-02-05T00:00:00Z");
    ThreadTransactions.SessionCreateResult created =
        transactions.createSession("lifecycle", false, now);
    long threadId = created.mainThread().id();
    long originalHead = created.mainThread().headEntryId();

    assertFalse(
        transactions.compact(
            threadId,
            "lost",
            new CompactionEntryPayload("summary", originalHead, 1, "{}"),
            List.of(),
            now));
    assertFalse(
        transactions.appendEvents(
            threadId, "lost", List.of(event(ThreadEventType.ASSISTANT_STARTED)), now));
    assertEquals(
        ThreadTransactions.QuiescenceResult.LOST_OWNERSHIP,
        transactions.quiesce(threadId, "lost", now));
    assertEquals(originalHead, threadMapper.find(threadId).getHeadEntryId());

    threadMapper.updateStatusDirect(threadId, "RUNNING", utc(now));
    assertTrue(threadStore.tryAcquire(threadId, "owner-a", now, Duration.ofMinutes(1)).isPresent());
    assertFalse(transactions.waitForExternal(threadId, "owner-a", "no-tools", now.plusSeconds(1)));
    assertEquals("owner-a", threadMapper.find(threadId).getProcessorToken());
    insertWaitingInvocation(threadId, originalHead, now);
    assertTrue(transactions.waitForExternal(threadId, "owner-a", "permission", now.plusSeconds(2)));
    assertEquals("WAITING", threadMapper.find(threadId).getStatus());
    assertNull(threadMapper.find(threadId).getProcessorToken());
    assertEquals(1, eventMapper.countByType(threadId, ThreadEventType.THREAD_WAITING.value()));

    assertTrue(
        threadStore
            .tryAcquire(threadId, "owner-b", now.plusSeconds(3), Duration.ofMinutes(1))
            .isPresent());
    assertEquals(
        ThreadTransactions.QuiescenceResult.WORK_REMAINS,
        transactions.quiesce(threadId, "owner-b", now.plusSeconds(4)));
    assertEquals("owner-b", threadMapper.find(threadId).getProcessorToken());
    assertTrue(threadStore.release(threadId, "owner-b", now.plusSeconds(5)));

    ThreadTransactions.SessionCreateResult work = transactions.createSession("work", false, now);
    long workThreadId = work.mainThread().id();
    threadMapper.updateStatusDirect(workThreadId, "RUNNING", utc(now));
    assertTrue(
        threadStore.tryAcquire(workThreadId, "owner-c", now, Duration.ofMinutes(1)).isPresent());
    assertTrue(
        transactions.appendEvents(
            workThreadId, "owner-c", List.of(event(ThreadEventType.ASSISTANT_STARTED)), now));
    assertTrue(
        transactions.compact(
            workThreadId,
            "owner-c",
            new CompactionEntryPayload("summary", work.mainThread().headEntryId(), 1, "{}"),
            List.of(event(ThreadEventType.COMPACTION_COMPLETED)),
            now.plusSeconds(1)));
    long compactedHead = threadMapper.find(workThreadId).getHeadEntryId();
    assertEquals("compaction", entryMapper.find(work.sessionId(), compactedHead).getEntryType());
    assertTrue(
        transactions.fail(
            workThreadId,
            "owner-c",
            List.of(event(ThreadEventType.THREAD_FAILED)),
            now.plusSeconds(2)));
    assertEquals("FAILED", threadMapper.find(workThreadId).getStatus());
    assertNull(threadMapper.find(workThreadId).getProcessorToken());
    assertFalse(transactions.fail(workThreadId, "owner-c", List.of(), now.plusSeconds(3)));

    ThreadTransactions.SessionCreateResult retry = transactions.createSession("retry", false, now);
    long retryThreadId = retry.mainThread().id();
    threadMapper.updateStatusDirect(retryThreadId, "RUNNING", utc(now.plusSeconds(3)));
    assertTrue(
        threadStore
            .tryAcquire(retryThreadId, "owner-d", now.plusSeconds(4), Duration.ofMinutes(1))
            .isPresent());
    assertTrue(
        transactions.scheduleRetry(
            retryThreadId,
            "owner-d",
            1,
            now.plusSeconds(8),
            List.of(event(ThreadEventType.THREAD_RETRY_SCHEDULED)),
            now.plusSeconds(5)));
    assertEquals("RETRYING", threadMapper.find(retryThreadId).getStatus());
    assertEquals(1, threadMapper.find(retryThreadId).getRetryAttempt());
    assertEquals(
        1, eventMapper.countByType(retryThreadId, ThreadEventType.THREAD_RETRY_SCHEDULED.value()));
    assertTrue(
        threadStore
            .tryAcquire(retryThreadId, "too-early", now.plusSeconds(6), Duration.ofMinutes(1))
            .isEmpty());
    assertTrue(
        threadStore
            .tryAcquire(retryThreadId, "owner-e", now.plusSeconds(8), Duration.ofMinutes(1))
            .isPresent());
    assertTrue(transactions.completeRetryDebt(retryThreadId, "owner-e", now.plusSeconds(9)));
    assertEquals("RUNNING", threadMapper.find(retryThreadId).getStatus());
    assertEquals(0, threadMapper.find(retryThreadId).getRetryAttempt());
    assertNull(threadMapper.find(retryThreadId).getRetryAt());
    assertEquals(
        ThreadTransactions.QuiescenceResult.IDLE,
        transactions.quiesce(retryThreadId, "owner-e", now.plusSeconds(10)));
    assertEquals("IDLE", threadMapper.find(retryThreadId).getStatus());
    assertNull(threadMapper.find(retryThreadId).getProcessorToken());
  }

  @Test
  void rejectsInvalidInputsAndDoesNotLetLostOwnersWrite() {
    Instant now = Instant.parse("2026-02-06T00:00:00Z");
    ThreadTransactions.SessionCreateResult created =
        transactions.createSession("validation", false, now);
    long threadId = created.mainThread().id();
    long initialHead = created.mainThread().headEntryId();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            transactions.submitUserMessage(
                threadId,
                new AgentMessage(
                    AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("not-user"))),
                "wrong-role",
                now));
    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.submitSetModel(threadId, " ", "v", "blank-model", now));
    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.harvestQueuedInputs(9_999_997L, "missing", now));
    assertThrows(
        IllegalArgumentException.class, () -> transactions.stop(9_999_996L, "missing-stop", now));
    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.scheduleRetry(threadId, "lost", 0, now.plusSeconds(1), List.of(), now));

    assertFalse(
        transactions.commitFinalAssistant(
            threadId, "lost", 9_999_994L, null, null, List.of(), now.plusSeconds(1)));
    assertFalse(transactions.applyTerminalToolResults(threadId, "lost", now.plusSeconds(1)));
    threadMapper.updateStatusDirect(threadId, "RUNNING", utc(now));
    assertTrue(threadStore.tryAcquire(threadId, "owner", now, Duration.ofMinutes(1)).isPresent());
    assertFalse(transactions.applyTerminalToolResults(threadId, "owner", now.plusSeconds(1)));

    assertEquals(initialHead, threadMapper.find(threadId).getHeadEntryId());
    assertTrue(inputMapper.listByThread(threadId).isEmpty());
    assertEquals(0, eventMapper.countByType(threadId, ThreadEventType.INPUT_APPLIED.value()));
  }

  private static AgentMessage userMessage(String text) {
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)));
  }

  private static AgentMessage customMessage(String text) {
    return new AgentMessage(AgentMessageRole.SYSTEM, List.of(new TextMessageContent(text)));
  }

  private static ThreadEventDraft event(ThreadEventType type) {
    return new ThreadEventDraft(type, "{\"schemaVersion\":1}");
  }

  private <T extends SessionEntryPayload> T payload(HarnessSessionEntryDO entry, Class<T> type) {
    SessionEntryPayload payload =
        ENTRY_CODEC.decode(
            SessionEntryType.fromValue(entry.getEntryType()), entry.getPayloadJson());
    return assertInstanceOf(type, payload);
  }

  private void insertWaitingInvocation(long threadId, long assistantEntryId, Instant now) {
    ToolInvocationDO invocation = new ToolInvocationDO();
    invocation.setId(threadId + 1_000_000L);
    invocation.setThreadId(threadId);
    invocation.setAssistantEntryId(assistantEntryId);
    invocation.setOrdinal(0);
    invocation.setToolCallId("waiting-" + threadId);
    invocation.setToolName("write");
    invocation.setToolVersion("1");
    invocation.setLocation("PLATFORM");
    invocation.setArgumentsJson("{}");
    invocation.setStatus("WAITING_APPROVAL");
    invocation.setPermissionAction("ASK");
    invocation.setSideEffect("WRITE");
    invocation.setDeadlineAt(utc(now.plusSeconds(60)));
    invocation.setCreateTime(utc(now));
    invocation.setUpdateTime(utc(now));
    assertEquals(1, invocationMapper.insert(invocation));
    assertNotNull(invocationMapper.find(invocation.getId()));
  }

  private static LocalDateTime utc(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  // -------------------------------------------------------------------------------------------
  // recordAssistantError
  // -------------------------------------------------------------------------------------------

  /**
   * RETRY_SCHEDULED 路径必须原子地插入 ASSISTANT_ERROR Entry、推进 head、保留 RETRYING debt、记录 assistant_failed 与
   * thread_retry_scheduled 事件；下一次 retry debt 仍能偿清。
   */
  @Test
  void recordAssistantErrorRetryAdvancesHeadAndKeepsRetryDebt() {
    Instant now = Instant.parse("2026-08-01T00:00:00Z");
    ThreadTransactions.SessionCreateResult created =
        transactions.createSession("error-retry", false, now);
    long threadId = created.mainThread().id();
    transactions.submitUserMessage(threadId, userMessage("go"), "u", now);
    assertTrue(threadStore.tryAcquire(threadId, "owner", now, Duration.ofMinutes(1)).isPresent());
    ThreadTransactions.HarvestResult harvest =
        transactions.harvestQueuedInputs(threadId, "owner", now.plusSeconds(1));
    long userEntryId = harvest.applied().get(0).appliedEntryId();
    long planned = 9_900_000L;
    Instant retryAt = now.plusSeconds(5);
    AssistantErrorEntryPayload payload =
        new AssistantErrorEntryPayload("TRANSIENT", "transient network", 1, 2, true);

    assertTrue(
        transactions.recordAssistantError(
            threadId,
            "owner",
            planned,
            payload,
            ThreadTransactions.AssistantErrorOutcome.RETRY_SCHEDULED,
            retryAt,
            List.of(
                new ThreadEventDraft(
                    ThreadEventType.ASSISTANT_FAILED,
                    planned,
                    ThreadEventPayloads.of(
                        "kind",
                        "TRANSIENT",
                        "message",
                        "transient network",
                        "retryScheduled",
                        true,
                        "retryAttempt",
                        1,
                        "maxRetries",
                        2)),
                new ThreadEventDraft(
                    ThreadEventType.THREAD_RETRY_SCHEDULED,
                    ThreadEventPayloads.of(
                        "retryAttempt",
                        1,
                        "maxRetries",
                        2,
                        "delayMillis",
                        5000L,
                        "retryAt",
                        retryAt,
                        "kind",
                        "TRANSIENT"))),
            now.plusSeconds(2)));

    HarnessThreadDO thread = threadMapper.find(threadId);
    assertEquals(planned, thread.getHeadEntryId());
    assertEquals("RETRYING", thread.getStatus());
    assertNull(thread.getProcessorToken());
    assertEquals(1, thread.getRetryAttempt());
    HarnessSessionEntryDO inserted = entryMapper.find(created.sessionId(), planned);
    assertNotNull(inserted);
    assertEquals("assistant_error", inserted.getEntryType());
    assertEquals(userEntryId, inserted.getParentEntryId());
    SessionEntryPayload decoded =
        ENTRY_CODEC.decode(SessionEntryType.ASSISTANT_ERROR, inserted.getPayloadJson());
    AssistantErrorEntryPayload restored = (AssistantErrorEntryPayload) decoded;
    assertEquals("TRANSIENT", restored.kind());
    assertEquals(1, restored.retryAttempt());
    assertEquals(2, restored.maxRetries());
    assertTrue(restored.retryScheduled());
    assertEquals(1, eventMapper.countByType(threadId, ThreadEventType.ASSISTANT_FAILED.value()));
    assertEquals(
        1, eventMapper.countByType(threadId, ThreadEventType.THREAD_RETRY_SCHEDULED.value()));
    // RETRYING debt must still be observable: the thread stays in RETRYING with the second
    // attempt counted. The processor token was released by the first recordAssistantError; a
    // follow-up re-acquire (after retry_at passes) lets the simulator run another Turn that
    // completes the debt.
    assertEquals("RETRYING", threadMapper.find(threadId).getStatus());
    assertEquals(1, threadMapper.find(threadId).getRetryAttempt());
    // After the scheduled retry_at the durable recovery can re-acquire and complete the debt.
    assertTrue(
        threadStore
            .tryAcquire(threadId, "owner-2", retryAt.plusSeconds(1), Duration.ofMinutes(1))
            .isPresent());
    assertTrue(transactions.completeRetryDebt(threadId, "owner-2", retryAt.plusSeconds(2)));
  }

  /** FAILED 路径切到 FAILED 状态、清除 processor、写 thread_failed 事件。 */
  @Test
  void recordAssistantErrorFailedTerminatesThread() {
    Instant now = Instant.parse("2026-08-02T00:00:00Z");
    ThreadTransactions.SessionCreateResult created =
        transactions.createSession("error-fail", false, now);
    long threadId = created.mainThread().id();
    transactions.submitUserMessage(threadId, userMessage("go"), "u", now);
    assertTrue(threadStore.tryAcquire(threadId, "owner", now, Duration.ofMinutes(1)).isPresent());
    transactions.harvestQueuedInputs(threadId, "owner", now.plusSeconds(1));
    long planned = 9_900_010L;
    AssistantErrorEntryPayload payload =
        new AssistantErrorEntryPayload("INVALID_REQUEST", "bad api key", 0, null, false);
    assertTrue(
        transactions.recordAssistantError(
            threadId,
            "owner",
            planned,
            payload,
            ThreadTransactions.AssistantErrorOutcome.FAILED,
            null,
            List.of(
                new ThreadEventDraft(
                    ThreadEventType.ASSISTANT_FAILED,
                    planned,
                    ThreadEventPayloads.of(
                        "kind",
                        "INVALID_REQUEST",
                        "message",
                        "bad api key",
                        "retryScheduled",
                        false,
                        "retryAttempt",
                        0)),
                new ThreadEventDraft(
                    ThreadEventType.THREAD_FAILED,
                    ThreadEventPayloads.of("reason", "bad_key", "kind", "INVALID_REQUEST"))),
            now.plusSeconds(2)));
    HarnessThreadDO thread = threadMapper.find(threadId);
    assertEquals(planned, thread.getHeadEntryId());
    assertEquals("FAILED", thread.getStatus());
    assertNull(thread.getProcessorToken());
    assertNull(thread.getRetryAt());
    assertEquals(1, eventMapper.countByType(threadId, ThreadEventType.ASSISTANT_FAILED.value()));
    assertEquals(1, eventMapper.countByType(threadId, ThreadEventType.THREAD_FAILED.value()));
  }

  /** Lost ownership: recordAssistantError must return false without inserting an orphan entry. */
  @Test
  void recordAssistantErrorRefusesLostToken() {
    Instant now = Instant.parse("2026-08-03T00:00:00Z");
    ThreadTransactions.SessionCreateResult created =
        transactions.createSession("error-lost", false, now);
    long threadId = created.mainThread().id();
    transactions.submitUserMessage(threadId, userMessage("go"), "u", now);
    assertTrue(threadStore.tryAcquire(threadId, "owner", now, Duration.ofMinutes(1)).isPresent());
    transactions.harvestQueuedInputs(threadId, "owner", now.plusSeconds(1));
    long headBefore = threadMapper.find(threadId).getHeadEntryId();
    long entryCountBefore = entryMapper.listBySession(created.sessionId()).size();
    assertFalse(
        transactions.recordAssistantError(
            threadId,
            "stolen-token",
            9_900_020L,
            new AssistantErrorEntryPayload("TRANSIENT", "x", 1, 2, true),
            ThreadTransactions.AssistantErrorOutcome.RETRY_SCHEDULED,
            now.plusSeconds(5),
            List.of(),
            now.plusSeconds(1)));
    HarnessThreadDO thread = threadMapper.find(threadId);
    assertEquals(headBefore, thread.getHeadEntryId());
    assertEquals(entryCountBefore, entryMapper.listBySession(created.sessionId()).size());
  }

  /** RETRY_SCHEDULED 必须显式带 retryAt；缺值即时拒绝。 */
  @Test
  void recordAssistantErrorRetryRequiresRetryAt() {
    Instant now = Instant.parse("2026-08-04T00:00:00Z");
    ThreadTransactions.SessionCreateResult created =
        transactions.createSession("error-validate", false, now);
    long threadId = created.mainThread().id();
    transactions.submitUserMessage(threadId, userMessage("go"), "u", now);
    assertTrue(threadStore.tryAcquire(threadId, "owner", now, Duration.ofMinutes(1)).isPresent());
    transactions.harvestQueuedInputs(threadId, "owner", now.plusSeconds(1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            transactions.recordAssistantError(
                threadId,
                "owner",
                9_900_030L,
                new AssistantErrorEntryPayload("TRANSIENT", "x", 1, 2, true),
                ThreadTransactions.AssistantErrorOutcome.RETRY_SCHEDULED,
                null,
                List.of(),
                now.plusSeconds(1)));
  }
}
