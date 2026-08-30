package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.Fixture;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.assistantMetadata;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.claimLosingStore;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.claimThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.insertAssistantPayload;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.insertAssistantWithCalls;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.model;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.path;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.plainRequest;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.requestThreadWork;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedCommand;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedModelInvocation;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedOpenInputTurn;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedSucceededToolPhase;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedToolChain;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedToolInvocation;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.succeedToolWith;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.successResponse;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.thread;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.tool;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.tooledRequest;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.toolsByAssistant;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.touchModelTimestamp;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.touchThreadTimestamp;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.touchToolTimestamp;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.transitionModel;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.work;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolEffectBatch;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * ThreadProcessor Tool sibling batch：每个 claim 恰执行一个动作的原子 ordinal 回写、错误 payload、blocker 与不变量违反回滚。
 */
class ThreadProcessorToolBatchTest extends ThreadProcessorTestBase {

  /** 全部 terminal 的 sibling batch：一个 claim 完成 apply，下一 claim 才做 continuation。 */
  @Test
  void toolSiblingBatchAppliesAtomicallyInOrdinalOrderThenStartsContinuation() {
    Fixture fixture = fixture();
    var chain =
        seedToolChain(
            fixture.store,
            List.of("call-1", "call-2"),
            ModelInvocationStatus.SUCCEEDED,
            List.of(ToolInvocationStatus.SUCCEEDED, ToolInvocationStatus.SUCCEEDED));
    requestThreadWork(fixture.store, chain.turn().threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));

    // claim1：Tool batch apply（TOOL0/TOOL1/TURN_END(COMPLETED, continueModel=true)），删除
    // children+parent，
    // 固定先请求 THREAD 再完成 claim。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(chain.turn().threadId()));
    EntryPath afterBatch = path(fixture.store, chain.turn().threadId());
    List<Entry> batchEntries = afterBatch.entries();
    assertEquals(7, batchEntries.size());
    // ROOT, TURN_START, USER, ASSISTANT, TOOL0, TOOL1, TURN_END
    assertEquals(chain.assistantEntryId(), batchEntries.get(3).id());
    MessagePayload tool0 = (MessagePayload) batchEntries.get(4).payload();
    MessagePayload tool1 = (MessagePayload) batchEntries.get(5).payload();
    assertEquals(AgentMessageRole.TOOL, tool0.message().role());
    assertEquals(0, tool0.toolResultMetadata().ordinal());
    assertEquals("call-1", tool0.toolResultMetadata().toolCallId());
    assertEquals(ToolResultStatus.SUCCEEDED, tool0.toolResultMetadata().status());
    ToolResultMessageContent result0 = (ToolResultMessageContent) tool0.message().contents().get(0);
    assertEquals("tool ok", ((TextMessageContent) result0.contents().get(0)).text());
    assertEquals(1, tool1.toolResultMetadata().ordinal());
    assertEquals("call-2", tool1.toolResultMetadata().toolCallId());
    TurnEndPayload end = (TurnEndPayload) batchEntries.get(6).payload();
    assertEquals(TurnEndOutcome.COMPLETED, end.outcome());
    assertTrue(end.continueModel());
    assertEquals(
        batchEntries.get(6).id(), thread(fixture.store, chain.turn().threadId()).headEntryId());
    // 先请求 THREAD 再 complete：wake 保留，continuation 由下一 claim 执行。
    assertNotNull(
        work(fixture.store, new WorkTarget(WorkTargetType.THREAD, chain.turn().threadId())));
    // applyToolBatch 同事务删除全部 child ToolInvocation 行与 parent ModelInvocation 行。
    assertTrue(toolsByAssistant(fixture.store, chain.assistantEntryId()).isEmpty());
    assertNull(
        fixture
            .store
            .transaction(tx -> tx.findModelInvocation(chain.modelInvocationId()))
            .orElse(null));

    // claim2：下一 claim 才创建 continuation Model 并请求 MODEL Work（consumed 本 claim，THREAD 行被清除）。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(chain.turn().threadId()));
    EntryPath path = path(fixture.store, chain.turn().threadId());
    List<Entry> entries = path.entries();
    assertEquals(8, entries.size());
    TurnStartPayload continuation = (TurnStartPayload) entries.get(7).payload();
    assertEquals(TurnStartReason.CONTINUATION, continuation.reason());
    assertEquals(entries.get(7).id(), thread(fixture.store, chain.turn().threadId()).headEntryId());
    // baseline(0) + seed assistant 推进(1) + batch apply(2) + continuation(3)。
    assertEquals(3L, thread(fixture.store, chain.turn().threadId()).version());
    ModelInvocation continuationModel =
        inTx(
                fixture,
                tx -> tx.findModelInvocationByTurn(chain.turn().threadId(), entries.get(7).id()))
            .orElseThrow();
    assertNotNull(
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, continuationModel.id())));
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, chain.turn().threadId())));
  }

  /**
   * Tool batch 新建的 effects/result/TURN_END 以及随后 continuation 事实不得早于已锁定 Thread/Model/Tool durable
   * floors；raw lease clock 仍保持回拨后的本地样本。
   */
  @Test
  void toolTerminalApplyUsesAllDurableFloorsWithoutClampingLeaseClock() {
    Fixture fixture = fixture();
    var chain =
        seedToolChain(
            fixture.store,
            List.of("call-1"),
            ModelInvocationStatus.SUCCEEDED,
            List.of(ToolInvocationStatus.SUCCEEDED));
    Instant threadFloor = NOW.plusSeconds(120);
    Instant modelFloor = NOW.plusSeconds(121);
    Instant toolFloor = NOW.plusSeconds(122);
    touchThreadTimestamp(fixture.store, chain.turn().threadId(), threadFloor);
    touchModelTimestamp(fixture.store, chain.modelInvocationId(), modelFloor);
    touchToolTimestamp(fixture.store, chain.toolInvocationIds().getFirst(), toolFloor);
    requestThreadWork(fixture.store, chain.turn().threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));

    // claim1：Tool batch 原子 apply（results + TURN_END）以全部 durable floors 抬升。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(chain.turn().threadId()));
    EntryPath durablePath = path(fixture.store, chain.turn().threadId());
    for (Entry entry : durablePath.entries().subList(4, durablePath.entries().size())) {
      assertEquals(toolFloor, entry.createdAt());
    }
    // applyToolBatch 已删除 Tool 行：无法读 tool.updatedAt，但 thread 的最终 updatedAt 仍受 mutationNow 抬升。
    assertTrue(
        fixture
            .store
            .transaction(tx -> tx.findToolInvocation(chain.toolInvocationIds().getFirst()))
            .isEmpty());
    assertEquals(toolFloor, thread(fixture.store, chain.turn().threadId()).updatedAt());

    // claim2：continuation 事实沿用同一 durable floors（thread/plan 已抬升到 toolFloor）。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(chain.turn().threadId()));
    Entry continuation = path(fixture.store, chain.turn().threadId()).head();
    ModelInvocation continuationModel =
        inTx(
                fixture,
                tx -> tx.findModelInvocationByTurn(chain.turn().threadId(), continuation.id()))
            .orElseThrow();
    assertEquals(toolFloor, continuationModel.createdAt());
    assertEquals(toolFloor, continuationModel.updatedAt());
  }

  /** effects 落在 ToolResult 之前；两个 tool 的 batch 后固定 TURN_END，随后单独 claim 启动 continuation。 */
  @Test
  void successfulEffectsAreAppendedBeforeTheirToolResults() {
    Fixture fixture = fixture();
    var chain =
        seedToolChain(
            fixture.store,
            List.of("call-1", "call-2"),
            ModelInvocationStatus.SUCCEEDED,
            List.of(ToolInvocationStatus.READY, ToolInvocationStatus.READY));
    ToolEffectBatch effects =
        new ToolEffectBatch(
            List.of(
                new CustomEntryPayload("goal", "state", 1, "{\"step\":1}"),
                new CustomEntryPayload("goal", "state", 1, "{\"step\":2}")));
    succeedToolWith(
        fixture.store,
        chain.toolInvocationIds().get(0),
        new ToolResult("call-1", List.of(new TextResultContent("first")), false, "{}"),
        effects);
    succeedToolWith(
        fixture.store,
        chain.toolInvocationIds().get(1),
        new ToolResult("call-2", List.of(new TextResultContent("second")), false, "{}"));
    requestThreadWork(fixture.store, chain.turn().threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));

    // claim1：Tool batch apply：effects 先于 ToolResult，TURN_END 收尾，rows 删除。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(chain.turn().threadId()));
    List<Entry> batchEntries = path(fixture.store, chain.turn().threadId()).entries();
    assertEquals(9, batchEntries.size());
    assertEquals(
        new CustomEntryPayload("goal", "state", 1, "{\"step\":1}"), batchEntries.get(4).payload());
    assertEquals(
        new CustomEntryPayload("goal", "state", 1, "{\"step\":2}"), batchEntries.get(5).payload());
    assertTrue(batchEntries.get(6).payload() instanceof MessagePayload);
    assertTrue(batchEntries.get(7).payload() instanceof MessagePayload);
    // applyToolBatch 同事务删除全部 child ToolInvocation 行与 parent ModelInvocation 行。
    assertTrue(toolsByAssistant(fixture.store, chain.assistantEntryId()).isEmpty());
    assertNull(
        fixture
            .store
            .transaction(tx -> tx.findModelInvocation(chain.modelInvocationId()))
            .orElse(null));

    // claim2：continuation 使 branch 到达 10 条（TURN_START 追加在 TURN_END 后）。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(chain.turn().threadId()));
    assertEquals(10, path(fixture.store, chain.turn().threadId()).entries().size());
  }

  @Test
  void outcomeAppenderRejectsANonterminalInvocationBeforeWritingEntries() {
    Fixture fixture = fixture();
    var chain =
        seedToolChain(
            fixture.store,
            List.of("call-1"),
            ModelInvocationStatus.SUCCEEDED,
            List.of(ToolInvocationStatus.READY));
    ToolInvocation ready = tool(fixture.store, chain.toolInvocationIds().getFirst());
    EntryPath before = path(fixture.store, chain.turn().threadId());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            fixture.store.transaction(
                tx ->
                    ToolOutcomeAppender.append(
                        tx, before.root().sessionId(), chain.assistantEntryId(), ready, NOW)));
    assertEquals(before, path(fixture.store, chain.turn().threadId()));
  }

  /** FAILED/CANCELLED/UNKNOWN sibling outcome payload 精确映射；随后单独 claim 启动 continuation。 */
  @Test
  void toolSiblingBatchMapsFailureCancelledUnknownPayloadsExactly() {
    Fixture fixture = fixture();
    var chain =
        seedToolChain(
            fixture.store,
            List.of("call-1", "call-2", "call-3"),
            ModelInvocationStatus.SUCCEEDED,
            List.of(
                ToolInvocationStatus.FAILED,
                ToolInvocationStatus.CANCELLED,
                ToolInvocationStatus.UNKNOWN));
    requestThreadWork(fixture.store, chain.turn().threadId());
    fixture.resolver.results.add(new TurnResolver.Resolved(plainRequest(), 100_000, 16_384));

    // claim1：Tool batch 精确映射 FAILED/CANCELLED/UNKNOWN outcome payload 并收尾 TURN_END。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(chain.turn().threadId()));
    List<Entry> batchEntries = path(fixture.store, chain.turn().threadId()).entries();
    assertEquals(8, batchEntries.size());
    String[] expectedText = {"tool failed", "tool cancelled", "tool unknown"};
    ToolResultStatus[] expectedStatus = {
      ToolResultStatus.FAILED, ToolResultStatus.CANCELLED, ToolResultStatus.UNKNOWN
    };
    for (int i = 0; i < 3; i++) {
      MessagePayload toolPayload = (MessagePayload) batchEntries.get(4 + i).payload();
      ToolResultMessageContent result =
          (ToolResultMessageContent) toolPayload.message().contents().get(0);
      assertEquals(expectedText[i], ((TextMessageContent) result.contents().get(0)).text());
      assertTrue(result.error());
      assertTrue(result.detailsJson().contains("\"kind\""));
      ToolResultMetadata metadata = toolPayload.toolResultMetadata();
      assertEquals(expectedStatus[i], metadata.status());
      assertFalse(metadata.synthetic());
      assertNull(metadata.reason());
      assertEquals("call-" + (i + 1), metadata.toolCallId());
      assertEquals(i, metadata.ordinal());
      assertEquals(chain.assistantEntryId(), metadata.assistantEntryId());
    }

    // claim2：下一 claim 才做 continuation。
    assertEquals(ThreadProcessResult.COMPLETED, fixture.nextClaim(chain.turn().threadId()));
    assertEquals(9, path(fixture.store, chain.turn().threadId()).entries().size());
  }

  @Test
  void nonterminalToolSiblingBlocksAndCompletesWorkInOneClaim() {
    Fixture toolFixture = fixture();
    var chain =
        seedToolChain(
            toolFixture.store,
            List.of("call-1", "call-2"),
            ModelInvocationStatus.SUCCEEDED,
            List.of(ToolInvocationStatus.SUCCEEDED, ToolInvocationStatus.READY));
    requestThreadWork(toolFixture.store, chain.turn().threadId());

    // 一个 claim：TOOL_ACTIVE 直接完成 claim（applyToolBatch 未执行），Tool 行与 parent Model 行保持原样。
    assertEquals(ThreadProcessResult.COMPLETED, toolFixture.nextClaim(chain.turn().threadId()));
    List<ToolInvocation> siblings = toolsByAssistant(toolFixture.store, chain.assistantEntryId());
    assertEquals(2, siblings.size());
    assertEquals(ToolInvocationStatus.SUCCEEDED, siblings.get(0).status());
    assertEquals(ToolInvocationStatus.READY, siblings.get(1).status());
    assertNotNull(
        toolFixture
            .store
            .transaction(tx -> tx.findModelInvocation(chain.modelInvocationId()))
            .orElse(null));
    assertEquals(4, path(toolFixture.store, chain.turn().threadId()).entries().size());
  }

  @Test
  void toolSiblingCountMismatchIsRejectedAtomically() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    var seeded = seedSucceededToolPhase(fixture.store, baseline, List.of("call-1", "call-2"));
    UUID modelId = seeded.modelInvocationId();
    UUID assistantId = seeded.assistantEntryId();
    UUID toolId =
        seedToolInvocation(
            fixture.store, modelId, assistantId, 0, "call-1", ToolInvocationStatus.SUCCEEDED);
    requestThreadWork(fixture.store, baseline.threadId());

    // assistant 有 2 个 call 但只有 1 个 sibling：防御性 ISE，不写入任何 TOOL Entry。
    assertThrows(IllegalStateException.class, () -> fixture.nextClaim(baseline.threadId()));
    assertEquals(4, path(fixture.store, baseline.threadId()).entries().size());
    // ISE 回滚后 Tool 行保持原样；ToolInvocation 无 resultEntryId 字段。
    assertEquals(ToolInvocationStatus.SUCCEEDED, tool(fixture.store, toolId).status());
  }

  @Test
  void toolSiblingCountMismatchOnNonterminalSiblingsIsInvariantViolation() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    var seeded = seedSucceededToolPhase(fixture.store, baseline, List.of("call-1", "call-2"));
    UUID modelId = seeded.modelInvocationId();
    UUID assistantId = seeded.assistantEntryId();
    UUID toolId =
        seedToolInvocation(
            fixture.store, modelId, assistantId, 0, "call-1", ToolInvocationStatus.READY);
    requestThreadWork(fixture.store, baseline.threadId());

    // 2 个 call 但只有 1 个非 terminal sibling：数量一致性校验先于状态分类 -> ISE 回滚，绝非 blocker。
    assertThrows(IllegalStateException.class, () -> fixture.nextClaim(baseline.threadId()));
    assertEquals(4, path(fixture.store, baseline.threadId()).entries().size());
    // ISE 回滚后 Tool 行保持原状；ToolInvocation 无 resultEntryId 字段。
    assertEquals(ToolInvocationStatus.READY, tool(fixture.store, toolId).status());
  }

  @Test
  void assistantWithCallsButEmptySiblingsIsInvariantViolationNotNormalization() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    var seeded = seedSucceededToolPhase(fixture.store, baseline, List.of("call-1", "call-2"));
    UUID assistantId = seeded.assistantEntryId();
    // 即使已有 queued USER，也绝不降级为历史 normalization：空 siblings 是不变量违反。
    seedCommand(
        fixture.store, baseline.threadId(), new UserMessageCommandPayload(userMessage("hi")));
    requestThreadWork(fixture.store, baseline.threadId());

    // assistant 有 2 个 call 但没有 sibling：不变量违反（旧行为静默历史 normalization）-> ISE 回滚，resolver 不被调用。
    assertThrows(IllegalStateException.class, () -> fixture.nextClaim(baseline.threadId()));
    assertEquals(4, path(fixture.store, baseline.threadId()).entries().size());
    assertEquals(assistantId, thread(fixture.store, baseline.threadId()).headEntryId());
    assertEquals(0, fixture.resolver.calls);
  }

  @Test
  void nonSucceededModelCannotAttachToAssistantMessage() {
    for (ModelInvocationStatus status :
        List.of(
            ModelInvocationStatus.FAILED,
            ModelInvocationStatus.CANCELLED,
            ModelInvocationStatus.UNKNOWN)) {
      Fixture fixture = fixture();
      var baseline = seedOpenInputTurn(fixture.store);
      UUID modelId =
          seedModelInvocation(
              fixture.store,
              baseline.threadId(),
              baseline.turnStartEntryId(),
              baseline.userEntryId(),
              status,
              tooledRequest(List.of("bash")),
              null,
              new ModelInvocationError(ProviderErrorKind.TRANSIENT, "boom"));
      UUID assistantId = insertAssistantWithCalls(fixture.store, baseline, List.of("call-1"));

      // Store 在 result attach 边界即拒绝非 SUCCEEDED model 指向普通 Assistant MESSAGE。
      assertThrows(
          IllegalArgumentException.class,
          () ->
              transitionModel(fixture.store, modelId, m -> m.attachResultEntry(assistantId, NOW)));
      assertEquals(4, path(fixture.store, baseline.threadId()).entries().size());
      assertEquals(assistantId, thread(fixture.store, baseline.threadId()).headEntryId());
      assertNull(model(fixture.store, modelId).resultEntryId());
      assertEquals(0, fixture.resolver.calls);
    }
  }

  @Test
  void modelResultToolCallsMismatchingAssistantAreRejectedAtAttach() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    ModelRequestSpec request = tooledRequest(List.of("bash"));
    ProviderResponse response = successResponse(List.of("call-1", "call-2"), "bash");
    UUID modelId =
        seedModelInvocation(
            fixture.store,
            baseline.threadId(),
            baseline.turnStartEntryId(),
            baseline.userEntryId(),
            ModelInvocationStatus.SUCCEEDED,
            request,
            response,
            null);
    // assistant 与 response 不一致（第二个 call id 不同）：strict attach 校验要求完整 payload 全等，首附即拒。
    UUID assistantId =
        insertAssistantPayload(
            fixture.store,
            baseline,
            new MessagePayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(
                        new ToolCallMessageContent("call-1", "bash", "bash", "{}"),
                        new ToolCallMessageContent("call-X", "bash", "bash", "{}"))),
                assistantMetadata(GenerationStopReason.COMPLETE),
                null));
    assertThrows(
        IllegalArgumentException.class,
        () -> transitionModel(fixture.store, modelId, m -> m.attachResultEntry(assistantId, NOW)));
    assertEquals(4, path(fixture.store, baseline.threadId()).entries().size());
    assertEquals(assistantId, thread(fixture.store, baseline.threadId()).headEntryId());
    assertNull(model(fixture.store, modelId).resultEntryId());
    assertTrue(toolsByAssistant(fixture.store, assistantId).isEmpty());
  }

  @Test
  void lostClaimAtToolBatchFenceRollsBackAllMutations() {
    InMemoryHarnessStore real = new InMemoryHarnessStore();
    Fixture fixture = fixture(claimLosingStore(real, 2));
    var chain =
        seedToolChain(
            real,
            List.of("call-1", "call-2"),
            ModelInvocationStatus.SUCCEEDED,
            List.of(ToolInvocationStatus.SUCCEEDED, ToolInvocationStatus.SUCCEEDED));
    requestThreadWork(real, chain.turn().threadId());
    ClaimedWork claim = claimThreadWork(real, chain.turn().threadId());

    // fence 调用序列：claimOwned（1）-> tool batch final fence（2）丢失 -> 整事务回滚（零 TOOL Entry / 挂载）。
    assertEquals(ThreadProcessResult.LOST_OWNERSHIP, fixture.processor.process(claim));

    assertEquals(4, path(real, chain.turn().threadId()).entries().size());
    // LOST 回滚后 Tool 行保持原状；ToolInvocation 无 resultEntryId 字段。
    assertEquals(
        ToolInvocationStatus.SUCCEEDED, tool(real, chain.toolInvocationIds().get(0)).status());
    assertEquals(
        ToolInvocationStatus.SUCCEEDED, tool(real, chain.toolInvocationIds().get(1)).status());
    assertEquals(chain.assistantEntryId(), thread(real, chain.turn().threadId()).headEntryId());
  }

  @Test
  void binaryResultContentRollsBackAtomically() {
    Fixture fixture = fixture();
    var baseline = seedOpenInputTurn(fixture.store);
    var seeded = seedSucceededToolPhase(fixture.store, baseline, List.of("call-1", "call-2"));
    UUID modelId = seeded.modelInvocationId();
    UUID assistantId = seeded.assistantEntryId();
    UUID tool0 =
        seedToolInvocation(
            fixture.store, modelId, assistantId, 0, "call-1", ToolInvocationStatus.READY);
    UUID tool1 =
        seedToolInvocation(
            fixture.store, modelId, assistantId, 1, "call-2", ToolInvocationStatus.SUCCEEDED);
    succeedToolWith(
        fixture.store,
        tool0,
        new ToolResult(
            "call-1",
            List.of(new BinaryResultContent("application/octet-stream", new byte[] {1, 2})),
            false,
            "{}"));
    requestThreadWork(fixture.store, baseline.threadId());

    // Binary content 无法进入 Session 语义消息：mapper IAE -> 事务回滚零写入。
    assertThrows(IllegalArgumentException.class, () -> fixture.nextClaim(baseline.threadId()));
    assertEquals(4, path(fixture.store, baseline.threadId()).entries().size());
    // IAE 回滚后 Tool 行保持原状；ToolInvocation 无 resultEntryId 字段。
    assertEquals(ToolInvocationStatus.SUCCEEDED, tool(fixture.store, tool0).status());
    assertEquals(ToolInvocationStatus.SUCCEEDED, tool(fixture.store, tool1).status());
    assertEquals(assistantId, thread(fixture.store, baseline.threadId()).headEntryId());
  }
}
