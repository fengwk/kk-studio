package fun.fengwk.kkstudio.platform.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.history.EntryCurrentState;
import fun.fengwk.kkstudio.harness.api.interaction.PendingEnvironmentWait;
import fun.fengwk.kkstudio.harness.api.interaction.PendingEnvironmentWaitPage;
import fun.fengwk.kkstudio.harness.api.interaction.PendingInteraction;
import fun.fengwk.kkstudio.harness.api.interaction.PendingInteractionPage;
import fun.fengwk.kkstudio.harness.api.thread.ThreadKind;
import fun.fengwk.kkstudio.harness.api.thread.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.environment.EnvironmentService;
import fun.fengwk.kkstudio.platform.harness.dto.InteractionDTO;
import fun.fengwk.kkstudio.platform.harness.dto.InteractionOwnerDTO;
import fun.fengwk.kkstudio.platform.harness.dto.InteractionPageDTO;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwnerDisplay;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwnership;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 待处理交互统一查询用例 {@link InteractionQueryService} 的单元测试。 */
class InteractionQueryServiceTest {

  private static final UUID ZERO_UUID = new UUID(0L, 0L);

  private static UUID id(long value) {
    return new UUID(0L, value);
  }

  private static InteractionDTO.Input input(InteractionDTO item) {
    return assertInstanceOf(InteractionDTO.Input.class, item);
  }

  private static InteractionDTO.Approval approval(InteractionDTO item) {
    return assertInstanceOf(InteractionDTO.Approval.class, item);
  }

  private static InteractionDTO.EnvironmentWait environmentWait(InteractionDTO item) {
    return assertInstanceOf(InteractionDTO.EnvironmentWait.class, item);
  }

  private SessionOwnership ownership;
  private EnvironmentService environmentService;
  private ObjectProvider<HarnessRuntime> runtimes;
  private HarnessRuntime runtime;
  private InteractionQueryService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    ownership = mock(SessionOwnership.class);
    environmentService = mock(EnvironmentService.class);
    runtimes = mock(ObjectProvider.class);
    runtime = mock(HarnessRuntime.class);

    when(runtimes.getIfAvailable()).thenReturn(runtime);
    // 默认没有环境等待分组；需要环境等待的用例再显式覆盖本 stub。
    when(runtime.listEnvironmentWaitCandidates(any(), any(), anyInt()))
        .thenReturn(new PendingEnvironmentWaitPage(List.of(), false));
    service = new InteractionQueryService(List.of(ownership), environmentService, runtimes);
  }

  private void stubOwner(UUID root, UUID session, String kind, UUID ownerId, String title) {
    when(ownership.findOwner(root, session))
        .thenReturn(Optional.of(new SessionOwnerDisplay(kind, ownerId.toString(), title, null)));
  }

  /** 两个产品分别命中不同的根时都应可见，不能误判为冲突。 */
  @Test
  void differentProductsOnDifferentRootsAreVisible() {
    SessionOwnership other = mock(SessionOwnership.class);
    service = new InteractionQueryService(List.of(ownership, other), environmentService, runtimes);
    UUID root1 = id(101);
    UUID session1 = id(102);
    UUID root2 = id(201);
    UUID session2 = id(202);
    stubRoot(root1);
    stubRoot(root2);
    stubOwner(root1, session1, "CHAT", id(103), "chat");
    when(other.findOwner(root2, session2))
        .thenReturn(
            Optional.of(new SessionOwnerDisplay("ISSUE_AGENT", "issue:agent", "issue", null)));
    Instant time = Instant.parse("2026-03-01T10:00:00Z");
    PendingInteraction first = waitingInput(id(104), root1, session1, time);
    PendingInteraction second = waitingInput(id(204), root2, session2, time.plusSeconds(1));
    when(runtime.listPendingInteractions(Instant.EPOCH, ZERO_UUID, 10))
        .thenReturn(new PendingInteractionPage(List.of(first, second), false));
    stubCountPage(List.of(first, second), false);

    InteractionPageDTO page = service.listInteractions(null, null, 10);
    assertEquals(2, page.getTotal());
    assertEquals("CHAT", page.getItems().get(0).owner().getKind());
    assertEquals("issue:agent", page.getItems().get(1).owner().getId());
    verify(other).findOwner(root1, session1);
    verify(ownership).findOwner(root2, session2);
  }

  /** 显式过滤根/直接来源根必须解析为执行根；同时提供该根的快照（展示名）。后代来源会用 {@link #stubRootSession} 覆盖同一根的快照。 */
  private void stubRoot(UUID threadId) {
    when(runtime.findRootThreadId(threadId)).thenReturn(Optional.of(threadId));
    stubRootSnapshot(threadId, ZERO_UUID, "thread-" + threadId);
  }

  private void stubDescendant(UUID threadId, UUID rootThreadId) {
    when(runtime.findRootThreadId(threadId)).thenReturn(Optional.of(rootThreadId));
  }

  /** 根快照同时提供 Session（归属解析）与名称（展示）；服务端每个根只回读一次。 */
  private void stubRootSession(UUID rootThreadId, UUID sessionId) {
    stubRootSnapshot(rootThreadId, sessionId, "thread-" + rootThreadId);
  }

  private void stubRootSnapshot(UUID rootThreadId, UUID sessionId, String name) {
    ThreadState thread = mock(ThreadState.class);
    when(thread.sessionId()).thenReturn(sessionId);
    when(thread.name()).thenReturn(name);
    ThreadSnapshot snapshot = mock(ThreadSnapshot.class);
    when(snapshot.thread()).thenReturn(thread);
    when(runtime.getThreadSnapshot(rootThreadId)).thenReturn(snapshot);
  }

  /** 直接以根为来源的人工等待：根 Session 直接取自行，但展示名称仍需根快照。 */
  private void stubRootName(UUID rootThreadId, String name) {
    stubRootSnapshot(rootThreadId, ZERO_UUID, name);
  }

  /** 计数扫描固定从首屏游标按 {@link InteractionQueryService#COUNT_PAGE_SIZE} 推进，因此每个用例都要显式给出该源。 */
  private void stubCountPage(List<PendingInteraction> rows, boolean hasMore) {
    stubCountPage(Instant.EPOCH, ZERO_UUID, rows, hasMore);
  }

  private void stubCountPage(
      Instant afterCreatedAt, UUID afterId, List<PendingInteraction> rows, boolean hasMore) {
    when(runtime.listPendingInteractions(
            afterCreatedAt, afterId, InteractionQueryService.COUNT_PAGE_SIZE))
        .thenReturn(new PendingInteractionPage(rows, hasMore));
  }

  private static PendingInteraction waitingInput(
      UUID invocationId, UUID threadId, UUID sessionId, Instant createdAt) {
    return new PendingInteraction(
        invocationId,
        threadId,
        sessionId,
        ToolInvocationStatus.WAITING_INPUT,
        "call",
        "ask_user",
        "{}",
        null,
        createdAt);
  }

  /**
   * 测试意图：验证服务端沿来源 Thread 的祖先链解析产品归属——根为 Chat Session 或 Issue+Agent 绑定时可见，两者都解析不到的内部行被过滤；
   * 同时验证后代来源用根的 Session 解析 owner 且 DTO 同时携带原始来源坐标与真实 rootThreadId。
   */
  @Test
  void ownerResolutionResolvesChatAndIssueAgentAndFiltersInvisibleRows() {
    UUID sessionId1 = id(10);
    UUID threadId1 = id(11);
    UUID invocationId1 = id(101);
    Instant t1 = Instant.parse("2026-03-01T10:00:00Z");
    PendingInteraction row1 = waitingInput(invocationId1, threadId1, sessionId1, t1);
    stubRoot(threadId1);

    UUID sessionId2 = id(20);
    UUID threadId2 = id(21);
    UUID invocationId2 = id(102);
    Instant t2 = Instant.parse("2026-03-01T10:01:00Z");
    PendingInteraction row2 =
        new PendingInteraction(
            invocationId2,
            threadId2,
            sessionId2,
            ToolInvocationStatus.WAITING_APPROVAL,
            "call-2",
            "deploy_service",
            "{\"env\":\"prod\"}",
            "{\"allowed\":false}",
            t2);
    stubRoot(threadId2);

    UUID sessionId3 = id(30);
    UUID threadId3 = id(31);
    UUID invocationId3 = id(103);
    Instant t3 = Instant.parse("2026-03-01T10:02:00Z");
    PendingInteraction row3 = waitingInput(invocationId3, threadId3, sessionId3, t3);
    stubRoot(threadId3);

    when(ownership.findOwner(threadId1, sessionId1))
        .thenReturn(
            Optional.of(
                new SessionOwnerDisplay(
                    "CHAT", id(1001).toString(), "deploy chat", "/chats/1001")));
    stubOwner(threadId2, sessionId2, "ISSUE_AGENT", id(2001), "fix the pipeline");

    when(runtime.listPendingInteractions(Instant.EPOCH, ZERO_UUID, 10))
        .thenReturn(new PendingInteractionPage(List.of(row1, row2, row3), false));
    stubCountPage(List.of(row1, row2, row3), false);

    InteractionPageDTO page = service.listInteractions(null, null, 10);

    assertEquals(2, page.getItems().size());
    assertNull(page.getNextCursor());
    assertEquals(2, page.getTotal(), "total 统计的是可见待处理行，而不是原始源行数");

    InteractionDTO.Input dto1 = input(page.getItems().get(0));
    assertEquals(invocationId1.toString(), dto1.toolInvocationId());
    assertEquals(threadId1.toString(), dto1.threadId());
    assertEquals(sessionId1.toString(), dto1.sessionId());
    assertEquals(threadId1.toString(), dto1.rootThreadId());
    assertEquals("call", dto1.toolCallId());
    assertEquals("ask_user", dto1.toolName());
    assertEquals("{}", dto1.argumentsJson());
    assertEquals(t1, dto1.createdAt());
    assertEquals("CHAT", dto1.owner().getKind());
    assertEquals(id(1001).toString(), dto1.owner().getId());
    assertEquals("deploy chat", dto1.owner().getTitle());
    assertEquals("/chats/1001", dto1.owner().getLink());

    InteractionDTO.Approval dto2 = approval(page.getItems().get(1));
    assertEquals(invocationId2.toString(), dto2.toolInvocationId());
    assertEquals(threadId2.toString(), dto2.threadId());
    assertEquals(sessionId2.toString(), dto2.sessionId());
    assertEquals(threadId2.toString(), dto2.rootThreadId());
    assertEquals("{\"env\":\"prod\"}", dto2.argumentsJson());
    assertEquals(JsonValues.readTree("{\"allowed\":false}"), dto2.approval());
    assertEquals(t2, dto2.createdAt());
    assertEquals("ISSUE_AGENT", dto2.owner().getKind());
    assertEquals(id(2001).toString(), dto2.owner().getId());
    assertEquals("fix the pipeline", dto2.owner().getTitle());
    assertNull(dto2.owner().getLink());

    // 列表和计数共用请求级缓存。
    verify(ownership, times(1)).findOwner(threadId1, sessionId1);
    verify(ownership, times(1)).findOwner(threadId2, sessionId2);
  }

  /** 测试意图：只统计同一 Assistant、同一 Environment、同一归一化文件路径上仍未结束的后继审批调用。 */
  @Test
  void approvalProjectsUnfinishedSameFileSuccessorsOnly() {
    UUID sessionId = id(60);
    UUID threadId = id(61);
    Instant createdAt = Instant.parse("2026-03-01T10:00:00Z");
    PendingInteraction row =
        new PendingInteraction(
            id(64),
            threadId,
            sessionId,
            ToolInvocationStatus.WAITING_APPROVAL,
            "call-1",
            "edit",
            "{\"path\":\"/a/b\"}",
            "{}",
            createdAt);
    stubRoot(threadId);
    stubOwner(threadId, sessionId, "CHAT", id(65), "chat");

    when(runtime.countQueuedSameFileMutationsAfter(id(64))).thenReturn(1);
    when(runtime.listPendingInteractions(Instant.EPOCH, ZERO_UUID, 10))
        .thenReturn(new PendingInteractionPage(List.of(row), false));
    stubCountPage(List.of(row), false);

    InteractionDTO.Approval approval =
        approval(service.listInteractions(null, null, 10).getItems().getFirst());

    assertEquals(1, approval.queuedSameFileCount());
    verify(runtime).countQueuedSameFileMutationsAfter(id(64));
    verify(runtime, never()).getThreadSnapshot(threadId);
  }

  /** 非文件修改审批无需读取 Thread 快照或查询同文件排队项。 */
  @Test
  void nonFileMutationApprovalSkipsQueueCountQuery() {
    UUID sessionId = id(80);
    UUID threadId = id(81);
    PendingInteraction row =
        new PendingInteraction(
            id(82),
            threadId,
            sessionId,
            ToolInvocationStatus.WAITING_APPROVAL,
            "call",
            "deploy_service",
            "{}",
            "{}",
            Instant.parse("2026-03-01T10:00:00Z"));
    stubRoot(threadId);
    stubOwner(threadId, sessionId, "CHAT", id(83), "chat");
    when(runtime.listPendingInteractions(Instant.EPOCH, ZERO_UUID, 10))
        .thenReturn(new PendingInteractionPage(List.of(row), false));
    stubCountPage(List.of(row), false);

    InteractionDTO.Approval dto =
        approval(service.listInteractions(null, null, 10).getItems().getFirst());

    assertEquals(0, dto.queuedSameFileCount());
    verify(runtime, never()).countQueuedSameFileMutationsAfter(any());
    verify(runtime, never()).getThreadSnapshot(threadId);
  }

  /**
   * 测试意图：验证没有直接产品绑定的后代来源仍可见——服务端回读根快照取得根 Session 并解析出根产品归属，DTO 保留原始子 threadId/sessionId，另以
   * rootThreadId 指向真实根。
   */
  @Test
  void descendantSourceIsVisibleThroughRootAttribution() {
    UUID rootThreadId = id(500);
    UUID rootSessionId = id(600);
    UUID childThreadId = id(501);
    UUID childSessionId = id(601);
    Instant t1 = Instant.parse("2026-03-01T10:00:00Z");
    PendingInteraction childRow = waitingInput(id(900), childThreadId, childSessionId, t1);
    stubDescendant(childThreadId, rootThreadId);
    stubRootSession(rootThreadId, rootSessionId);
    stubOwner(rootThreadId, rootSessionId, "CHAT", id(700), null);
    when(runtime.listPendingInteractions(Instant.EPOCH, ZERO_UUID, 10))
        .thenReturn(new PendingInteractionPage(List.of(childRow), false));
    stubCountPage(List.of(childRow), false);

    InteractionPageDTO page = service.listInteractions(null, null, 10);

    assertEquals(1, page.getItems().size());
    assertEquals(1, page.getTotal());
    InteractionDTO.Input dto = input(page.getItems().get(0));
    assertEquals(childThreadId.toString(), dto.threadId());
    assertEquals(childSessionId.toString(), dto.sessionId());
    assertEquals(rootThreadId.toString(), dto.rootThreadId());
    assertEquals("CHAT", dto.owner().getKind());
    assertEquals(id(700).toString(), dto.owner().getId());
  }

  /**
   * 测试意图：验证按 rootThreadId 过滤时，服务端跨页继续扫描直到匹配页满，既不漏掉后代来源也不因命中其他根的行而提前截断； 同时验证后代行用根 Session
   * 归属、其他根的行被排除。
   */
  @Test
  void rootFilterMatchesDescendantsAcrossPagesWithoutMissingRows() {
    UUID rootThreadId = id(500);
    UUID rootSessionId = id(600);
    UUID rootRowThreadId = id(500);
    UUID rootRowSessionId = id(600);
    UUID childThreadId = id(501);
    UUID childSessionId = id(601);
    UUID otherRootThreadId = id(510);
    UUID otherRootSessionId = id(610);

    Instant t1 = Instant.parse("2026-03-01T10:00:00Z");
    Instant t2 = Instant.parse("2026-03-01T10:01:00Z");
    Instant t3 = Instant.parse("2026-03-01T10:02:00Z");
    PendingInteraction rootRow = waitingInput(id(901), rootRowThreadId, rootRowSessionId, t1);
    PendingInteraction otherRow = waitingInput(id(902), otherRootThreadId, otherRootSessionId, t2);
    PendingInteraction childRow = waitingInput(id(903), childThreadId, childSessionId, t3);

    stubRoot(rootThreadId);
    stubRoot(otherRootThreadId);
    stubDescendant(childThreadId, rootThreadId);
    stubRootSession(rootThreadId, rootSessionId);
    stubOwner(rootThreadId, rootSessionId, "CHAT", id(700), null);
    stubOwner(otherRootThreadId, otherRootSessionId, "CHAT", id(710), null);

    // 第 1 页：根自身 + 其他根（被过滤），hasMore=true；第 2 页：后代来源（可见）。
    when(runtime.listPendingInteractions(Instant.EPOCH, ZERO_UUID, 2))
        .thenReturn(new PendingInteractionPage(List.of(rootRow, otherRow), true));
    when(runtime.listPendingInteractions(t2, id(902), 1))
        .thenReturn(new PendingInteractionPage(List.of(childRow), false));

    // 计数同样按根过滤跨页扫描完整源：其他根的行不进入 total。
    stubCountPage(List.of(rootRow, otherRow), true);
    stubCountPage(t2, id(902), List.of(childRow), false);

    InteractionPageDTO page = service.listInteractions(rootThreadId, null, 2);

    assertEquals(2, page.getItems().size());
    assertEquals(id(901).toString(), input(page.getItems().get(0)).toolInvocationId());
    assertEquals(rootThreadId.toString(), page.getItems().get(0).rootThreadId());
    assertEquals(id(903).toString(), input(page.getItems().get(1)).toolInvocationId());
    assertEquals(childThreadId.toString(), input(page.getItems().get(1)).threadId());
    assertEquals(rootThreadId.toString(), page.getItems().get(1).rootThreadId());
    assertNull(page.getNextCursor());
    assertEquals(2, page.getTotal(), "total 必须与列表使用同一根过滤");

    verify(runtime).listPendingInteractions(Instant.EPOCH, ZERO_UUID, 2);
    verify(runtime).listPendingInteractions(t2, id(902), 1);
  }

  /** 测试意图：显式根过滤值必须是已存在的执行根，不存在返回 not-found、子 Thread 返回非法参数且都不进入分页扫描。 */
  @Test
  void rootFilterRejectsNonexistentAndNonRootThreads() {
    when(runtime.findRootThreadId(id(999))).thenReturn(Optional.empty());
    assertEquals(
        DomainErrorCode.NOT_FOUND,
        assertThrows(DomainException.class, () -> service.listInteractions(id(999), null, 10))
            .code());

    UUID child = id(501);
    UUID root = id(500);
    when(runtime.findRootThreadId(child)).thenReturn(Optional.of(root));
    assertEquals(
        DomainErrorCode.VALIDATION,
        assertThrows(DomainException.class, () -> service.listInteractions(child, null, 10))
            .code());

    verify(runtime, never()).listPendingInteractions(any(), any(), anyInt());
  }

  /**
   * 测试意图：验证当单页存在被过滤的不可见行时，服务持续向后扫描（每轮以剩余额度向 runtime 请求），直至凑满 limit 条可见项或底层耗尽，绝不因中间存在不可见行而提前截断返回；
   * 并严格验证第二次调用 runtime 时以第一页最后一条已消费的原始行作为游标。
   */
  @Test
  void scanUntilLimitAccumulatesAcrossPagesWithoutPrematureTruncation() {
    Instant t1 = Instant.parse("2026-03-01T10:00:00Z");
    Instant t2 = Instant.parse("2026-03-01T10:01:00Z");
    Instant t3 = Instant.parse("2026-03-01T10:02:00Z");
    Instant t4 = Instant.parse("2026-03-01T10:03:00Z");
    Instant t5 = Instant.parse("2026-03-01T10:04:00Z");

    PendingInteraction row1 = waitingInput(id(10), id(2), id(1), t1);
    PendingInteraction row2 = waitingInput(id(20), id(4), id(3), t2);
    PendingInteraction row3 = waitingInput(id(30), id(6), id(5), t3);
    PendingInteraction row4 = waitingInput(id(40), id(8), id(7), t4);
    PendingInteraction row5 = waitingInput(id(50), id(10), id(9), t5);

    // row1, row2 不可见
    stubRoot(id(2));
    stubRoot(id(4));

    // row3 可见 (Chat)
    stubRoot(id(6));
    stubOwner(id(6), id(5), "CHAT", id(500), null);

    // row4 可见 (IssueAgent)
    stubRoot(id(8));
    stubOwner(id(8), id(7), "ISSUE_AGENT", id(800), null);

    // row5 可见 (Chat)
    stubRoot(id(10));
    stubOwner(id(10), id(9), "CHAT", id(900), null);

    // 第 1 页返回 3 条（2 条不可见 + 1 条可见），hasMore 为 true
    when(runtime.listPendingInteractions(Instant.EPOCH, ZERO_UUID, 3))
        .thenReturn(new PendingInteractionPage(List.of(row1, row2, row3), true));

    // 第 2 页以第 1 页最后一行 (t3, id(30)) 作为游标，额度为 3 - 1 = 2；返回 2 条可见，hasMore 为 false
    when(runtime.listPendingInteractions(t3, id(30), 2))
        .thenReturn(new PendingInteractionPage(List.of(row4, row5), false));

    stubCountPage(List.of(row1, row2, row3, row4, row5), false);

    InteractionPageDTO page = service.listInteractions(null, null, 3);

    assertEquals(3, page.getItems().size());
    assertEquals(id(30).toString(), input(page.getItems().get(0)).toolInvocationId());
    assertEquals(id(40).toString(), input(page.getItems().get(1)).toolInvocationId());
    assertEquals(id(50).toString(), input(page.getItems().get(2)).toolInvocationId());
    assertNull(page.getNextCursor());
    assertEquals(3, page.getTotal(), "total 只统计可见行（row3/row4/row5）");

    verify(runtime).listPendingInteractions(Instant.EPOCH, ZERO_UUID, 3);
    verify(runtime).listPendingInteractions(t3, id(30), 2);
  }

  /**
   * 测试意图：验证游标的前向推进保证： 1. 内部扫描时，整页不可见且 hasMore=true 时，服务以前一页最后一行继续向 runtime 请求下一页； 2. 凑满 limit 且
   * hasMore=true 时，返回的 nextCursor 正确编码最后一条消费的原始行； 3. 客户端再次携带该 nextCursor 发起请求时，服务能准确反解
   * afterCreatedAt 与 afterId 传给 runtime。
   */
  @Test
  void cursorForwardProgressAcrossRequestsAndInternalPages() {
    Instant t1 = Instant.parse("2026-03-01T10:00:00Z");
    Instant t2 = Instant.parse("2026-03-01T10:01:00Z");
    Instant t3 = Instant.parse("2026-03-01T10:02:00Z");

    PendingInteraction row1 = waitingInput(id(10), id(2), id(1), t1);
    PendingInteraction row2 = waitingInput(id(20), id(4), id(3), t2);
    PendingInteraction row3 = waitingInput(id(30), id(6), id(5), t3);

    // row1 可见 (Chat)
    stubRoot(id(2));
    stubOwner(id(2), id(1), "CHAT", id(100), null);

    // row2 不可见
    stubRoot(id(4));

    // row3 可见 (Chat)
    stubRoot(id(6));
    stubOwner(id(6), id(5), "CHAT", id(300), null);

    // 第一次调用：limit=1，返回 row1 且 hasMore=true
    when(runtime.listPendingInteractions(Instant.EPOCH, ZERO_UUID, 1))
        .thenReturn(new PendingInteractionPage(List.of(row1), true));

    // 计数页始终从首屏游标扫描完整源，与当前请求的 limit/游标无关。
    stubCountPage(List.of(row1, row2, row3), false);

    InteractionPageDTO firstPage = service.listInteractions(null, null, 1);
    assertEquals(1, firstPage.getItems().size());
    String expectedCursor1 = t1.toEpochMilli() + ":" + id(10);
    assertEquals(expectedCursor1, firstPage.getNextCursor());
    assertEquals(2, firstPage.getTotal(), "row1 与 row3 可见，row2 不可见");

    // 第二次调用：携带 expectedCursor1，limit=1。
    // 第 1 轮 runtime 返回 row2（整页不可见，hasMore=true），验证服务内部以 row2 (t2, id(20)) 自动继续推进扫描下一页
    when(runtime.listPendingInteractions(t1, id(10), 1))
        .thenReturn(new PendingInteractionPage(List.of(row2), true));
    when(runtime.listPendingInteractions(t2, id(20), 1))
        .thenReturn(new PendingInteractionPage(List.of(row3), false));

    InteractionPageDTO secondPage = service.listInteractions(null, firstPage.getNextCursor(), 1);
    assertEquals(1, secondPage.getItems().size());
    assertEquals(id(30).toString(), input(secondPage.getItems().get(0)).toolInvocationId());
    assertNull(secondPage.getNextCursor());
    assertEquals(2, secondPage.getTotal(), "total 与分页位置无关，翻页后仍然是全局可见总数");

    verify(runtime).listPendingInteractions(Instant.EPOCH, ZERO_UUID, 1);
    verify(runtime).listPendingInteractions(t1, id(10), 1);
    verify(runtime).listPendingInteractions(t2, id(20), 1);
  }

  /** 测试意图：验证底层源耗尽（hasMore 为 false 或空列表）时，返回的 nextCursor 必须为 null，且 total 为 0/可见行数。 */
  @Test
  void exhaustedSourceYieldsNullNextCursor() {
    // 场景 A：首屏即为空列表
    when(runtime.listPendingInteractions(Instant.EPOCH, ZERO_UUID, 10))
        .thenReturn(new PendingInteractionPage(List.of(), false));
    stubCountPage(List.of(), false);

    InteractionPageDTO pageEmpty = service.listInteractions(null, null, 10);
    assertEquals(0, pageEmpty.getItems().size());
    assertNull(pageEmpty.getNextCursor());
    assertEquals(0, pageEmpty.getTotal());

    // 场景 B：首屏有数据但 hasMore 为 false
    UUID sessionId = id(10);
    UUID threadId = id(11);
    PendingInteraction row =
        waitingInput(id(101), threadId, sessionId, Instant.parse("2026-03-01T10:00:00Z"));
    stubRoot(threadId);
    stubOwner(threadId, sessionId, "CHAT", id(1001), null);
    when(runtime.listPendingInteractions(Instant.EPOCH, ZERO_UUID, 5))
        .thenReturn(new PendingInteractionPage(List.of(row), false));
    stubCountPage(List.of(row), false);

    InteractionPageDTO pageWithItems = service.listInteractions(null, null, 5);
    assertEquals(1, pageWithItems.getItems().size());
    assertNull(pageWithItems.getNextCursor());
    assertEquals(1, pageWithItems.getTotal());
  }

  /** 测试意图：验证 total 是完整源的真实可见数，而不是本页长度——limit=1 时仍要跨页统计出 3 条可见待处理。 */
  @Test
  void totalCoversWholeSourceInsteadOfReturnedPage() {
    Instant t1 = Instant.parse("2026-03-01T10:00:00Z");
    Instant t2 = Instant.parse("2026-03-01T10:01:00Z");
    Instant t3 = Instant.parse("2026-03-01T10:02:00Z");
    Instant t4 = Instant.parse("2026-03-01T10:03:00Z");

    PendingInteraction row1 = waitingInput(id(10), id(2), id(1), t1);
    PendingInteraction row2 = waitingInput(id(20), id(4), id(3), t2);
    PendingInteraction row3 = waitingInput(id(30), id(6), id(5), t3);
    PendingInteraction row4 = waitingInput(id(40), id(8), id(7), t4);

    // row1/row3/row4 可见；row2 无产品归属（内部委派）必须被统计排除。
    stubRoot(id(2));
    stubOwner(id(2), id(1), "CHAT", id(100), null);
    stubRoot(id(4));
    stubRoot(id(6));
    stubOwner(id(6), id(5), "CHAT", id(300), null);
    stubRoot(id(8));
    stubOwner(id(8), id(7), "CHAT", id(400), null);

    when(runtime.listPendingInteractions(Instant.EPOCH, ZERO_UUID, 1))
        .thenReturn(new PendingInteractionPage(List.of(row1), true));
    stubCountPage(List.of(row1, row2), true);
    stubCountPage(t2, id(20), List.of(row3, row4), false);

    InteractionPageDTO page = service.listInteractions(null, null, 1);

    assertEquals(1, page.getItems().size());
    assertEquals(id(10).toString(), input(page.getItems().get(0)).toolInvocationId());
    assertEquals(3, page.getTotal(), "total 必须统计完整源的可见行，不能等于首页长度");
  }

  /** 测试意图：验证各种非法格式游标都被拒绝为 validation，且不触碰 runtime。 */
  @Test
  void malformedCursorIsValidationError() {
    List<String> malformedCursors =
        List.of(
            "nope",
            "12:not-a-uuid",
            "x:00000000-0000-0000-0000-000000000001",
            ":00000000-0000-0000-0000-000000000001",
            "123:",
            "-1:00000000-0000-0000-0000-000000000001",
            "123:00000000000000000000000000000001",
            "123:00000000-0000-0000-0000-000000000001:extra");

    for (String cursor : malformedCursors) {
      DomainException error =
          assertThrows(DomainException.class, () -> service.listInteractions(null, cursor, 10));
      assertEquals(DomainErrorCode.VALIDATION, error.code());
    }

    verifyNoInteractions(runtime);
  }

  /** 测试意图：验证 limit 越界同样是 validation 错误，且不触碰 runtime。 */
  @Test
  void outOfRangeLimitIsValidationError() {
    for (int limit : new int[] {0, -1, InteractionQueryService.MAX_LIMIT + 1}) {
      DomainException error =
          assertThrows(DomainException.class, () -> service.listInteractions(null, null, limit));
      assertEquals(DomainErrorCode.VALIDATION, error.code());
    }
    verifyNoInteractions(runtime);
  }

  /** 测试意图：验证 HarnessRuntime 未提供时抛出 IllegalStateException。 */
  @Test
  void runtimeUnavailableThrowsIllegalStateException() {
    when(runtimes.getIfAvailable()).thenReturn(null);
    assertThrows(IllegalStateException.class, () -> service.listInteractions(null, null, 10));
  }

  /** 环境等待投影使用的环境身份；用例只需一致的字符串 id 与环境展示名。 */
  private static final String WAITING_ENVIRONMENT_ID = "33333333-3333-3333-3333-333333333333";

  private static final String SECOND_ENVIRONMENT_ID = "44444444-4444-4444-4444-444444444444";

  private static PendingEnvironmentWait environmentWait(
      UUID rootThreadId,
      String environmentId,
      Instant createdAt,
      UUID representativeInvocationId,
      int waitingCount) {
    return new PendingEnvironmentWait(
        rootThreadId,
        EnvironmentId.parse(environmentId),
        createdAt,
        representativeInvocationId,
        waitingCount);
  }

  private void stubEnvironmentPage(
      Instant afterCreatedAt,
      UUID afterId,
      int size,
      List<PendingEnvironmentWait> rows,
      boolean hasMore) {
    when(runtime.listEnvironmentWaitCandidates(afterCreatedAt, afterId, size))
        .thenReturn(new PendingEnvironmentWaitPage(rows, hasMore));
  }

  /** 计数扫描固定以 {@link InteractionQueryService#COUNT_PAGE_SIZE} 从首屏游标推进，与环境等待首屏 stub 区分。 */
  private void stubEnvironmentCountPage(List<PendingEnvironmentWait> rows) {
    stubEnvironmentPage(
        Instant.EPOCH, ZERO_UUID, InteractionQueryService.COUNT_PAGE_SIZE, rows, false);
  }

  private void stubEnvironmentName(String environmentId, String name) {
    when(environmentService.requireName(EnvironmentId.parse(environmentId))).thenReturn(name);
  }

  /** 人工后代与环境等待共享根及根 Session，并在列表和计数中只解析一次归属。 */
  @Test
  void manualDescendantAndEnvironmentShareRootOwnership() {
    UUID root = id(80);
    UUID rootSession = id(81);
    UUID child = id(82);
    UUID childSession = id(83);
    Instant time = Instant.parse("2026-03-01T10:00:00Z");
    PendingInteraction manual = waitingInput(id(84), child, childSession, time);
    PendingEnvironmentWait environment =
        environmentWait(root, WAITING_ENVIRONMENT_ID, time.plusSeconds(1), id(85), 1);
    stubDescendant(child, root);
    stubRootSession(root, rootSession);
    stubOwner(root, rootSession, "CHAT", id(86), "root owner");
    stubEnvironmentName(WAITING_ENVIRONMENT_ID, "dev");
    when(runtime.listPendingInteractions(Instant.EPOCH, ZERO_UUID, 10))
        .thenReturn(new PendingInteractionPage(List.of(manual), false));
    stubEnvironmentPage(Instant.EPOCH, ZERO_UUID, 10, List.of(environment), false);
    stubCountPage(List.of(manual), false);
    stubEnvironmentCountPage(List.of(environment));

    InteractionPageDTO page = service.listInteractions(null, null, 10);
    assertEquals(2, page.getTotal());
    assertEquals(childSession.toString(), input(page.getItems().get(0)).sessionId());
    assertEquals(root.toString(), page.getItems().get(0).rootThreadId());
    assertEquals(id(86).toString(), page.getItems().get(0).owner().getId());
    assertEquals(page.getItems().get(0).owner(), page.getItems().get(1).owner());
    verify(ownership).findOwner(root, rootSession);
  }

  /**
   * 测试意图：人工等待与环境等待按共享 {@code (createdAt, id)} 键归并——环境等待用组内最早代表参与排序；DTO 携带 {@code type}/{@code
   * environmentId}/{@code environmentName}/{@code waitingCount}，且不暴露可操作主键或状态；{@code total}
   * 同时统计两条来源的可见项，而不是只看首页长度。
   */
  @Test
  void environmentWaitMergesWithManualInteractionsBySharedKey() {
    UUID manualRootThreadId = id(11);
    UUID manualSessionId = id(12);
    Instant manualCreatedAt = Instant.parse("2026-03-01T10:01:00Z");
    PendingInteraction manual =
        waitingInput(id(101), manualRootThreadId, manualSessionId, manualCreatedAt);
    stubRoot(manualRootThreadId);
    stubOwner(manualRootThreadId, manualSessionId, "CHAT", id(13), null);

    UUID environmentRootThreadId = id(21);
    UUID environmentSessionId = id(22);
    Instant earlierCreatedAt = Instant.parse("2026-03-01T10:00:00Z");
    Instant laterCreatedAt = Instant.parse("2026-03-01T10:02:00Z");
    PendingEnvironmentWait earlier =
        environmentWait(
            environmentRootThreadId, WAITING_ENVIRONMENT_ID, earlierCreatedAt, id(901), 3);
    PendingEnvironmentWait later =
        environmentWait(environmentRootThreadId, SECOND_ENVIRONMENT_ID, laterCreatedAt, id(902), 1);
    stubRootSession(environmentRootThreadId, environmentSessionId);
    stubOwner(environmentRootThreadId, environmentSessionId, "CHAT", id(23), null);
    stubEnvironmentName(WAITING_ENVIRONMENT_ID, "dev-env");
    stubEnvironmentName(SECOND_ENVIRONMENT_ID, "prod-env");

    when(runtime.listPendingInteractions(Instant.EPOCH, ZERO_UUID, 10))
        .thenReturn(new PendingInteractionPage(List.of(manual), false));
    stubEnvironmentPage(Instant.EPOCH, ZERO_UUID, 10, List.of(earlier, later), false);
    stubCountPage(List.of(manual), false);
    stubEnvironmentCountPage(List.of(earlier, later));

    InteractionPageDTO page = service.listInteractions(null, null, 10);

    assertEquals(3, page.getItems().size());
    assertEquals(3, page.getTotal());
    assertNull(page.getNextCursor());

    InteractionDTO.EnvironmentWait environmentItem = environmentWait(page.getItems().get(0));
    assertEquals(WAITING_ENVIRONMENT_ID, environmentItem.environmentId());
    assertEquals("dev-env", environmentItem.environmentName());
    assertEquals(3, environmentItem.waitingCount());
    assertEquals(environmentRootThreadId.toString(), environmentItem.rootThreadId());
    assertEquals("CHAT", environmentItem.owner().getKind());
    assertEquals(id(23).toString(), environmentItem.owner().getId());
    assertEquals(earlierCreatedAt, environmentItem.createdAt());

    assertEquals(id(101).toString(), input(page.getItems().get(1)).toolInvocationId());

    InteractionDTO.EnvironmentWait secondEnvironmentItem = environmentWait(page.getItems().get(2));
    assertEquals(SECOND_ENVIRONMENT_ID, secondEnvironmentItem.environmentId());
    assertEquals("prod-env", secondEnvironmentItem.environmentName());
    assertEquals(1, secondEnvironmentItem.waitingCount());
  }

  /** 在线候选不能让人工等待末页出现多余的空游标页，total 同样不计在线环境。 */
  @Test
  void onlineCandidatesDoNotCreateAnEmptyCursorPage() {
    UUID root = id(11);
    UUID session = id(12);
    Instant manualTime = Instant.parse("2026-03-01T10:00:00Z");
    PendingInteraction manual = waitingInput(id(101), root, session, manualTime);
    PendingEnvironmentWait online =
        environmentWait(root, WAITING_ENVIRONMENT_ID, manualTime.plusSeconds(1), id(102), 1);
    stubRoot(root);
    stubOwner(root, session, "CHAT", id(13), "chat");
    when(environmentService.findReadyLeaseUntil(EnvironmentId.parse(WAITING_ENVIRONMENT_ID)))
        .thenReturn(Optional.of(manualTime.plusSeconds(100)));
    when(runtime.listPendingInteractions(Instant.EPOCH, ZERO_UUID, 1))
        .thenReturn(new PendingInteractionPage(List.of(manual), false));
    stubEnvironmentPage(Instant.EPOCH, ZERO_UUID, 1, List.of(online), false);
    stubCountPage(List.of(manual), false);
    stubEnvironmentCountPage(List.of(online));

    InteractionPageDTO page = service.listInteractions(null, null, 1);

    assertEquals(1, page.getTotal());
    assertEquals(id(101).toString(), input(page.getItems().getFirst()).toolInvocationId());
    assertNull(page.getNextCursor());
  }

  /** 测试意图：环境等待条目的游标是组内最早调用的 {@code (createdAt, id)}；按该游标翻页只跳过已消费分组，不漏项、不重复。 */
  @Test
  void environmentWaitKeepsStableCursorAcrossPages() {
    UUID rootThreadId = id(31);
    UUID sessionId = id(32);
    Instant firstCreatedAt = Instant.parse("2026-03-01T10:00:00Z");
    Instant secondCreatedAt = Instant.parse("2026-03-01T10:05:00Z");
    PendingEnvironmentWait first =
        environmentWait(rootThreadId, WAITING_ENVIRONMENT_ID, firstCreatedAt, id(901), 1);
    PendingEnvironmentWait second =
        environmentWait(rootThreadId, WAITING_ENVIRONMENT_ID, secondCreatedAt, id(902), 2);
    stubRootSession(rootThreadId, sessionId);
    stubOwner(rootThreadId, sessionId, "CHAT", id(33), null);
    stubEnvironmentName(WAITING_ENVIRONMENT_ID, "dev-env");

    when(runtime.listPendingInteractions(Instant.EPOCH, ZERO_UUID, 1))
        .thenReturn(new PendingInteractionPage(List.of(), false));
    stubEnvironmentPage(Instant.EPOCH, ZERO_UUID, 1, List.of(first), true);
    when(runtime.listPendingInteractions(firstCreatedAt, id(901), 1))
        .thenReturn(new PendingInteractionPage(List.of(), false));
    stubEnvironmentPage(firstCreatedAt, id(901), 1, List.of(second), false);
    stubEnvironmentPage(
        firstCreatedAt, id(901), InteractionQueryService.COUNT_PAGE_SIZE, List.of(second), false);
    stubCountPage(List.of(), false);
    stubEnvironmentCountPage(List.of(first, second));

    InteractionPageDTO firstPage = service.listInteractions(null, null, 1);
    assertEquals(1, firstPage.getItems().size());
    assertEquals(1, environmentWait(firstPage.getItems().get(0)).waitingCount());
    assertEquals(2, firstPage.getTotal());
    String cursor = firstPage.getNextCursor();
    assertEquals(InteractionCursor.encode(firstCreatedAt, id(901)), cursor);

    InteractionPageDTO secondPage = service.listInteractions(null, cursor, 1);
    assertEquals(1, secondPage.getItems().size());
    assertEquals(2, environmentWait(secondPage.getItems().get(0)).waitingCount());
    assertEquals(secondCreatedAt, environmentWait(secondPage.getItems().get(0)).createdAt());
    assertEquals(2, secondPage.getTotal());
    assertNull(secondPage.getNextCursor());
  }

  /** 测试意图：显式根过滤同时裁剪两条来源的可见项与计数，绝不按客户端归属信任。 */
  @Test
  void rootFilterPrunesEnvironmentWaitsFromOtherRoots() {
    UUID environmentRootThreadId = id(41);
    UUID environmentSessionId = id(42);
    Instant environmentCreatedAt = Instant.parse("2026-03-01T10:00:00Z");
    PendingEnvironmentWait environmentItem =
        environmentWait(
            environmentRootThreadId, WAITING_ENVIRONMENT_ID, environmentCreatedAt, id(901), 2);
    stubRoot(environmentRootThreadId);
    stubRootSession(environmentRootThreadId, environmentSessionId);
    stubOwner(environmentRootThreadId, environmentSessionId, "CHAT", id(43), null);
    stubEnvironmentName(WAITING_ENVIRONMENT_ID, "dev-env");

    UUID otherRootThreadId = id(51);
    UUID otherSessionId = id(52);
    PendingInteraction otherManual =
        waitingInput(
            id(102), otherRootThreadId, otherSessionId, Instant.parse("2026-03-01T10:01:00Z"));
    stubRoot(otherRootThreadId);
    stubOwner(otherRootThreadId, otherSessionId, "CHAT", id(53), null);

    when(runtime.listPendingInteractions(Instant.EPOCH, ZERO_UUID, 10))
        .thenReturn(new PendingInteractionPage(List.of(otherManual), false));
    stubEnvironmentPage(Instant.EPOCH, ZERO_UUID, 10, List.of(environmentItem), false);
    stubCountPage(List.of(otherManual), false);
    stubEnvironmentCountPage(List.of(environmentItem));

    InteractionPageDTO page = service.listInteractions(environmentRootThreadId, null, 10);

    assertEquals(1, page.getItems().size());
    assertInstanceOf(InteractionDTO.EnvironmentWait.class, page.getItems().get(0));
    assertEquals(1, page.getTotal());
  }

  /** 测试意图：环境等待的根解析不到任何产品归属时不对外暴露，也不计入 total，且不空转返回假游标。 */
  @Test
  void environmentWaitWithoutOwnerIsNotExposedOrCounted() {
    UUID rootThreadId = id(61);
    UUID sessionId = id(62);
    PendingEnvironmentWait orphan =
        environmentWait(
            rootThreadId,
            WAITING_ENVIRONMENT_ID,
            Instant.parse("2026-03-01T10:00:00Z"),
            id(901),
            1);
    stubRootSession(rootThreadId, sessionId);

    when(runtime.listPendingInteractions(Instant.EPOCH, ZERO_UUID, 1))
        .thenReturn(new PendingInteractionPage(List.of(), false));
    stubEnvironmentPage(Instant.EPOCH, ZERO_UUID, 1, List.of(orphan), false);
    stubCountPage(List.of(), false);
    stubEnvironmentCountPage(List.of(orphan));

    InteractionPageDTO page = service.listInteractions(null, null, 1);

    assertEquals(0, page.getItems().size());
    assertEquals(0, page.getTotal());
    assertNull(page.getNextCursor());
    verifyNoInteractions(environmentService);
  }

  /** 测试意图：控制器/插件发起、没有 Chat 或 Issue 归属的独立任务根以 AGENT_TASK 暴露并计数，否则其审批无人可见、任务永久挂起。 */
  @Test
  void headlessAgentTaskRootIsExposedAsAgentTask() {
    UUID rootThreadId = id(71);
    UUID sessionId = id(72);
    PendingInteraction approval =
        waitingInput(id(73), rootThreadId, sessionId, Instant.EPOCH.plusSeconds(1));
    when(runtime.findRootThreadId(rootThreadId)).thenReturn(Optional.of(rootThreadId));
    ThreadState thread = mock(ThreadState.class);
    when(thread.sessionId()).thenReturn(sessionId);
    when(thread.name()).thenReturn("nightly report");
    when(thread.kind()).thenReturn(ThreadKind.CONTROLLER_TASK);
    EntryCurrentState entryState = mock(EntryCurrentState.class);
    when(entryState.baseSettings())
        .thenReturn(
            new BranchSettings(
                "worker", BuiltinExecution.of(ModelSelection.model("m", "v1")), null));
    ThreadSnapshot snapshot = mock(ThreadSnapshot.class);
    when(snapshot.thread()).thenReturn(thread);
    when(snapshot.entryState()).thenReturn(entryState);
    when(runtime.getThreadSnapshot(rootThreadId)).thenReturn(snapshot);
    when(runtime.listPendingInteractions(Instant.EPOCH, ZERO_UUID, 5))
        .thenReturn(new PendingInteractionPage(List.of(approval), false));
    stubEnvironmentPage(Instant.EPOCH, ZERO_UUID, 5, List.of(), false);
    stubCountPage(List.of(approval), false);
    stubEnvironmentCountPage(List.of());

    InteractionPageDTO page = service.listInteractions(null, null, 5);

    assertEquals(1, page.getItems().size());
    InteractionOwnerDTO owner = page.getItems().get(0).owner();
    assertEquals("AGENT_TASK", owner.getKind());
    assertEquals(rootThreadId.toString(), owner.getId());
    assertEquals("worker", owner.getTitle());
    assertNull(owner.getLink());
    assertEquals(1, page.getTotal());
  }

  /** 测试意图：验证构造函数的非空防御。 */
  @Test
  void constructorRejectsNullDependencies() {
    assertThrows(
        NullPointerException.class,
        () -> new InteractionQueryService(null, environmentService, runtimes));
    assertThrows(
        NullPointerException.class,
        () -> new InteractionQueryService(List.of(ownership), null, runtimes));
    assertThrows(
        NullPointerException.class,
        () -> new InteractionQueryService(List.of(ownership), environmentService, null));
  }
}
