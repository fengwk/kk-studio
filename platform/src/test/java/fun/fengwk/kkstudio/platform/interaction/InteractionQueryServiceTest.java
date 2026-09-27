package fun.fengwk.kkstudio.platform.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.interaction.PendingInteraction;
import fun.fengwk.kkstudio.harness.runtime.interaction.PendingInteractionPage;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
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

  /**
   * 测试意图：验证 InteractionQueryService 能正确解析 CHAT 归属（通过 SessionId 命中 ChatSession）与 ISSUE_AGENT 归属（通过
   * ThreadId 命中 IssueAgentThread），并将两者均未命中的内部委派或隐藏行过滤（不暴露给客户端）。
   */
  @Test
  void ownerResolutionResolvesChatAndIssueAgentAndFiltersInvisibleRows() {
    UUID sessionId1 = id(10);
    UUID threadId1 = id(11);
    UUID invocationId1 = id(101);
    Instant t1 = Instant.parse("2026-03-01T10:00:00Z");
    PendingInteraction row1 =
        new PendingInteraction(
            invocationId1,
            threadId1,
            sessionId1,
            ToolInvocationStatus.WAITING_INPUT,
            "call-1",
            "ask_user",
            "{\"questions\":[]}",
            null,
            t1);

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

    UUID sessionId3 = id(30);
    UUID threadId3 = id(31);
    UUID invocationId3 = id(103);
    Instant t3 = Instant.parse("2026-03-01T10:02:00Z");
    PendingInteraction row3 =
        new PendingInteraction(
            invocationId3,
            threadId3,
            sessionId3,
            ToolInvocationStatus.WAITING_INPUT,
            "call-3",
            "internal_call",
            "{}",
            null,
            t3);

    when(chatSessionRepository.findBySessionId(sessionId1))
        .thenReturn(new ChatSession(sessionId1, id(1001)));
    when(chatSessionRepository.findBySessionId(sessionId2)).thenReturn(null);
    when(issueAgentThreadRepository.findByThreadId(threadId2))
        .thenReturn(new IssueAgentThread(id(2001), "planner", threadId2));
    when(chatSessionRepository.findBySessionId(sessionId3)).thenReturn(null);
    when(issueAgentThreadRepository.findByThreadId(threadId3)).thenReturn(null);

    when(runtime.listPendingInteractions(Instant.EPOCH, new UUID(0L, 0L), 10))
        .thenReturn(new PendingInteractionPage(List.of(row1, row2, row3), false));

    InteractionPageDTO page = service.listInteractions(null, 10);

    assertEquals(2, page.getItems().size());
    assertNull(page.getNextCursor());

    InteractionDTO dto1 = page.getItems().get(0);
    assertEquals(invocationId1.toString(), dto1.getInteractionId());
    assertEquals("WAITING_INPUT", dto1.getStatus());
    assertEquals(threadId1.toString(), dto1.getThreadId());
    assertEquals(sessionId1.toString(), dto1.getSessionId());
    assertEquals("call-1", dto1.getToolCallId());
    assertEquals("ask_user", dto1.getToolName());
    assertEquals("{\"questions\":[]}", dto1.getArgumentsJson());
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
    assertEquals("call-2", dto2.getToolCallId());
    assertEquals("deploy_service", dto2.getToolName());
    assertEquals("{\"env\":\"prod\"}", dto2.getArgumentsJson());
    assertEquals("{\"allowed\":false}", dto2.getApprovalJson());
    assertEquals(t2, dto2.getCreateTime());
    assertEquals("ISSUE_AGENT", dto2.getOwner().getType());
    assertEquals(id(2001).toString(), dto2.getOwner().getIssueId());
    assertEquals("planner", dto2.getOwner().getAgentName());
    assertNull(dto2.getOwner().getChatId());
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

    PendingInteraction row1 =
        new PendingInteraction(
            id(10),
            id(2),
            id(1),
            ToolInvocationStatus.WAITING_INPUT,
            "c1",
            "tool1",
            "{}",
            null,
            t1);
    PendingInteraction row2 =
        new PendingInteraction(
            id(20),
            id(4),
            id(3),
            ToolInvocationStatus.WAITING_INPUT,
            "c2",
            "tool2",
            "{}",
            null,
            t2);
    PendingInteraction row3 =
        new PendingInteraction(
            id(30),
            id(6),
            id(5),
            ToolInvocationStatus.WAITING_INPUT,
            "c3",
            "tool3",
            "{}",
            null,
            t3);

    PendingInteraction row4 =
        new PendingInteraction(
            id(40),
            id(8),
            id(7),
            ToolInvocationStatus.WAITING_INPUT,
            "c4",
            "tool4",
            "{}",
            null,
            t4);
    PendingInteraction row5 =
        new PendingInteraction(
            id(50),
            id(10),
            id(9),
            ToolInvocationStatus.WAITING_INPUT,
            "c5",
            "tool5",
            "{}",
            null,
            t5);

    // row1, row2 不可见
    when(chatSessionRepository.findBySessionId(id(1))).thenReturn(null);
    when(issueAgentThreadRepository.findByThreadId(id(2))).thenReturn(null);
    when(chatSessionRepository.findBySessionId(id(3))).thenReturn(null);
    when(issueAgentThreadRepository.findByThreadId(id(4))).thenReturn(null);

    // row3 可见 (Chat)
    when(chatSessionRepository.findBySessionId(id(5))).thenReturn(new ChatSession(id(5), id(500)));

    // row4 可见 (IssueAgent)
    when(chatSessionRepository.findBySessionId(id(7))).thenReturn(null);
    when(issueAgentThreadRepository.findByThreadId(id(8)))
        .thenReturn(new IssueAgentThread(id(800), "coder", id(8)));

    // row5 可见 (Chat)
    when(chatSessionRepository.findBySessionId(id(9))).thenReturn(new ChatSession(id(9), id(900)));

    // 第 1 页返回 3 条（2 条不可见 + 1 条可见），hasMore 为 true
    when(runtime.listPendingInteractions(Instant.EPOCH, new UUID(0L, 0L), 3))
        .thenReturn(new PendingInteractionPage(List.of(row1, row2, row3), true));

    // 第 2 页以第 1 页最后一行 (t3, id(30)) 作为游标，额度为 3 - 1 = 2；返回 2 条可见，hasMore 为 false
    when(runtime.listPendingInteractions(t3, id(30), 2))
        .thenReturn(new PendingInteractionPage(List.of(row4, row5), false));

    InteractionPageDTO page = service.listInteractions(null, 3);

    assertEquals(3, page.getItems().size());
    assertEquals(id(30).toString(), page.getItems().get(0).getInteractionId());
    assertEquals(id(40).toString(), page.getItems().get(1).getInteractionId());
    assertEquals(id(50).toString(), page.getItems().get(2).getInteractionId());
    assertNull(page.getNextCursor());

    verify(runtime).listPendingInteractions(Instant.EPOCH, new UUID(0L, 0L), 3);
    verify(runtime).listPendingInteractions(t3, id(30), 2);
    verifyNoMoreInteractions(runtime);
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

    PendingInteraction row1 =
        new PendingInteraction(
            id(10),
            id(2),
            id(1),
            ToolInvocationStatus.WAITING_INPUT,
            "c1",
            "tool1",
            "{}",
            null,
            t1);
    PendingInteraction row2 =
        new PendingInteraction(
            id(20),
            id(4),
            id(3),
            ToolInvocationStatus.WAITING_INPUT,
            "c2",
            "tool2",
            "{}",
            null,
            t2);
    PendingInteraction row3 =
        new PendingInteraction(
            id(30),
            id(6),
            id(5),
            ToolInvocationStatus.WAITING_INPUT,
            "c3",
            "tool3",
            "{}",
            null,
            t3);

    // row1 可见 (Chat)
    when(chatSessionRepository.findBySessionId(id(1))).thenReturn(new ChatSession(id(1), id(100)));

    // row2 不可见
    when(chatSessionRepository.findBySessionId(id(3))).thenReturn(null);
    when(issueAgentThreadRepository.findByThreadId(id(4))).thenReturn(null);

    // row3 可见 (Chat)
    when(chatSessionRepository.findBySessionId(id(5))).thenReturn(new ChatSession(id(5), id(300)));

    // 第一次调用：limit=1，返回 row1 且 hasMore=true
    when(runtime.listPendingInteractions(Instant.EPOCH, new UUID(0L, 0L), 1))
        .thenReturn(new PendingInteractionPage(List.of(row1), true));

    InteractionPageDTO firstPage = service.listInteractions(null, 1);
    assertEquals(1, firstPage.getItems().size());
    String expectedCursor1 = t1.toEpochMilli() + ":" + id(10);
    assertEquals(expectedCursor1, firstPage.getNextCursor());

    // 第二次调用：携带 expectedCursor1，limit=1。
    // 第 1 轮 runtime 返回 row2（整页不可见，hasMore=true），验证服务内部以 row2 (t2, id(20)) 自动继续推进扫描下一页
    when(runtime.listPendingInteractions(t1, id(10), 1))
        .thenReturn(new PendingInteractionPage(List.of(row2), true));
    when(runtime.listPendingInteractions(t2, id(20), 1))
        .thenReturn(new PendingInteractionPage(List.of(row3), false));

    InteractionPageDTO secondPage = service.listInteractions(firstPage.getNextCursor(), 1);
    assertEquals(1, secondPage.getItems().size());
    assertEquals(id(30).toString(), secondPage.getItems().get(0).getInteractionId());
    assertNull(secondPage.getNextCursor());

    verify(runtime).listPendingInteractions(Instant.EPOCH, new UUID(0L, 0L), 1);
    verify(runtime).listPendingInteractions(t1, id(10), 1);
    verify(runtime).listPendingInteractions(t2, id(20), 1);
    verifyNoMoreInteractions(runtime);
  }

  /** 测试意图：验证底层源耗尽（hasMore 为 false 或空列表）时，返回的 nextCursor 必须为 null。 */
  @Test
  void exhaustedSourceYieldsNullNextCursor() {
    // 场景 A：首屏即为空列表
    when(runtime.listPendingInteractions(Instant.EPOCH, new UUID(0L, 0L), 10))
        .thenReturn(new PendingInteractionPage(List.of(), false));

    InteractionPageDTO pageEmpty = service.listInteractions(null, 10);
    assertEquals(0, pageEmpty.getItems().size());
    assertNull(pageEmpty.getNextCursor());

    // 场景 B：首屏有数据但 hasMore 为 false
    UUID sessionId = id(10);
    UUID threadId = id(11);
    UUID invocationId = id(101);
    Instant t1 = Instant.parse("2026-03-01T10:00:00Z");
    PendingInteraction row =
        new PendingInteraction(
            invocationId,
            threadId,
            sessionId,
            ToolInvocationStatus.WAITING_INPUT,
            "call-1",
            "ask_user",
            "{}",
            null,
            t1);
    when(chatSessionRepository.findBySessionId(sessionId))
        .thenReturn(new ChatSession(sessionId, id(1001)));
    when(runtime.listPendingInteractions(Instant.EPOCH, new UUID(0L, 0L), 5))
        .thenReturn(new PendingInteractionPage(List.of(row), false));

    InteractionPageDTO pageWithItems = service.listInteractions(null, 5);
    assertEquals(1, pageWithItems.getItems().size());
    assertNull(pageWithItems.getNextCursor());
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
      assertThrows(IllegalArgumentException.class, () -> service.listInteractions(cursor, 10));
    }

    verifyNoInteractions(runtime);
  }

  /** 测试意图：验证 limit <= 0 时立即抛出 IllegalArgumentException，且不触碰 runtime。 */
  @Test
  void nonPositiveLimitThrowsIllegalArgumentException() {
    assertThrows(IllegalArgumentException.class, () -> service.listInteractions(null, 0));
    assertThrows(IllegalArgumentException.class, () -> service.listInteractions(null, -1));
    verifyNoInteractions(runtime);
  }

  /** 测试意图：验证 HarnessRuntime 未提供时抛出 IllegalStateException。 */
  @Test
  void runtimeUnavailableThrowsIllegalStateException() {
    when(runtimes.getIfAvailable()).thenReturn(null);
    assertThrows(IllegalStateException.class, () -> service.listInteractions(null, 10));
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
