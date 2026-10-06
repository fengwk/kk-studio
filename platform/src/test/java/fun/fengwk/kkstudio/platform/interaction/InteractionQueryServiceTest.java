package fun.fengwk.kkstudio.platform.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.interaction.PendingInteraction;
import fun.fengwk.kkstudio.harness.runtime.interaction.PendingInteractionPage;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSession;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSessionRepository;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.share.ai.interaction.InteractionDTO;
import fun.fengwk.kkstudio.share.ai.interaction.InteractionPageDTO;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 待处理交互统一查询用例 {@link InteractionQueryService} 的单元测试。 */
class InteractionQueryServiceTest {

  private static final UUID ZERO_UUID = new UUID(0L, 0L);

  private static UUID id(long value) {
    return new UUID(0L, value);
  }

  private ChatSessionRepository chatSessionRepository;
  private IssueAgentThreadRepository issueAgentThreadRepository;
  private ObjectProvider<HarnessRuntime> runtimes;
  private HarnessRuntime runtime;
  private InteractionQueryService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    chatSessionRepository = mock(ChatSessionRepository.class);
    issueAgentThreadRepository = mock(IssueAgentThreadRepository.class);
    runtimes = mock(ObjectProvider.class);
    runtime = mock(HarnessRuntime.class);

    when(runtimes.getIfAvailable()).thenReturn(runtime);
    service =
        new InteractionQueryService(chatSessionRepository, issueAgentThreadRepository, runtimes);
  }

  /** 显式过滤根必须解析为执行根；不存在抛 not-found、非根抛非法参数。 */
  private void stubRoot(UUID threadId) {
    when(runtime.findAncestorChain(threadId)).thenReturn(List.of(threadId));
  }

  private void stubDescendant(UUID threadId, UUID rootThreadId) {
    when(runtime.findAncestorChain(threadId)).thenReturn(List.of(threadId, rootThreadId));
  }

  private void stubRootSession(UUID rootThreadId, UUID sessionId) {
    ThreadState thread = mock(ThreadState.class);
    when(thread.sessionId()).thenReturn(sessionId);
    ThreadSnapshot snapshot = mock(ThreadSnapshot.class);
    when(snapshot.thread()).thenReturn(thread);
    when(runtime.getThreadSnapshot(rootThreadId)).thenReturn(snapshot);
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

    when(chatSessionRepository.findBySessionId(sessionId1))
        .thenReturn(new ChatSession(sessionId1, id(1001)));
    when(chatSessionRepository.findBySessionId(sessionId2)).thenReturn(null);
    when(issueAgentThreadRepository.findByThreadId(threadId2))
        .thenReturn(new IssueAgentThread(id(2001), "planner", threadId2));
    when(chatSessionRepository.findBySessionId(sessionId3)).thenReturn(null);
    when(issueAgentThreadRepository.findByThreadId(threadId3)).thenReturn(null);

    when(runtime.listPendingInteractions(Instant.EPOCH, ZERO_UUID, 10))
        .thenReturn(new PendingInteractionPage(List.of(row1, row2, row3), false));
    stubCountPage(List.of(row1, row2, row3), false);

    InteractionPageDTO page = service.listInteractions(null, null, 10);

    assertEquals(2, page.getItems().size());
    assertNull(page.getNextCursor());
    assertEquals(2, page.getTotal(), "total 统计的是可见待处理行，而不是原始源行数");

    InteractionDTO dto1 = page.getItems().get(0);
    assertEquals(invocationId1.toString(), dto1.getInteractionId());
    assertEquals("WAITING_INPUT", dto1.getStatus());
    assertEquals(threadId1.toString(), dto1.getThreadId());
    assertEquals(sessionId1.toString(), dto1.getSessionId());
    assertEquals(threadId1.toString(), dto1.getRootThreadId());
    assertEquals("call", dto1.getToolCallId());
    assertEquals("ask_user", dto1.getToolName());
    assertEquals("{}", dto1.getArgumentsJson());
    assertNull(dto1.getApprovalJson());
    assertEquals(t1, dto1.getCreateTime());
    assertEquals("CHAT", dto1.getOwner().getType());
    assertEquals(id(1001).toString(), dto1.getOwner().getChatId());
    assertNull(dto1.getOwner().getIssueId());
    assertNull(dto1.getOwner().getAgentName());

    InteractionDTO dto2 = page.getItems().get(1);
    assertEquals(invocationId2.toString(), dto2.getInteractionId());
    assertEquals("WAITING_APPROVAL", dto2.getStatus());
    assertEquals(threadId2.toString(), dto2.getThreadId());
    assertEquals(sessionId2.toString(), dto2.getSessionId());
    assertEquals(threadId2.toString(), dto2.getRootThreadId());
    assertEquals("{\"env\":\"prod\"}", dto2.getArgumentsJson());
    assertEquals("{\"allowed\":false}", dto2.getApprovalJson());
    assertEquals(t2, dto2.getCreateTime());
    assertEquals("ISSUE_AGENT", dto2.getOwner().getType());
    assertEquals(id(2001).toString(), dto2.getOwner().getIssueId());
    assertEquals("planner", dto2.getOwner().getAgentName());
    assertNull(dto2.getOwner().getChatId());
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
    when(chatSessionRepository.findBySessionId(rootSessionId))
        .thenReturn(new ChatSession(rootSessionId, id(700)));
    when(runtime.listPendingInteractions(Instant.EPOCH, ZERO_UUID, 10))
        .thenReturn(new PendingInteractionPage(List.of(childRow), false));
    stubCountPage(List.of(childRow), false);

    InteractionPageDTO page = service.listInteractions(null, null, 10);

    assertEquals(1, page.getItems().size());
    assertEquals(1, page.getTotal());
    InteractionDTO dto = page.getItems().get(0);
    assertEquals(childThreadId.toString(), dto.getThreadId());
    assertEquals(childSessionId.toString(), dto.getSessionId());
    assertEquals(rootThreadId.toString(), dto.getRootThreadId());
    assertEquals("CHAT", dto.getOwner().getType());
    assertEquals(id(700).toString(), dto.getOwner().getChatId());
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
    when(chatSessionRepository.findBySessionId(rootSessionId))
        .thenReturn(new ChatSession(rootSessionId, id(700)));
    when(chatSessionRepository.findBySessionId(otherRootSessionId))
        .thenReturn(new ChatSession(otherRootSessionId, id(710)));

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
    assertEquals(id(901).toString(), page.getItems().get(0).getInteractionId());
    assertEquals(rootThreadId.toString(), page.getItems().get(0).getRootThreadId());
    assertEquals(id(903).toString(), page.getItems().get(1).getInteractionId());
    assertEquals(childThreadId.toString(), page.getItems().get(1).getThreadId());
    assertEquals(rootThreadId.toString(), page.getItems().get(1).getRootThreadId());
    assertNull(page.getNextCursor());
    assertEquals(2, page.getTotal(), "total 必须与列表使用同一根过滤");

    verify(runtime).listPendingInteractions(Instant.EPOCH, ZERO_UUID, 2);
    verify(runtime).listPendingInteractions(t2, id(902), 1);
  }

  /** 测试意图：显式根过滤值必须是已存在的执行根，不存在返回 not-found、子 Thread 返回非法参数且都不进入分页扫描。 */
  @Test
  void rootFilterRejectsNonexistentAndNonRootThreads() {
    when(runtime.findAncestorChain(id(999))).thenReturn(List.of());
    assertThrows(
        HarnessRuntimeNotFoundException.class, () -> service.listInteractions(id(999), null, 10));

    UUID child = id(501);
    UUID root = id(500);
    when(runtime.findAncestorChain(child)).thenReturn(List.of(child, root));
    assertThrows(IllegalArgumentException.class, () -> service.listInteractions(child, null, 10));

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
    when(chatSessionRepository.findBySessionId(id(1))).thenReturn(null);
    when(issueAgentThreadRepository.findByThreadId(id(2))).thenReturn(null);
    when(chatSessionRepository.findBySessionId(id(3))).thenReturn(null);
    when(issueAgentThreadRepository.findByThreadId(id(4))).thenReturn(null);

    // row3 可见 (Chat)
    stubRoot(id(6));
    when(chatSessionRepository.findBySessionId(id(5))).thenReturn(new ChatSession(id(5), id(500)));

    // row4 可见 (IssueAgent)
    stubRoot(id(8));
    when(chatSessionRepository.findBySessionId(id(7))).thenReturn(null);
    when(issueAgentThreadRepository.findByThreadId(id(8)))
        .thenReturn(new IssueAgentThread(id(800), "coder", id(8)));

    // row5 可见 (Chat)
    stubRoot(id(10));
    when(chatSessionRepository.findBySessionId(id(9))).thenReturn(new ChatSession(id(9), id(900)));

    // 第 1 页返回 3 条（2 条不可见 + 1 条可见），hasMore 为 true
    when(runtime.listPendingInteractions(Instant.EPOCH, ZERO_UUID, 3))
        .thenReturn(new PendingInteractionPage(List.of(row1, row2, row3), true));

    // 第 2 页以第 1 页最后一行 (t3, id(30)) 作为游标，额度为 3 - 1 = 2；返回 2 条可见，hasMore 为 false
    when(runtime.listPendingInteractions(t3, id(30), 2))
        .thenReturn(new PendingInteractionPage(List.of(row4, row5), false));

    stubCountPage(List.of(row1, row2, row3, row4, row5), false);

    InteractionPageDTO page = service.listInteractions(null, null, 3);

    assertEquals(3, page.getItems().size());
    assertEquals(id(30).toString(), page.getItems().get(0).getInteractionId());
    assertEquals(id(40).toString(), page.getItems().get(1).getInteractionId());
    assertEquals(id(50).toString(), page.getItems().get(2).getInteractionId());
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
    when(chatSessionRepository.findBySessionId(id(1))).thenReturn(new ChatSession(id(1), id(100)));

    // row2 不可见
    stubRoot(id(4));
    when(chatSessionRepository.findBySessionId(id(3))).thenReturn(null);
    when(issueAgentThreadRepository.findByThreadId(id(4))).thenReturn(null);

    // row3 可见 (Chat)
    stubRoot(id(6));
    when(chatSessionRepository.findBySessionId(id(5))).thenReturn(new ChatSession(id(5), id(300)));

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
    assertEquals(id(30).toString(), secondPage.getItems().get(0).getInteractionId());
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
    when(chatSessionRepository.findBySessionId(sessionId))
        .thenReturn(new ChatSession(sessionId, id(1001)));
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
    when(chatSessionRepository.findBySessionId(id(1))).thenReturn(new ChatSession(id(1), id(100)));
    stubRoot(id(4));
    when(chatSessionRepository.findBySessionId(id(3))).thenReturn(null);
    when(issueAgentThreadRepository.findByThreadId(id(4))).thenReturn(null);
    stubRoot(id(6));
    when(chatSessionRepository.findBySessionId(id(5))).thenReturn(new ChatSession(id(5), id(300)));
    stubRoot(id(8));
    when(chatSessionRepository.findBySessionId(id(7))).thenReturn(new ChatSession(id(7), id(400)));

    when(runtime.listPendingInteractions(Instant.EPOCH, ZERO_UUID, 1))
        .thenReturn(new PendingInteractionPage(List.of(row1), true));
    stubCountPage(List.of(row1, row2), true);
    stubCountPage(t2, id(20), List.of(row3, row4), false);

    InteractionPageDTO page = service.listInteractions(null, null, 1);

    assertEquals(1, page.getItems().size());
    assertEquals(id(10).toString(), page.getItems().get(0).getInteractionId());
    assertEquals(3, page.getTotal(), "total 必须统计完整源的可见行，不能等于首页长度");
  }

  /** 测试意图：验证各种非法格式游标均抛出 IllegalArgumentException，且不触碰 runtime。 */
  @Test
  void malformedCursorThrowsIllegalArgumentException() {
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
      assertThrows(
          IllegalArgumentException.class, () -> service.listInteractions(null, cursor, 10));
    }

    verifyNoInteractions(runtime);
  }

  /** 测试意图：验证 limit <= 0 时立即抛出 IllegalArgumentException，且不触碰 runtime。 */
  @Test
  void nonPositiveLimitThrowsIllegalArgumentException() {
    assertThrows(IllegalArgumentException.class, () -> service.listInteractions(null, null, 0));
    assertThrows(IllegalArgumentException.class, () -> service.listInteractions(null, null, -1));
    verifyNoInteractions(runtime);
  }

  /** 测试意图：验证 HarnessRuntime 未提供时抛出 IllegalStateException。 */
  @Test
  void runtimeUnavailableThrowsIllegalStateException() {
    when(runtimes.getIfAvailable()).thenReturn(null);
    assertThrows(IllegalStateException.class, () -> service.listInteractions(null, null, 10));
  }

  /** 测试意图：验证构造函数的非空防御。 */
  @Test
  void constructorRejectsNullDependencies() {
    assertThrows(
        NullPointerException.class,
        () -> new InteractionQueryService(null, issueAgentThreadRepository, runtimes));
    assertThrows(
        NullPointerException.class,
        () -> new InteractionQueryService(chatSessionRepository, null, runtimes));
    assertThrows(
        NullPointerException.class,
        () -> new InteractionQueryService(chatSessionRepository, issueAgentThreadRepository, null));
  }
}
