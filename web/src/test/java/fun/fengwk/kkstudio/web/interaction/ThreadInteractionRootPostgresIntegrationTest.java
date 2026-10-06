package fun.fengwk.kkstudio.web.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.common.schema.ArraySchema;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.ObjectSchema;
import fun.fengwk.kkstudio.harness.common.schema.StringSchema;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcher;
import fun.fengwk.kkstudio.harness.infra.dispatch.HarnessWorkDispatcherConfig;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.ToolApprovalCommand;
import fun.fengwk.kkstudio.harness.runtime.ToolInputAcceptance;
import fun.fengwk.kkstudio.harness.runtime.ToolInputSubmissionCommand;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.join.ThreadJoinRequest;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadYoloMode;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadYoloPolicy;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.interaction.InteractionQueryService;
import fun.fengwk.kkstudio.platform.interaction.InteractionService;
import fun.fengwk.kkstudio.share.ai.interaction.InteractionDTO;
import fun.fengwk.kkstudio.share.ai.interaction.InteractionPageDTO;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * 真实 PostgreSQL + 真实 Runtime + 真实 Tool Processor 上的三级执行树人工交互（approval / ask_user）集成测试。
 *
 * <p>测试意图：只有真实事务、真实祖先链解析、真实 keyset 分页、真实产品归属读与真实 Runtime 写回才能证明交互边界的三件事：
 *
 * <ul>
 *   <li>归属：孙执行（C）的等待调用沿不可变 parent 链汇聚到执行根（R），响应保留原始来源 {@code threadId/sessionId}，与无关执行树严格隔离；
 *   <li>写回：审批/作答只改动 C 自己的 invocation 与 Work，绝不动兄弟/祖先的等待事实；重试幂等、非同答冲突有 durable 证据；
 *   <li>边界：root 的 YOLO 开关不代替用户回答问卷（真实 ToolProcessor 只把 ask_user 冻结为 WAITING_INPUT）。
 * </ul>
 *
 * <p>{@code WAITING_APPROVAL}/{@code WAITING_INPUT} 的冻结通过合法 Store fixture（真实 Runtime 同款
 * READY-&gt;请求等待的 domain transition）构造，不写裸 SQL 绕过不变量；root 问卷场景额外用真实 dispatcher + ToolProcessor 驱动
 * READY ask_user，证明 YOLO 非自动作答。外部模型一律不调用（workers 默认关闭，仅问卷用例启动自持 dispatcher，且 ask_user 不进入 Gateway）。
 */
class ThreadInteractionRootPostgresIntegrationTest extends WebPostgresTestSupport {

  private static final BranchSettings SETTINGS =
      new BranchSettings(
          "default-assistant", new ModelSelection("stub", "acceptance-stub", "default"), null);

  private static final String AGENT = "default-assistant";
  private static final String HASH = "a".repeat(64);
  private static final String ACTOR = "local-user";
  private static final int MAX_TURNS = 5;
  private static final int MAX_DEPTH = 3;
  private static final int MAX_CONCURRENT_CHILDREN = 2;
  private static final int MAX_CONCURRENT_THREADS = 8;
  private static final long AWAIT_SECONDS = 60;

  @Autowired private HarnessRuntime runtime;
  @Autowired private HarnessStore store;
  @Autowired private InteractionQueryService queryService;
  @Autowired private InteractionService interactionService;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private Clock clock;
  @Autowired private ThreadProcessor threadProcessor;
  @Autowired private ModelProcessor modelProcessor;
  @Autowired private ToolProcessor toolProcessor;

  private final List<HarnessWorkDispatcher> testDispatchers = new ArrayList<>();
  private final List<ExecutorService> testExecutors = new ArrayList<>();

  private Instant previousTime;

  @AfterEach
  void stopTestDispatchers() throws InterruptedException {
    for (HarnessWorkDispatcher dispatcher : testDispatchers) {
      dispatcher.stop();
    }
    testDispatchers.clear();
    for (ExecutorService executor : testExecutors) {
      executor.shutdownNow();
    }
    for (ExecutorService executor : testExecutors) {
      if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
        throw new IllegalStateException("test executor did not terminate: " + executor);
      }
    }
    testExecutors.clear();
  }

  /**
   * Chat 执行根：R 派发 A/B，A 再派发孙 C，问卷冻结在 C。查询 root=R 必须以来源 C 的原始 threadId/sessionId 呈现，并附带
   * rootThreadId=R 与根归属的 Chat owner；非根过滤值被拒；另一棵无关执行树（含无产品归属的内部调用）绝不泄漏。
   */
  @Test
  void chatRootProjectsGrandchildInteractionAndIsolatesOtherRoots() {
    Tree tree = buildTree();
    UUID chatId = bindChat(tree.rootSessionId());
    UUID cInvocation = attachWaiting(tree.c(), true, QUESTIONNAIRE, true);

    // 无产品归属的无关执行树：等待行存在但任何 owner 都无法解析，因此对任何查询都不可见。
    Seed ownerless = seedStandaloneRoot(false, "{}", true, true, false);

    InteractionPageDTO page = queryService.listInteractions(tree.root(), null, 50);
    assertEquals(1, page.getItems().size(), "only the owner-bound grandchild row is visible");
    assertNull(page.getNextCursor());
    InteractionDTO item = page.getItems().getFirst();
    assertEquals(cInvocation.toString(), item.getInteractionId());
    assertEquals(tree.c().toString(), item.getThreadId(), "source thread stays the grandchild");
    assertEquals(tree.cSessionId().toString(), item.getSessionId());
    assertEquals(tree.root().toString(), item.getRootThreadId(), "root is the execution root");
    assertEquals("CHAT", item.getOwner().getType());
    assertEquals(chatId.toString(), item.getOwner().getChatId());
    assertEquals(ToolInvocationStatus.WAITING_INPUT.name(), item.getStatus());
    assertEquals("ask_user", item.getToolName());
    assertEquals(QUESTIONNAIRE, item.getArgumentsJson());
    assertNull(item.getApprovalJson());

    // 祖先链是归属事实源：C -> A -> R。
    assertEquals(List.of(tree.c(), tree.a(), tree.root()), runtime.findAncestorChain(tree.c()));

    // 非 canonical 执行根（中间父/自身）被拒为非法参数，而不是静默返回别的树。
    assertThrows(
        IllegalArgumentException.class, () -> queryService.listInteractions(tree.a(), null, 50));

    // 无关执行根看不到本树的等待行。
    assertTrue(
        queryService.listInteractions(ownerless.threadId(), null, 50).getItems().isEmpty(),
        "an ownerless root exposes nothing");
  }

  /**
   * Issue+Agent 执行根：R' 绑定 Issue，孙 C' 的等待审批沿祖先链归属到 R'，并读取根的 Issue/Agent owner。经 {@link
   * InteractionService} 提交同一审批必须成功，证明先产品层级锁、再 Runtime 写回的祖先归属路径在真实 PG 上成立。
   */
  @Test
  void issueAgentRootResolvesGrandchildInteractionThroughAncestorAttribution() {
    Tree tree = buildTree();
    IssueBinding issue = bindIssueAgent(tree.root());
    UUID cInvocation = attachWaiting(tree.c(), false, "{}", true);

    InteractionPageDTO page = queryService.listInteractions(tree.root(), null, 50);
    assertEquals(1, page.getItems().size());
    InteractionDTO item = page.getItems().getFirst();
    assertEquals(cInvocation.toString(), item.getInteractionId());
    assertEquals(tree.c().toString(), item.getThreadId());
    assertEquals(tree.root().toString(), item.getRootThreadId());
    assertEquals("ISSUE_AGENT", item.getOwner().getType());
    assertEquals(issue.issueId().toString(), item.getOwner().getIssueId());
    assertEquals(AGENT, item.getOwner().getAgentName());
    assertEquals(ToolInvocationStatus.WAITING_APPROVAL.name(), item.getStatus());

    UUID decisionId = UUID.randomUUID();
    ToolInvocation decided =
        interactionService.decideApproval(
            new ToolApprovalCommand(
                tree.c(), cInvocation, ToolApprovalDecision.ALLOWED, decisionId, ACTOR, null));
    // ALLOWED 把调用放回 READY 以便按 completed approval 重新派发，审批决定本身已 durable。
    assertEquals(ToolInvocationStatus.READY, decided.status());
    assertEquals(ToolApprovalDecision.ALLOWED, decided.approval().decision());
  }

  /** root 过滤 + keyset 分页：同一执行根下跨 C/B/A 三个节点的等待行必须全部收敛，即便每页只取一条也不能漏；插在中间的无归属等待行不得打乱游标。 */
  @Test
  void rootFilterPaginationNeverDropsWaitingSiblings() {
    Tree tree = buildTree();
    bindChat(tree.rootSessionId());
    UUID cInvocation = attachWaiting(tree.c(), true, QUESTIONNAIRE, true);
    // 无归属行创建时间插在 C 与 B 之间：它会被归属过滤，但游标仍必须继续前进。
    seedStandaloneRoot(false, "{}", true, true, false);
    UUID bInvocation = attachWaiting(tree.b(), false, "{}", true);
    UUID aInvocation = attachWaiting(tree.a(), false, "{}", true);

    for (int limit = 1; limit <= 3; limit++) {
      List<InteractionDTO> collected = drain(tree.root(), limit);
      Set<String> ids = new HashSet<>();
      for (InteractionDTO dto : collected) {
        ids.add(dto.getInteractionId());
        assertEquals(tree.root().toString(), dto.getRootThreadId());
      }
      assertEquals(
          Set.of(cInvocation.toString(), bInvocation.toString(), aInvocation.toString()),
          ids,
          "limit " + limit + " must collect every waiting sibling exactly once");
      assertEquals(3, collected.size(), "no duplicate rows across pages at limit " + limit);
    }
  }

  /**
   * 审批只写回 C：A 与 R 上的等待审批、版本与 Work 完全不受影响；同一 decisionId 重放不产生第二次持久化，改判则类型化冲突。 派发关系（C 的 join parent
   * 仍是 A）不因根归属的人工审批被改写。
   */
  @Test
  void approvalDecisionWritesBackOnlyToTheGrandchildInvocation() {
    Tree tree = buildTree();
    bindChat(tree.rootSessionId());
    UUID cInvocation = attachWaiting(tree.c(), false, "{}", true);
    UUID aInvocation = attachWaiting(tree.a(), false, "{}", true);
    UUID rInvocation = attachWaiting(tree.root(), false, "{}", true);
    long aVersionBefore = threadState(tree.a()).version();
    long rVersionBefore = threadState(tree.root()).version();
    Instant aUpdatedBefore = toolInvocation(aInvocation).updatedAt();
    Instant rUpdatedBefore = toolInvocation(rInvocation).updatedAt();
    long cThreadVersion = threadState(tree.c()).version();

    UUID decisionId = UUID.randomUUID();
    ToolInvocation decided =
        interactionService.decideApproval(
            new ToolApprovalCommand(
                tree.c(), cInvocation, ToolApprovalDecision.ALLOWED, decisionId, ACTOR, null));
    assertEquals(ToolApprovalDecision.ALLOWED, decided.approval().decision());
    assertEquals(decisionId, decided.approval().decisionId());
    assertEquals(ACTOR, decided.approval().actor());
    assertEquals(
        cThreadVersion + 1,
        threadState(tree.c()).version(),
        "deciding the grandchild touches exactly its own thread version once");
    assertTrue(
        hasWork(WorkTargetType.TOOL, cInvocation),
        "an ALLOW decision requests TOOL work for the decided invocation");

    // 兄弟/祖先的等待事实、thread 版本与 Work 不被误决策。
    assertEquals(ToolInvocationStatus.WAITING_APPROVAL, toolInvocation(aInvocation).status());
    assertNull(toolInvocation(aInvocation).approval().decision());
    assertEquals(ToolInvocationStatus.WAITING_APPROVAL, toolInvocation(rInvocation).status());
    assertNull(toolInvocation(rInvocation).approval().decision());
    assertEquals(aVersionBefore, threadState(tree.a()).version());
    assertEquals(rVersionBefore, threadState(tree.root()).version());
    assertEquals(aUpdatedBefore, toolInvocation(aInvocation).updatedAt());
    assertEquals(rUpdatedBefore, toolInvocation(rInvocation).updatedAt());
    assertFalse(hasWork(WorkTargetType.TOOL, aInvocation));
    assertFalse(hasWork(WorkTargetType.TOOL, rInvocation));

    // 同 decisionId 精确重放：不 bump 版本、不重复请求 Work。
    long versionAfterDecision = threadState(tree.c()).version();
    ToolInvocation replayed =
        interactionService.decideApproval(
            new ToolApprovalCommand(
                tree.c(), cInvocation, ToolApprovalDecision.ALLOWED, decisionId, ACTOR, null));
    assertEquals(decisionId, replayed.approval().decisionId());
    assertEquals(
        versionAfterDecision,
        threadState(tree.c()).version(),
        "an exact approval replay must not touch durable state again");

    // 改判被类型化拒绝。
    HarnessRuntimeConflictException conflict =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                interactionService.decideApproval(
                    new ToolApprovalCommand(
                        tree.c(),
                        cInvocation,
                        ToolApprovalDecision.DENIED,
                        decisionId,
                        ACTOR,
                        "no")));
    assertEquals(
        HarnessRuntimeConflictException.Reason.APPROVAL_DECISION_MISMATCH, conflict.reason());

    // 后续回执仍沿直接派发边界：C 的 join parent 是 A，人工审批不产生任何发给 R 的结果命令。
    assertEquals(tree.a(), joinParentOf(tree.c()));
  }

  /** 作答只写回 C：接受产生 durable 回执并请求 C 自己的 THREAD Work；同 submissionId+actor+答案重放不产生第二次持久化；同身份改答案冲突。 */
  @Test
  void askUserSubmissionWritesBackOnlyToTheGrandchildInvocationAndIsIdempotent() {
    Tree tree = buildTree();
    bindChat(tree.rootSessionId());
    UUID cInvocation = attachWaiting(tree.c(), true, QUESTIONNAIRE, true);
    long cVersionBefore = threadState(tree.c()).version();

    UUID submissionId = UUID.randomUUID();
    List<List<String>> answers =
        List.of(List.of("stable"), List.of("typed-routing", "stop-boundary"));
    ToolInputAcceptance accepted =
        interactionService.submitInput(
            new ToolInputSubmissionCommand(
                tree.c(), cInvocation, submissionId, ACTOR, false, answers));
    assertEquals(tree.c(), accepted.threadId());
    assertEquals(cInvocation, accepted.toolInvocationId());
    assertEquals(submissionId, accepted.receipt().submissionId());
    assertFalse(accepted.materialized());
    assertEquals(ToolInvocationStatus.SUCCEEDED, toolInvocation(cInvocation).status());
    assertNotNull(toolInvocation(cInvocation).inputReceipt());
    assertEquals(
        cVersionBefore + 1,
        threadState(tree.c()).version(),
        "accepting the answer touches exactly the grandchild thread version once");
    assertTrue(hasWork(WorkTargetType.THREAD, tree.c()));

    // 精确重放：同身份与同答案不再次写入。
    long versionAfterAccept = threadState(tree.c()).version();
    ToolInputAcceptance replayed =
        interactionService.submitInput(
            new ToolInputSubmissionCommand(
                tree.c(), cInvocation, submissionId, ACTOR, false, answers));
    assertEquals(submissionId, replayed.receipt().submissionId());
    assertEquals(versionAfterAccept, threadState(tree.c()).version());

    // 同 submissionId 但不同（且合法的）答案：冲突，不改写已接受事实。
    HarnessRuntimeConflictException changedAnswers =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                interactionService.submitInput(
                    new ToolInputSubmissionCommand(
                        tree.c(),
                        cInvocation,
                        submissionId,
                        ACTOR,
                        false,
                        List.of(List.of("canary"), List.of("typed-routing")))));
    assertEquals(
        HarnessRuntimeConflictException.Reason.INPUT_SUBMISSION_MISMATCH, changedAnswers.reason());

    // 相同答案但不同 submissionId：仍属于“已被其他提交作答”。
    HarnessRuntimeConflictException otherSubmission =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                interactionService.submitInput(
                    new ToolInputSubmissionCommand(
                        tree.c(), cInvocation, UUID.randomUUID(), ACTOR, false, answers)));
    assertEquals(
        HarnessRuntimeConflictException.Reason.INPUT_SUBMISSION_MISMATCH, otherSubmission.reason());

    assertEquals(tree.a(), joinParentOf(tree.c()));
  }

  /**
   * root 打开 YOLO 也不代替用户回答问卷：真实 ToolProcessor 把 READY 的内置 ask_user 冻结为 WAITING_INPUT，既不进入 Gateway
   * 也不写回执。
   */
  @Test
  void yoloEnabledRootNeverAutoAnswersAskUser() {
    Seed seed = seedStandaloneRoot(true, QUESTIONNAIRE, false, true, true);
    UUID chatId = bindChat(seed.sessionId());

    // 前置事实：根 YOLO = ENABLE，问卷调用仍是 READY 且已有 TOOL Work（尚未被处理）。
    assertEquals(ThreadYoloMode.ENABLE, threadState(seed.threadId()).yoloPolicy().mode());
    assertEquals(ToolInvocationStatus.READY, toolInvocation(seed.invocationId()).status());
    assertTrue(hasWork(WorkTargetType.TOOL, seed.invocationId()));

    startTestDispatcher();

    awaitTrue(
        () -> toolInvocation(seed.invocationId()).status() == ToolInvocationStatus.WAITING_INPUT,
        "the real ToolProcessor must freeze ask_user instead of auto-answering it");
    ToolInvocation frozen = toolInvocation(seed.invocationId());
    // YOLO 没有把它自动放行/执行，也没有伪造回执。
    assertNull(frozen.approval());
    assertNull(frozen.inputReceipt());
    assertNull(frozen.result());
    assertEquals(ThreadYoloMode.ENABLE, threadState(seed.threadId()).yoloPolicy().mode());

    InteractionPageDTO page = queryService.listInteractions(seed.threadId(), null, 50);
    assertEquals(1, page.getItems().size());
    InteractionDTO item = page.getItems().getFirst();
    assertEquals(seed.invocationId().toString(), item.getInteractionId());
    assertEquals(seed.threadId().toString(), item.getRootThreadId());
    assertEquals(seed.threadId().toString(), item.getThreadId());
    assertEquals("CHAT", item.getOwner().getType());
    assertEquals(chatId.toString(), item.getOwner().getChatId());
    assertEquals(ToolInvocationStatus.WAITING_INPUT.name(), item.getStatus());
  }

  // ------------------------------------------------------------------ fixture

  /** 用真实 Runtime 接受 R -> {A,B} 且 A -> C 的可执行树；children 携带真实 join（parent/child 订阅关系）。 */
  private Tree buildTree() {
    UUID rootThreadId = acceptRoot("root work");
    UUID rootSessionId = threadState(rootThreadId).sessionId();
    UUID aThreadId = acceptChild(rootThreadId, "A work");
    UUID bThreadId = acceptChild(rootThreadId, "B work");
    UUID cThreadId = acceptChild(aThreadId, "C work");
    return new Tree(
        rootThreadId,
        rootSessionId,
        aThreadId,
        bThreadId,
        cThreadId,
        threadState(cThreadId).sessionId());
  }

  private UUID acceptRoot(String prompt) {
    UUID threadId = UUID.randomUUID();
    runtime.acceptCommands(
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewRootSession(UUID.randomUUID(), threadId, SETTINGS, false),
            List.of(promptCommand(prompt))),
        AcceptancePreflight.IDENTITY);
    return threadId;
  }

  private UUID acceptChild(UUID parentThreadId, String prompt) {
    UUID threadId = UUID.randomUUID();
    UUID invocationId = UUID.randomUUID();
    runtime.acceptCommandsAndJoin(
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.NewChildSession(
                UUID.randomUUID(), threadId, SETTINGS, parentThreadId),
            List.of(promptCommand(prompt))),
        taskJoin(invocationId, parentThreadId, headEntryId(parentThreadId), prompt),
        AcceptancePreflight.IDENTITY);
    return threadId;
  }

  private static NewThreadCommand promptCommand(String prompt) {
    return new NewThreadCommand(
        new UserMessageCommandPayload(AgentMessage.user(prompt)), UUID.randomUUID());
  }

  private static ThreadJoinRequest taskJoin(
      UUID invocationId, UUID parentThreadId, UUID expectedParentHeadEntryId, String prompt) {
    return new ThreadJoinRequest(
        invocationId,
        parentThreadId,
        expectedParentHeadEntryId,
        ThreadCommandPayloadJsonCodec.requestHash(
            new UserMessageCommandPayload(AgentMessage.user(prompt))),
        AGENT,
        MAX_TURNS,
        MAX_DEPTH,
        MAX_CONCURRENT_CHILDREN,
        MAX_CONCURRENT_THREADS);
  }

  /** 在执行根的产品绑定上插入一个 Chat owner，并返回 chatId。 */
  private UUID bindChat(UUID sessionId) {
    UUID chatId = UUID.randomUUID();
    jdbc.update(
        "insert into chat (id, title, agent_name) values (?, ?, ?)",
        chatId,
        "chat-" + chatId,
        AGENT);
    jdbc.update("insert into chat_session (session_id, chat_id) values (?, ?)", sessionId, chatId);
    return chatId;
  }

  /** 在执行根上插入一个 Issue+Agent 归属，并返回 product 层级 id。 */
  private IssueBinding bindIssueAgent(UUID rootThreadId) {
    UUID projectId = UUID.randomUUID();
    jdbc.update(
        "insert into project (id, title, description, workflow)"
            + " values (?, ?, '', '{\"states\":[]}'::jsonb)",
        projectId,
        "project-" + projectId);
    UUID issueId = UUID.randomUUID();
    jdbc.update(
        "insert into project_issue (id, project_id, number, title, state)"
            + " values (?, ?, ?, ?, 'INIT')",
        issueId,
        projectId,
        1L,
        "issue-" + issueId);
    jdbc.update(
        "insert into project_issue_agent_thread (issue_id, agent_name, thread_id)"
            + " values (?, ?, ?)",
        issueId,
        AGENT,
        rootThreadId);
    return new IssueBinding(projectId, issueId);
  }

  /**
   * 在既有 Thread 上按真实 Runtime 同款 domain 形状挂一个等待调用：ROOT -&gt; TURN_START -&gt; USER -&gt;
   * ASSISTANT(tool call) + SUCCEEDED Model + ToolInvocation；{@code freeze} 时再走 READY-&gt;WAITING
   * 转换。
   */
  private UUID attachWaiting(
      UUID threadId, boolean humanInput, String argumentsJson, boolean freeze) {
    return store.transaction(
        tx -> {
          ThreadState thread = tx.lockThread(threadId).orElseThrow();
          return attachWaiting(tx, thread, humanInput, argumentsJson, freeze);
        });
  }

  /**
   * 独立执行根（无既有 join）：用于 ownerless 等待行与真实 ToolProcessor 的 YOLO 问卷场景。{@code requestToolWork} 时登记 TOOL
   * Work。
   */
  private Seed seedStandaloneRoot(
      boolean humanInput,
      String argumentsJson,
      boolean freeze,
      boolean yoloEnabled,
      boolean requestToolWork) {
    return store.transaction(
        tx -> {
          Instant now = nextTime();
          UUID sessionId = tx.nextId();
          tx.insertSession(new Session(sessionId, "session-" + sessionId, now));
          UUID rootEntryId = tx.nextId();
          tx.insertEntry(new Entry(rootEntryId, sessionId, null, new RootPayload(SETTINGS), now));
          UUID threadId = tx.nextId();
          ThreadState thread =
              new ThreadState(
                  threadId,
                  sessionId,
                  null,
                  rootEntryId,
                  HASH,
                  "standalone",
                  ThreadYoloPolicy.root(yoloEnabled),
                  ThreadExecutionControl.RUNNABLE,
                  0L,
                  1L,
                  0L,
                  now,
                  now);
          tx.insertThread(thread);
          UUID invocationId =
              attachWaiting(tx, thread, humanInput, argumentsJson, freeze, requestToolWork, now);
          return new Seed(threadId, sessionId, invocationId);
        });
  }

  private UUID attachWaiting(
      HarnessStore.Transaction tx,
      ThreadState thread,
      boolean humanInput,
      String argumentsJson,
      boolean freeze) {
    return attachWaiting(tx, thread, humanInput, argumentsJson, freeze, false, nextTime());
  }

  private UUID attachWaiting(
      HarnessStore.Transaction tx,
      ThreadState thread,
      boolean humanInput,
      String argumentsJson,
      boolean freeze,
      boolean requestToolWork,
      Instant now) {
    UUID threadId = thread.id();
    UUID sessionId = thread.sessionId();
    UUID turnStartEntryId = tx.nextId();
    tx.insertEntry(
        new Entry(
            turnStartEntryId,
            sessionId,
            thread.headEntryId(),
            new TurnStartPayload(TurnStartReason.INPUT, SETTINGS, threadId, 100_000, 16_384, null),
            now));
    ThreadState afterTurnStart = thread.advanceHead(turnStartEntryId, now);
    tx.updateThread(afterTurnStart);

    UUID userEntryId = tx.nextId();
    tx.insertEntry(new Entry(userEntryId, sessionId, turnStartEntryId, userMessage(), now));

    ToolBinding binding = humanInput ? askUserBinding() : bashBinding();
    String toolName = binding.descriptor().name();
    String callId = "call-" + toolName;
    ModelRequestSpec requestSpec = requestSpec(binding);
    ProviderResponse response = toolCallResponse(callId, toolName, argumentsJson);
    UUID assistantEntryId = tx.nextId();
    tx.insertEntry(
        new Entry(
            assistantEntryId,
            sessionId,
            userEntryId,
            new HistoryPayloadMapper().assistantPayload(response, requestSpec.toolBindings()),
            now));

    UUID modelId = tx.nextId();
    ModelInvocation model =
        new ModelInvocation(
            modelId,
            threadId,
            turnStartEntryId,
            turnStartEntryId,
            requestSpec,
            ModelInvocationStatus.READY,
            0,
            null,
            null,
            null,
            null,
            List.of(),
            now,
            now);
    tx.insertModelInvocation(model);
    tx.updateModelInvocation(model.beginDispatch(now));
    tx.updateModelInvocation(model.beginDispatch(now).markRunning(now));
    tx.updateModelInvocation(model.beginDispatch(now).markRunning(now).succeed(response, now));
    tx.updateModelInvocation(
        model
            .beginDispatch(now)
            .markRunning(now)
            .succeed(response, now)
            .attachResultEntry(assistantEntryId, now));

    UUID toolId = tx.nextId();
    ToolInvocation ready =
        new ToolInvocation(
            toolId,
            modelId,
            assistantEntryId,
            0,
            new ToolCall(callId, toolName, argumentsJson),
            binding,
            ToolInvocationStatus.READY,
            0,
            null,
            null,
            null,
            now,
            now);
    tx.insertToolInvocations(List.of(ready));
    if (freeze) {
      ToolInvocation waiting =
          humanInput
              ? ready.requestInput(now)
              : ready.requestApproval("tool approval requested", now);
      tx.updateToolInvocations(List.of(waiting));
    }
    tx.updateThread(afterTurnStart.advanceHead(assistantEntryId, now));
    if (requestToolWork) {
      tx.requestWork(new WorkTarget(WorkTargetType.TOOL, toolId), now);
    }
    return toolId;
  }

  private static MessagePayload userMessage() {
    return new MessagePayload(
        new AgentMessage(
            AgentMessageRole.USER, List.of(new TextMessageContent("resolve the nested task"))),
        null,
        null);
  }

  private static ToolBinding askUserBinding() {
    InputSchema schema =
        new InputSchema(
            "ask user questionnaire",
            Map.of(
                "questions",
                new ArraySchema(
                    "questions",
                    new ObjectSchema(
                        "question",
                        Map.of("question", new StringSchema("question")),
                        Set.of("question"),
                        true))),
            Set.of("questions"),
            false);
    return new ToolBinding(
        new AgentToolDefinition(
            new ToolDescriptor(
                "ask_user",
                "ask the user a questionnaire",
                "ask_user",
                schema,
                ToolSideEffect.READ_ONLY,
                Duration.ofSeconds(30)),
            ToolVisibility.SELECTABLE),
        new ContributorBinding("builtin", "ask-user", List.of()),
        EnvironmentSupport.NONE,
        null,
        null);
  }

  private static ToolBinding bashBinding() {
    return new ToolBinding(
        new AgentToolDefinition(
            new ToolDescriptor(
                "bash",
                "run a shell command",
                "bash",
                new InputSchema("arguments", Map.of(), Set.of(), false),
                ToolSideEffect.READ_ONLY,
                Duration.ofSeconds(30)),
            ToolVisibility.SELECTABLE),
        new ContributorBinding("core", "bash", List.of()),
        EnvironmentSupport.NONE,
        null,
        null);
  }

  private static ModelRequestSpec requestSpec(ToolBinding binding) {
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        new UUID(0L, 1L),
        modelDescriptor(),
        new ModelVariant("v1"),
        1024,
        "Test system instruction.",
        List.of(binding),
        List.of(),
        ProviderCacheControl.none());
  }

  private static ProviderResponse toolCallResponse(
      String callId, String toolName, String argumentsJson) {
    return new ProviderResponse(
        "",
        "",
        List.of(new ProviderToolCall(callId, toolName, argumentsJson)),
        GenerationStopReason.COMPLETE,
        new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L),
        null,
        null,
        null);
  }

  private static ModelDescriptor modelDescriptor() {
    return new ModelDescriptor(
        "provider", "model", "model", Set.of(ModelInputModality.TEXT), true, true);
  }

  // ------------------------------------------------------------------ reads / helpers

  /** 分页拉取某个执行根的全部可见 waiting 行，直到源耗尽；游标按服务端返回原样回传。 */
  private List<InteractionDTO> drain(UUID rootThreadId, int limit) {
    List<InteractionDTO> all = new ArrayList<>();
    String cursor = null;
    for (int guard = 0; guard < 20; guard++) {
      InteractionPageDTO page = queryService.listInteractions(rootThreadId, cursor, limit);
      all.addAll(page.getItems());
      if (page.getNextCursor() == null) {
        return all;
      }
      cursor = page.getNextCursor();
    }
    throw new AssertionError("interaction pagination did not terminate");
  }

  private ThreadState threadState(UUID threadId) {
    return store.transaction(tx -> tx.findThread(threadId).orElseThrow());
  }

  private ToolInvocation toolInvocation(UUID invocationId) {
    return store.transaction(tx -> tx.findToolInvocation(invocationId).orElseThrow());
  }

  private UUID headEntryId(UUID threadId) {
    return threadState(threadId).headEntryId();
  }

  /** C 的 join 直接派发者；缺失视为订阅关系破损。 */
  private UUID joinParentOf(UUID childThreadId) {
    return store.transaction(
        tx ->
            tx.loadIncompleteJoins(childThreadId).stream()
                .filter(join -> join.parentThreadId() != null)
                .map(join -> join.parentThreadId())
                .findFirst()
                .orElseThrow());
  }

  private boolean hasWork(WorkTargetType type, UUID targetId) {
    return store.transaction(tx -> tx.findWork(new WorkTarget(type, targetId)).isPresent());
  }

  /** 单调、毫秒精度且不回退的时间源：满足 Store 的毫秒与 notBefore 约束。 */
  private synchronized Instant nextTime() {
    Instant candidate = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    if (previousTime != null && !candidate.isAfter(previousTime)) {
      candidate = previousTime.plusMillis(1);
    }
    previousTime = candidate;
    return candidate;
  }

  private HarnessWorkDispatcher startTestDispatcher() {
    Duration lease = Duration.ofSeconds(30);
    HarnessWorkDispatcherConfig config =
        new HarnessWorkDispatcherConfig(
            lease, lease, lease, Duration.ofMillis(20), Duration.ofMillis(20), 64);
    ExecutorService drainExecutor =
        Executors.newSingleThreadExecutor(daemonThreads("interaction-test-drain-"));
    ExecutorService workerExecutor =
        new ThreadPoolExecutor(
            4,
            4,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(64),
            daemonThreads("interaction-test-worker-"),
            new ThreadPoolExecutor.AbortPolicy());
    ScheduledExecutorService pollScheduler =
        Executors.newSingleThreadScheduledExecutor(daemonThreads("interaction-test-poll-"));
    testExecutors.add(drainExecutor);
    testExecutors.add(workerExecutor);
    testExecutors.add(pollScheduler);
    HarnessWorkDispatcher dispatcher =
        new HarnessWorkDispatcher(
            UUID.randomUUID(),
            store,
            config,
            clock,
            drainExecutor,
            workerExecutor,
            pollScheduler,
            threadProcessor,
            modelProcessor,
            toolProcessor);
    testDispatchers.add(dispatcher);
    dispatcher.start();
    return dispatcher;
  }

  private static ThreadFactory daemonThreads(String prefix) {
    AtomicInteger counter = new AtomicInteger();
    return runnable -> {
      Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  private static void awaitTrue(BooleanSupplier condition, String message) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while waiting for: " + message, interrupted);
      }
    }
    throw new AssertionError("timed out waiting for: " + message);
  }

  private static final String QUESTIONNAIRE = loadQuestionnaire();

  private static String loadQuestionnaire() {
    ClassPathResource resource = new ClassPathResource("interaction/ask-user-questionnaire.json");
    try (InputStream input = resource.getInputStream()) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
    } catch (IOException error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  private record Tree(UUID root, UUID rootSessionId, UUID a, UUID b, UUID c, UUID cSessionId) {}

  private record Seed(UUID threadId, UUID sessionId, UUID invocationId) {}

  private record IssueBinding(UUID projectId, UUID issueId) {}
}
