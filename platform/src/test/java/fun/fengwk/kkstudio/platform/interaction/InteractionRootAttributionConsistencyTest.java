package fun.fengwk.kkstudio.platform.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.ToolInputAcceptance;
import fun.fengwk.kkstudio.harness.runtime.ToolInputSubmissionCommand;
import fun.fengwk.kkstudio.harness.runtime.interaction.PendingInteraction;
import fun.fengwk.kkstudio.harness.runtime.interaction.PendingInteractionPage;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSessionRepository;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.share.ai.interaction.InteractionDTO;
import fun.fengwk.kkstudio.share.ai.interaction.InteractionPageDTO;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 根归属一致性：查询与提交/审批对同一点代来源必须解析出同一真实执行根。
 *
 * <p>本测试刻意让查询与写入共享同一 Runtime stub 与仓储 stub：同一点代来源只有一个根，查询据此投影 owner、写入据此加产品锁，谁都不能自行发明根。
 */
class InteractionRootAttributionConsistencyTest {

  private static final UUID ZERO_UUID = new UUID(0L, 0L);

  private static UUID id(long value) {
    return new UUID(0L, value);
  }

  private ChatSessionRepository chatSessionRepository;
  private IssueAgentThreadRepository issueAgentThreadRepository;
  private IssueRepository issueRepository;
  private ProjectRepository projectRepository;
  private ObjectProvider<HarnessRuntime> runtimes;
  private HarnessRuntime runtime;
  private InteractionQueryService queryService;
  private InteractionService interactionService;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    chatSessionRepository = mock(ChatSessionRepository.class);
    issueAgentThreadRepository = mock(IssueAgentThreadRepository.class);
    issueRepository = mock(IssueRepository.class);
    projectRepository = mock(ProjectRepository.class);
    runtimes = mock(ObjectProvider.class);
    runtime = mock(HarnessRuntime.class);

    when(runtimes.getIfAvailable()).thenReturn(runtime);
    queryService =
        new InteractionQueryService(chatSessionRepository, issueAgentThreadRepository, runtimes);
    interactionService =
        new InteractionService(
            issueAgentThreadRepository, issueRepository, projectRepository, runtimes);
  }

  /** 测试意图：同一后代来源的根归属在查询与提交间一致——查询按根的 Issue 绑定投影 owner，提交按同一根的绑定加产品锁，两个入口对同一后代各调用一次同源根解析。 */
  @Test
  void queryAndSubmitResolveSameDescendantRootAttribution() {
    UUID childThreadId = id(100);
    UUID rootThreadId = id(101);
    UUID rootSessionId = id(400);
    UUID issueId = id(200);
    UUID projectId = id(300);

    when(runtime.findAncestorChain(childThreadId)).thenReturn(List.of(childThreadId, rootThreadId));
    IssueAgentThread binding = new IssueAgentThread(issueId, "coder", rootThreadId);
    when(issueAgentThreadRepository.findByThreadId(rootThreadId)).thenReturn(binding);

    // 查询：后代行先按根定位，再经根快照取得根 Session，最后按根 Thread 绑定解析 owner。
    ThreadState rootThread = mock(ThreadState.class);
    when(rootThread.sessionId()).thenReturn(rootSessionId);
    ThreadSnapshot rootSnapshot = mock(ThreadSnapshot.class);
    when(rootSnapshot.thread()).thenReturn(rootThread);
    when(runtime.getThreadSnapshot(rootThreadId)).thenReturn(rootSnapshot);
    when(chatSessionRepository.findBySessionId(rootSessionId)).thenReturn(null);
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

    InteractionPageDTO page = queryService.listInteractions(null, null, 10);

    assertEquals(1, page.getItems().size());
    InteractionDTO dto = page.getItems().get(0);
    assertEquals(childThreadId.toString(), dto.getThreadId());
    assertEquals(rootThreadId.toString(), dto.getRootThreadId());
    assertEquals(issueId.toString(), dto.getOwner().getIssueId());

    // 提交：同一点代来源的产品锁同样按根绑定解析出根 Issue 的层级。
    when(issueRepository.getById(issueId))
        .thenReturn(Issue.builder().id(issueId).projectId(projectId).build());
    when(projectRepository.lockForKeyShare(projectId)).thenReturn(mock(Project.class));
    when(issueRepository.lockById(issueId))
        .thenReturn(Issue.builder().id(issueId).projectId(projectId).build());
    ToolInputSubmissionCommand command =
        new ToolInputSubmissionCommand(
            childThreadId, id(102), id(103), "alice", false, List.of(List.of("ans")));
    when(runtime.submitToolInput(any(ToolInputSubmissionCommand.class)))
        .thenReturn(mock(ToolInputAcceptance.class));

    interactionService.submitInput(command);

    // 两个入口对同一后代各做一次同源根解析，命中同一真实根；提交按该根加产品锁。
    verify(runtime, times(2)).findAncestorChain(childThreadId);
    InOrder lockOrder = inOrder(issueRepository, projectRepository);
    lockOrder.verify(issueRepository).getById(issueId);
    lockOrder.verify(projectRepository).lockForKeyShare(projectId);
    lockOrder.verify(issueRepository).lockById(issueId);
    lockOrder.verifyNoMoreInteractions();
  }
}
