package fun.fengwk.kkstudio.platform.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.harness.api.ToolInputAcceptance;
import fun.fengwk.kkstudio.harness.api.ToolInputSubmissionCommand;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.history.EntryCurrentState;
import fun.fengwk.kkstudio.harness.api.interaction.PendingEnvironmentWaitPage;
import fun.fengwk.kkstudio.harness.api.interaction.PendingInteraction;
import fun.fengwk.kkstudio.harness.api.interaction.PendingInteractionPage;
import fun.fengwk.kkstudio.harness.api.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.api.thread.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.environment.EnvironmentService;
import fun.fengwk.kkstudio.platform.harness.dto.InteractionDTO;
import fun.fengwk.kkstudio.platform.harness.dto.InteractionPageDTO;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwnerDisplay;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwnership;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 根归属一致性：查询与提交/审批对同一点代来源必须解析出同一真实执行根。
 *
 * <p>本测试刻意让查询与写入共享同一 Runtime stub 与 owner stub：同一点代来源只有一个根，查询据此投影 owner、写入据此加产品锁，谁都不能自行发明根。
 */
class InteractionRootAttributionConsistencyTest {

  private static final UUID ZERO_UUID = new UUID(0L, 0L);

  private static UUID id(long value) {
    return new UUID(0L, value);
  }

  private SessionOwnership sessionOwnership;
  private EnvironmentService environmentService;
  private ObjectProvider<HarnessRuntime> runtimes;
  private HarnessRuntime runtime;
  private InteractionQueryService queryService;
  private InteractionService interactionService;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    sessionOwnership = mock(SessionOwnership.class);
    environmentService = mock(EnvironmentService.class);
    runtimes = mock(ObjectProvider.class);
    runtime = mock(HarnessRuntime.class);

    when(runtimes.getIfAvailable()).thenReturn(runtime);
    when(runtime.listEnvironmentWaitCandidates(any(), any(), anyInt()))
        .thenReturn(new PendingEnvironmentWaitPage(List.of(), false));
    queryService =
        new InteractionQueryService(List.of(sessionOwnership), environmentService, runtimes);
    interactionService = new InteractionService(List.of(sessionOwnership), runtimes);
  }

  /** 测试意图：同一后代来源的根归属在查询与提交间一致——查询按根的 Issue 绑定投影 owner，提交按同一根的绑定加产品锁，两个入口对同一后代各调用一次同源根解析。 */
  @Test
  void queryAndSubmitResolveSameDescendantRootAttribution() {
    UUID childThreadId = id(100);
    UUID rootThreadId = id(101);
    UUID rootSessionId = id(400);
    UUID issueId = id(200);

    when(runtime.findRootThreadId(childThreadId)).thenReturn(Optional.of(rootThreadId));
    when(sessionOwnership.findOwner(rootThreadId, rootSessionId))
        .thenReturn(
            Optional.of(new SessionOwnerDisplay("ISSUE_AGENT", issueId.toString(), "issue", null)));

    // 查询：后代行先按根定位，再经根快照取得根 Session，最后按根 Thread 绑定解析 owner。
    ThreadState rootThread = mock(ThreadState.class);
    when(rootThread.sessionId()).thenReturn(rootSessionId);
    ThreadSnapshot rootSnapshot = mock(ThreadSnapshot.class);
    when(rootSnapshot.thread()).thenReturn(rootThread);
    when(runtime.getThreadSnapshot(rootThreadId)).thenReturn(rootSnapshot);
    PendingInteraction row =
        new PendingInteraction(
            id(900),
            childThreadId,
            id(401),
            ToolInvocationStatus.WAITING_INPUT,
            "call",
            "ask_user",
            "{}",
            null,
            Instant.parse("2026-03-01T10:00:00Z"));
    when(runtime.listPendingInteractions(Instant.EPOCH, ZERO_UUID, 10))
        .thenReturn(new PendingInteractionPage(List.of(row), false));
    when(runtime.listPendingInteractions(
            Instant.EPOCH, ZERO_UUID, InteractionQueryService.COUNT_PAGE_SIZE))
        .thenReturn(new PendingInteractionPage(List.of(row), false));

    InteractionPageDTO page = queryService.listInteractions(null, null, 10);

    assertEquals(1, page.getItems().size());
    InteractionDTO.Input dto = assertInstanceOf(InteractionDTO.Input.class, page.getItems().get(0));
    assertEquals(childThreadId.toString(), dto.threadId());
    assertEquals(rootThreadId.toString(), dto.rootThreadId());
    assertEquals(issueId.toString(), dto.owner().getId());
    assertEquals("ISSUE_AGENT", dto.owner().getKind());
    assertEquals("issue", dto.owner().getTitle());

    // 提交：同一点代来源的产品锁同样按根解析。
    ToolInputSubmissionCommand command =
        new ToolInputSubmissionCommand(
            childThreadId, id(102), id(103), "alice", false, List.of(List.of("ans")));
    when(runtime.submitToolInput(any(ToolInputSubmissionCommand.class)))
        .thenReturn(mock(ToolInputAcceptance.class));

    interactionService.submitInput(command);

    // 两个入口对同一后代各做一次同源根解析，命中同一真实根；提交按该根加产品锁。
    verify(runtime, times(2)).findRootThreadId(childThreadId);
    verify(sessionOwnership).findOwner(rootThreadId, rootSessionId);
    verify(sessionOwnership).lockForInteraction(rootThreadId, rootSessionId);
  }

  /** 两个产品同时声明同一执行根时不能按注册顺序选择归属。 */
  @Test
  void conflictingProductsFailClosed() {
    UUID root = id(1);
    UUID session = id(2);
    SessionOwnership other = mock(SessionOwnership.class);
    queryService =
        new InteractionQueryService(List.of(sessionOwnership, other), environmentService, runtimes);
    when(sessionOwnership.findOwner(root, session))
        .thenReturn(Optional.of(new SessionOwnerDisplay("A", "1", null, null)));
    when(other.findOwner(root, session))
        .thenReturn(Optional.of(new SessionOwnerDisplay("B", "2", null, null)));
    stubSingleRoot(root, session);

    assertThrows(IllegalStateException.class, () -> queryService.listInteractions(null, null, 10));
    verify(sessionOwnership).findOwner(root, session);
    verify(other).findOwner(root, session);
  }

  /** 无产品归属时独立任务使用 agent 名；缺失 agent 名时使用根名称。 */
  @Test
  void standaloneTaskFallsBackToRootName() {
    UUID root = id(1);
    UUID session = id(2);
    stubSingleRoot(root, session);
    ThreadState thread = mock(ThreadState.class);
    when(thread.spawnTrigger()).thenReturn(SpawnTrigger.PLUGIN);
    when(thread.name()).thenReturn("root name");
    BranchSettings settings = mock(BranchSettings.class);
    EntryCurrentState entry = mock(EntryCurrentState.class);
    when(entry.baseSettings()).thenReturn(settings);
    ThreadSnapshot snapshot = mock(ThreadSnapshot.class);
    when(snapshot.thread()).thenReturn(thread);
    when(snapshot.entryState()).thenReturn(entry);
    when(runtime.getThreadSnapshot(root)).thenReturn(snapshot);

    InteractionDTO dto = queryService.listInteractions(null, null, 10).getItems().get(0);
    assertEquals("AGENT_TASK", dto.owner().getKind());
    assertEquals(root.toString(), dto.owner().getId());
    assertEquals("root name", dto.owner().getTitle());
    assertNull(dto.owner().getLink());
    verify(sessionOwnership).findOwner(root, session);
  }

  private void stubSingleRoot(UUID root, UUID session) {
    when(runtime.findRootThreadId(root)).thenReturn(Optional.of(root));
    PendingInteraction row =
        new PendingInteraction(
            id(9),
            root,
            session,
            ToolInvocationStatus.WAITING_INPUT,
            "call",
            "ask",
            "{}",
            null,
            Instant.parse("2026-03-01T10:00:00Z"));
    when(runtime.listPendingInteractions(Instant.EPOCH, ZERO_UUID, 10))
        .thenReturn(new PendingInteractionPage(List.of(row), false));
    when(runtime.listPendingInteractions(
            Instant.EPOCH, ZERO_UUID, InteractionQueryService.COUNT_PAGE_SIZE))
        .thenReturn(new PendingInteractionPage(List.of(row), false));
  }
}
