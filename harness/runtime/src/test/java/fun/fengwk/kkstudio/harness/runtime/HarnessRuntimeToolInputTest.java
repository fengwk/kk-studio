package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.ASK_USER_QUESTIONNAIRE;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T3;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T5;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T6;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.TestClock;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.ToolBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.beginDispatchTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.cancelTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.markRunningTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.parkForInput;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedAskUserBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedToolBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.setWaitingApproval;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.succeedTool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException.Reason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * {@code submitToolInput}：接受冻结问卷回答、精确 replay、冲突与不适用，以及崩溃恢复语义。
 *
 * <p>测试意图按安全边界分组：（1）接受必须在同一短事务内写入结果、回执与 Thread 推进，答案按冻结问卷规范化；（2）同一提交身份 + 同一答案 是幂等
 * replay（不重复推进、不追加第二条结果），不同提交身份或不同答案是 mismatch；（3）非法答案只拒绝、绝不落库、且错误信息不回显提交值； （4）非等待目标（READY / 审批中 /
 * Stop 后 CANCELLED / 非本 Thread）一律不适用——问答与审批互不代替。
 */
class HarnessRuntimeToolInputTest {

  private InMemoryHarnessStore store;
  private TestClock clock;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    clock = new TestClock(T5);
    runtime = HarnessRuntimeTestSupport.runtime(store, clock);
  }

  private static ToolInputSubmissionCommand command(
      UUID threadId, UUID toolId, UUID submissionId, String actor, List<List<String>> answers) {
    return new ToolInputSubmissionCommand(threadId, toolId, submissionId, actor, false, answers);
  }

  private static ToolInputSubmissionCommand declined(
      UUID threadId, UUID toolId, UUID submissionId, String actor) {
    return new ToolInputSubmissionCommand(threadId, toolId, submissionId, actor, true, List.of());
  }

  /** 接受：结果 + 回执与 Thread 推进同事务落盘，并登记 THREAD Work 以物化结果。 */
  @Test
  void acceptedSubmissionWritesResultReceiptAndThreadWork() {
    ToolBaseline baseline = seedAskUserBaseline(store);
    parkForInput(store, baseline, T3);
    UUID submissionId = TestIds.id(11);

    ToolInputAcceptance accepted =
        runtime.submitToolInput(
            command(
                baseline.threadId(),
                baseline.toolId(),
                submissionId,
                "alice",
                List.of(List.of("fast"), List.of("c", "a"))));

    assertEquals(baseline.threadId(), accepted.threadId());
    assertEquals(baseline.toolId(), accepted.toolInvocationId());
    assertEquals(submissionId, accepted.receipt().submissionId());
    assertEquals("alice", accepted.receipt().actor());
    assertEquals(T5, accepted.receipt().acceptedAt());
    assertFalse(accepted.materialized());
    ToolInvocation stored =
        store.transaction(tx -> tx.findToolInvocation(baseline.toolId()).orElseThrow());
    assertEquals(ToolInvocationStatus.SUCCEEDED, stored.status());
    // 人工作答不是执行：attempt 保持 0，且绝不携带 approval。
    assertEquals(0, stored.attempt());
    assertNull(stored.approval());
    assertNull(stored.error());
    assertFalse(stored.result().error());
    // 答案按冻结问卷规范化：多选按选项顺序、单选保持单一值。
    assertEquals("{\"answers\":[[\"fast\"],[\"a\",\"c\"]]}", stored.result().detailsJson());
    assertEquals(submissionId, stored.inputReceipt().submissionId());
    assertEquals("alice", stored.inputReceipt().actor());
    assertEquals(T5, stored.inputReceipt().acceptedAt());
    assertEquals(T5, stored.updatedAt());
    // baseline 推进一次 head（1），冻结等待再 +1（2），接受回答再 +1（3）。
    ThreadState thread = store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    assertEquals(3L, thread.version());
    Work threadWork =
        store.transaction(
            tx ->
                tx.findWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()))
                    .orElseThrow());
    assertEquals(1L, threadWork.wakeVersion());
    // 结果物化由 THREAD Work 驱动，不再重新请求 TOOL Work。
    assertNull(
        store.transaction(
            tx ->
                tx.findWork(new WorkTarget(WorkTargetType.TOOL, baseline.toolId())).orElse(null)));
  }

  /** 崩溃恢复：接受已落盘但响应丢失时，同一提交身份 + 等价答案必须精确 replay 且不产生任何新事实。 */
  @Test
  void acceptedSubmissionReplaysExactlyWithoutNewFacts() {
    ToolBaseline baseline = seedAskUserBaseline(store);
    parkForInput(store, baseline, T3);
    UUID submissionId = TestIds.id(12);
    ToolInputAcceptance accepted =
        runtime.submitToolInput(
            command(
                baseline.threadId(),
                baseline.toolId(),
                submissionId,
                "alice",
                List.of(List.of("safe"), List.of("b"))));
    clock.advance(T2);

    ToolInputAcceptance replayed =
        runtime.submitToolInput(
            command(
                baseline.threadId(),
                baseline.toolId(),
                submissionId,
                "alice",
                List.of(List.of("safe"), List.of("b"))));

    assertEquals(accepted, replayed);
    // replay 不触碰 durable 事实：acceptedAt / updatedAt / Thread version / Work 全部保持。
    assertEquals(T5, replayed.receipt().acceptedAt());
    assertFalse(replayed.materialized());
    assertEquals(
        3L, store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow()).version());
    assertEquals(
        1L,
        store
            .transaction(
                tx ->
                    tx.findWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()))
                        .orElseThrow())
            .wakeVersion());
  }

  /** 另一个提交身份或另一个操作者：都是 mismatch，且不得覆盖已接受的答案。 */
  @Test
  void anotherSubmissionIdentityOrActorIsRejected() {
    ToolBaseline baseline = seedAskUserBaseline(store);
    parkForInput(store, baseline, T3);
    UUID submissionId = TestIds.id(13);
    List<List<String>> answers = List.of(List.of("fast"), List.of("a"));
    runtime.submitToolInput(
        command(baseline.threadId(), baseline.toolId(), submissionId, "alice", answers));

    HarnessRuntimeConflictException anotherSubmission =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.submitToolInput(
                    command(
                        baseline.threadId(), baseline.toolId(), TestIds.id(14), "alice", answers)));
    assertEquals(Reason.INPUT_SUBMISSION_MISMATCH, anotherSubmission.reason());
    HarnessRuntimeConflictException anotherActor =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.submitToolInput(
                    command(baseline.threadId(), baseline.toolId(), submissionId, "bob", answers)));
    assertEquals(Reason.INPUT_SUBMISSION_MISMATCH, anotherActor.reason());
    ToolInvocation stored =
        store.transaction(tx -> tx.findToolInvocation(baseline.toolId()).orElseThrow());
    assertEquals(submissionId, stored.inputReceipt().submissionId());
    assertEquals("{\"answers\":[[\"fast\"],[\"a\"]]}", stored.result().detailsJson());
  }

  /** 同一提交身份携带不同答案：mismatch（重试必须携带相同答案才会被接受为原回执）。 */
  @Test
  void sameSubmissionWithDifferentAnswersIsRejected() {
    ToolBaseline baseline = seedAskUserBaseline(store);
    parkForInput(store, baseline, T3);
    UUID submissionId = TestIds.id(15);
    runtime.submitToolInput(
        command(
            baseline.threadId(),
            baseline.toolId(),
            submissionId,
            "alice",
            List.of(List.of("fast"), List.of("a"))));

    HarnessRuntimeConflictException conflict =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.submitToolInput(
                    command(
                        baseline.threadId(),
                        baseline.toolId(),
                        submissionId,
                        "alice",
                        List.of(List.of("safe"), List.of("a")))));

    assertEquals(Reason.INPUT_SUBMISSION_MISMATCH, conflict.reason());
  }

  /** 非法答案：类型化拒绝、store 不变、等待继续存在，且错误信息绝不回显提交值。 */
  @Test
  void invalidAnswersAreRejectedWithoutMutationAndWithoutLeakingValues() {
    ToolBaseline baseline = seedAskUserBaseline(store);
    ToolInvocation waiting = parkForInput(store, baseline, T3);
    UUID submissionId = TestIds.id(16);
    String secret = "secret-customer-name";

    List<List<String>> missingQuestion = List.of(List.of("fast"));
    List<List<String>> twoCustomAnswers = List.of(List.of(secret, "another"), List.of("a"));
    List<List<String>> blankAnswer = List.of(List.of("   "), List.of("a"));
    for (List<List<String>> invalid : List.of(missingQuestion, twoCustomAnswers, blankAnswer)) {
      HarnessRuntimeConflictException conflict =
          assertThrows(
              HarnessRuntimeConflictException.class,
              () ->
                  runtime.submitToolInput(
                      command(
                          baseline.threadId(), baseline.toolId(), submissionId, "alice", invalid)));
      assertEquals(Reason.INPUT_SUBMISSION_INVALID, conflict.reason());
      // 拒绝只报告问题下标与原因，不回显任何提交值。
      assertFalse(conflict.getMessage().contains(secret));
    }
    assertEquals(
        waiting, store.transaction(tx -> tx.findToolInvocation(baseline.toolId()).orElseThrow()));
  }

  /** 明确拒答：成功结果 {@code {"declined":true}} 并记录同样回执，绝不虚构默认答案。 */
  @Test
  void declinedSubmissionSucceedsWithDeclinedResult() {
    ToolBaseline baseline = seedAskUserBaseline(store);
    parkForInput(store, baseline, T3);

    ToolInputAcceptance declined =
        runtime.submitToolInput(
            declined(baseline.threadId(), baseline.toolId(), TestIds.id(17), "alice"));

    ToolInvocation stored =
        store.transaction(tx -> tx.findToolInvocation(baseline.toolId()).orElseThrow());
    assertEquals(ToolInvocationStatus.SUCCEEDED, stored.status());
    assertFalse(stored.result().error());
    assertEquals("{\"declined\":true}", stored.result().detailsJson());
    assertEquals(TestIds.id(17), declined.receipt().submissionId());
    assertEquals(TestIds.id(17), stored.inputReceipt().submissionId());
  }

  /** 非等待目标一律不适用：READY、审批中、Stop 后 CANCELLED、以及不属于该 Thread 的调用。 */
  @Test
  void nonWaitingTargetsAreNotApplicable() {
    ToolBaseline ready = seedAskUserBaseline(store);
    ToolInputSubmissionCommand readyCommand =
        command(
            ready.threadId(),
            ready.toolId(),
            TestIds.id(18),
            "alice",
            List.of(List.of("fast"), List.of("a")));
    assertEquals(
        Reason.INPUT_SUBMISSION_NOT_APPLICABLE,
        assertThrows(
                HarnessRuntimeConflictException.class, () -> runtime.submitToolInput(readyCommand))
            .reason());

    ToolBaseline approval = seedToolBaseline(store);
    setWaitingApproval(store, approval);
    ToolInputSubmissionCommand approvalCommand =
        command(
            approval.threadId(),
            approval.toolId(),
            TestIds.id(19),
            "alice",
            List.of(List.of("fast")));
    assertEquals(
        Reason.INPUT_SUBMISSION_NOT_APPLICABLE,
        assertThrows(
                HarnessRuntimeConflictException.class,
                () -> runtime.submitToolInput(approvalCommand))
            .reason());

    ToolBaseline cancelled = seedAskUserBaseline(store);
    parkForInput(store, cancelled, T3);
    cancelTool(store, cancelled.toolId());
    ToolInputSubmissionCommand lateCommand =
        command(
            cancelled.threadId(),
            cancelled.toolId(),
            TestIds.id(20),
            "alice",
            List.of(List.of("fast"), List.of("a")));
    assertEquals(
        Reason.INPUT_SUBMISSION_NOT_APPLICABLE,
        assertThrows(
                HarnessRuntimeConflictException.class, () -> runtime.submitToolInput(lateCommand))
            .reason());
    assertEquals(
        ToolInvocationStatus.CANCELLED,
        store.transaction(tx -> tx.findToolInvocation(cancelled.toolId()).orElseThrow()).status());

    ToolBaseline foreign = seedAskUserBaseline(store);
    parkForInput(store, foreign, T3);
    ToolInputSubmissionCommand foreignThread =
        command(
            ready.threadId(),
            foreign.toolId(),
            TestIds.id(21),
            "alice",
            List.of(List.of("fast"), List.of("a")));
    assertEquals(
        Reason.INPUT_SUBMISSION_NOT_APPLICABLE,
        assertThrows(
                HarnessRuntimeConflictException.class, () -> runtime.submitToolInput(foreignThread))
            .reason());
  }

  /** 未知 Thread / 未知调用：not applicable，而不是泄漏其他 Thread 的事实。 */
  @Test
  void unknownThreadOrInvocationIsNotApplicable() {
    ToolBaseline baseline = seedAskUserBaseline(store);
    parkForInput(store, baseline, T3);
    UUID unknownThread = TestIds.id(22);

    HarnessRuntimeConflictException unknownThreadFailure =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.submitToolInput(
                    command(
                        unknownThread,
                        baseline.toolId(),
                        TestIds.id(23),
                        "alice",
                        List.of(List.of("fast"), List.of("a")))));
    assertEquals(Reason.INPUT_SUBMISSION_NOT_APPLICABLE, unknownThreadFailure.reason());

    HarnessRuntimeConflictException unknownToolFailure =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.submitToolInput(
                    command(
                        baseline.threadId(),
                        TestIds.id(24),
                        TestIds.id(25),
                        "alice",
                        List.of(List.of("fast"), List.of("a")))));
    assertEquals(Reason.INPUT_SUBMISSION_NOT_APPLICABLE, unknownToolFailure.reason());
  }

  /** 并发同 submissionId：恰好被接受一次，另一个请求读到的已是同一 durable 结果（不允许双写）。 */
  @Test
  void concurrentIdenticalSubmissionsAcceptExactlyOnce() throws Exception {
    ToolBaseline baseline = seedAskUserBaseline(store);
    parkForInput(store, baseline, T3);
    UUID submissionId = TestIds.id(26);
    ToolInputSubmissionCommand concurrent =
        command(
            baseline.threadId(),
            baseline.toolId(),
            submissionId,
            "alice",
            List.of(List.of("fast"), List.of("a")));
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<ToolInputAcceptance> first = executor.submit(() -> submitAfter(start, concurrent));
      Future<ToolInputAcceptance> second = executor.submit(() -> submitAfter(start, concurrent));
      start.countDown();
      ToolInputAcceptance firstResult = first.get(10, TimeUnit.SECONDS);
      ToolInputAcceptance secondResult = second.get(10, TimeUnit.SECONDS);
      assertEquals(firstResult, secondResult);
      assertEquals(submissionId, firstResult.receipt().submissionId());
    }
    // 恰好一次接受：Thread version 只 +1（冻结等待 2 -> 接受 3），答案只归一化一次。
    assertEquals(
        3L, store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow()).version());
    JsonNode details =
        JsonValues.readTree(
            store
                .transaction(tx -> tx.findToolInvocation(baseline.toolId()).orElseThrow())
                .result()
                .detailsJson());
    assertEquals("fast", details.get("answers").get(0).get(0).textValue());
    assertNotEquals(0, details.get("answers").size());
  }

  /** 已执行成功（无回执）的调用不是人工输入：提交必须不适用，绝不把执行结果当回答。 */
  @Test
  void executedSuccessWithoutReceiptIsNotAnInputTarget() {
    ToolBaseline baseline = seedAskUserBaseline(store);
    beginDispatchTool(store, baseline.toolId());
    markRunningTool(store, baseline.toolId());
    succeedTool(store, baseline.toolId());

    HarnessRuntimeConflictException conflict =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.submitToolInput(
                    command(
                        baseline.threadId(),
                        baseline.toolId(),
                        TestIds.id(27),
                        "alice",
                        List.of(List.of("fast"), List.of("a")))));
    assertEquals(Reason.INPUT_SUBMISSION_NOT_APPLICABLE, conflict.reason());
  }

  /** 冻结问卷来自 Assistant ToolCall：调用行本身不复制问卷，题库只以 argumentsJson 为事实源。 */
  @Test
  void questionnaireRemainsTheOnlyFrozenSource() {
    ToolBaseline baseline = seedAskUserBaseline(store);
    parkForInput(store, baseline, T3);

    ToolInvocation waiting =
        store.transaction(tx -> tx.findToolInvocation(baseline.toolId()).orElseThrow());

    assertEquals(ASK_USER_QUESTIONNAIRE, waiting.call().argumentsJson());
    assertTrue(waiting.binding().contributor().contributorId().equals("builtin"));
  }

  /** 提交回答只触发 THREAD Work（由 ThreadProcessor 物化结果并决策下一步），绝不直接触发 MODEL 或 TOOL Work。 */
  @Test
  void submittingAnswerRequestsOnlyThreadWorkNotModelOrToolWork() {
    ToolBaseline baseline = seedAskUserBaseline(store);
    parkForInput(store, baseline, T3);
    UUID submissionId = TestIds.id(30);

    runtime.submitToolInput(
        command(
            baseline.threadId(),
            baseline.toolId(),
            submissionId,
            "alice",
            List.of(List.of("fast"), List.of("a"))));

    // MODEL 与 TOOL 的工作认领均为空
    Optional<ClaimedWork> modelWork =
        store.transaction(tx -> tx.claimNextWork(WorkTargetType.MODEL, T5, "probe-model", T6));
    assertTrue(modelWork.isEmpty());
    Optional<ClaimedWork> toolWork =
        store.transaction(tx -> tx.claimNextWork(WorkTargetType.TOOL, T5, "probe-tool", T6));
    assertTrue(toolWork.isEmpty());
    // THREAD 的工作认领存在且指向该 thread
    Optional<ClaimedWork> threadWork =
        store.transaction(tx -> tx.claimNextWork(WorkTargetType.THREAD, T5, "probe-thread", T6));
    assertTrue(threadWork.isPresent());
    assertEquals(
        new WorkTarget(WorkTargetType.THREAD, baseline.threadId()), threadWork.get().target());
  }

  /**
   * 工具结果物化（物理删除调用行、追加 Entry）后的真实重放： 同一提交身份与等价规范化答案精确 replay 且 materialized=true，不递增 Thread version
   * 与 wakeVersion； 答案按冻结问卷顺序归一化，多选选项顺序不同但规范化后相同的答案仍精确重放； 其他 submissionId、不同 actor、不同答案报
   * INPUT_SUBMISSION_MISMATCH； 未知 thread、未知 toolInvocationId 报 INPUT_SUBMISSION_NOT_APPLICABLE。
   */
  @Test
  void materializedToolResultReplaysExactlyAndRejectsMismatches() {
    ToolBaseline baseline = seedAskUserBaseline(store);
    parkForInput(store, baseline, T3);
    UUID submissionId = TestIds.id(15);
    List<List<String>> answers = List.of(List.of("safe"), List.of("c", "b"));
    ToolInputSubmissionCommand originalCommand =
        command(baseline.threadId(), baseline.toolId(), submissionId, "alice", answers);

    // 1. 首次接受回答
    ToolInputAcceptance initial = runtime.submitToolInput(originalCommand);
    assertFalse(initial.materialized());
    assertEquals(baseline.threadId(), initial.threadId());
    assertEquals(baseline.toolId(), initial.toolInvocationId());
    assertEquals(submissionId, initial.receipt().submissionId());
    assertEquals("alice", initial.receipt().actor());
    assertEquals(T5, initial.receipt().acceptedAt());

    // 2. 模拟生产物化：读取已接受的 ToolInvocation，通过生产 mapper 构建 history payload，
    // 在单个事务内追加 Entry、推进 Thread head，并物理删除 ToolInvocation 行
    ToolInvocation acceptedTool =
        store.transaction(tx -> tx.findToolInvocation(baseline.toolId()).orElseThrow());
    HistoryPayloadMapper mapper = new HistoryPayloadMapper();
    MessagePayload payload = mapper.toolResultPayload(acceptedTool);

    UUID newEntryId = TestIds.id(50);
    store.transaction(
        tx -> {
          ThreadState thread = tx.lockThread(baseline.threadId()).orElseThrow();
          EntryPath path = tx.loadEntryPath(thread.headEntryId());
          Instant now =
              HarnessStoreTime.notBefore(
                  clock.instant(),
                  thread.updatedAt(),
                  path.head().createdAt(),
                  acceptedTool.updatedAt());
          tx.insertEntry(
              new Entry(newEntryId, path.root().sessionId(), thread.headEntryId(), payload, now));
          tx.updateThread(thread.advanceHead(newEntryId, now));
          tx.lockToolInvocationsByAssistantEntryId(acceptedTool.assistantEntryId());
          tx.deleteToolInvocationsByIds(List.of(baseline.toolId()));
          return null;
        });

    // 确认 ToolInvocation 行已被删除
    assertTrue(store.transaction(tx -> tx.findToolInvocation(baseline.toolId())).isEmpty());

    ThreadState threadBefore =
        store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    Work workBefore =
        store.transaction(
            tx ->
                tx.findWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()))
                    .orElseThrow());

    // 3. 物化后用完全相同的 command 重试：必须返回 materialized=true，相同回执事实，且不产生任何 version/work 变更
    clock.advance(T6);
    ToolInputAcceptance replayed = runtime.submitToolInput(originalCommand);
    assertTrue(replayed.materialized());
    assertEquals(baseline.threadId(), replayed.threadId());
    assertEquals(baseline.toolId(), replayed.toolInvocationId());
    assertEquals(submissionId, replayed.receipt().submissionId());
    assertEquals("alice", replayed.receipt().actor());
    assertEquals(initial.receipt().acceptedAt(), replayed.receipt().acceptedAt());

    ThreadState threadAfter =
        store.transaction(tx -> tx.findThread(baseline.threadId()).orElseThrow());
    Work workAfter =
        store.transaction(
            tx ->
                tx.findWork(new WorkTarget(WorkTargetType.THREAD, baseline.threadId()))
                    .orElseThrow());
    assertEquals(threadBefore.version(), threadAfter.version());
    assertEquals(workBefore.wakeVersion(), workAfter.wakeVersion());

    // 4. 多选选项顺序不同但规范化后相同的答案（["c", "b"] vs ["b", "c"]）：规范化结果相同，成功重放
    List<List<String>> reorderedAnswers = List.of(List.of("safe"), List.of("b", "c"));
    ToolInputAcceptance reorderedReplay =
        runtime.submitToolInput(
            command(
                baseline.threadId(), baseline.toolId(), submissionId, "alice", reorderedAnswers));
    assertTrue(reorderedReplay.materialized());
    assertEquals(initial.receipt().acceptedAt(), reorderedReplay.receipt().acceptedAt());

    // 5. 其他 submissionId、不同 actor、不同答案报 MISMATCH
    HarnessRuntimeConflictException anotherSubmission =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.submitToolInput(
                    command(
                        baseline.threadId(), baseline.toolId(), TestIds.id(16), "alice", answers)));
    assertEquals(Reason.INPUT_SUBMISSION_MISMATCH, anotherSubmission.reason());

    HarnessRuntimeConflictException anotherActor =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.submitToolInput(
                    command(baseline.threadId(), baseline.toolId(), submissionId, "bob", answers)));
    assertEquals(Reason.INPUT_SUBMISSION_MISMATCH, anotherActor.reason());

    HarnessRuntimeConflictException differentAnswers =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.submitToolInput(
                    command(
                        baseline.threadId(),
                        baseline.toolId(),
                        submissionId,
                        "alice",
                        List.of(List.of("fast"), List.of("a")))));
    assertEquals(Reason.INPUT_SUBMISSION_MISMATCH, differentAnswers.reason());

    // 6. 不拥有该物化结果的 foreign threadId -> NOT_APPLICABLE
    ToolBaseline foreignThread = seedToolBaseline(store);
    HarnessRuntimeConflictException foreignConflict =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.submitToolInput(
                    command(
                        foreignThread.threadId(),
                        baseline.toolId(),
                        submissionId,
                        "alice",
                        answers)));
    assertEquals(Reason.INPUT_SUBMISSION_NOT_APPLICABLE, foreignConflict.reason());

    // 7. 从未存在过的未知 toolInvocationId -> NOT_APPLICABLE
    HarnessRuntimeConflictException unknownToolConflict =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.submitToolInput(
                    command(baseline.threadId(), TestIds.id(99), submissionId, "alice", answers)));
    assertEquals(Reason.INPUT_SUBMISSION_NOT_APPLICABLE, unknownToolConflict.reason());
  }

  private ToolInputAcceptance submitAfter(
      CountDownLatch start, ToolInputSubmissionCommand command) {
    try {
      if (!start.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timed out waiting for concurrent start");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(error);
    }
    return runtime.submitToolInput(command);
  }
}
