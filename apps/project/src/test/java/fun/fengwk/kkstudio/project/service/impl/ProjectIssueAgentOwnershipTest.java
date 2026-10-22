package fun.fengwk.kkstudio.project.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import fun.fengwk.kkstudio.platform.orchestration.IssueAgentOwnership;
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** {@link ProjectIssueAgentOwnership} 的锁序、层级校验与绑定读写。 */
class ProjectIssueAgentOwnershipTest {

  private static final UUID PROJECT_ID = new UUID(0L, 10);
  private static final UUID ISSUE_ID = new UUID(0L, 11);
  private static final UUID THREAD_ID = new UUID(0L, 12);
  private static final String AGENT = "executor";
  private static final OwnerRef.IssueAgent OWNER = new OwnerRef.IssueAgent(ISSUE_ID, AGENT);
  private static final Instant ARCHIVED_AT = Instant.parse("2026-01-01T00:00:00Z");

  private ProjectRepository projectRepository;
  private IssueRepository issueRepository;
  private IssueAgentThreadRepository bindings;
  private ProjectIssueAgentOwnership ownership;

  @BeforeEach
  void setUp() {
    projectRepository = mock(ProjectRepository.class);
    issueRepository = mock(IssueRepository.class);
    bindings = mock(IssueAgentThreadRepository.class);
    ownership = new ProjectIssueAgentOwnership(projectRepository, issueRepository, bindings);

    Project project = Project.builder().id(PROJECT_ID).build();
    when(projectRepository.lockForKeyShare(PROJECT_ID)).thenReturn(project);
    when(projectRepository.lockById(PROJECT_ID)).thenReturn(project);
    when(issueRepository.getById(ISSUE_ID)).thenReturn(issue(PROJECT_ID, null));
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(issue(PROJECT_ID, null));
    when(bindings.findByIssueIdAndAgentName(ISSUE_ID, AGENT))
        .thenReturn(new IssueAgentThread(ISSUE_ID, AGENT, THREAD_ID));
    when(bindings.findByThreadId(THREAD_ID))
        .thenReturn(new IssueAgentThread(ISSUE_ID, AGENT, THREAD_ID));
  }

  /** 接受路径严格按 Project KEY SHARE -> Issue UPDATE -> 稳定绑定读取的顺序执行，并返回绑定 Thread。 */
  @Test
  void acceptanceLocksProjectThenIssueThenReadsBinding() {
    assertEquals(Optional.of(THREAD_ID), ownership.lockForAcceptance(OWNER));

    InOrder order = inOrder(projectRepository, issueRepository, bindings);
    order.verify(projectRepository).lockForKeyShare(PROJECT_ID);
    order.verify(issueRepository).lockById(ISSUE_ID);
    order.verify(bindings).findByIssueIdAndAgentName(ISSUE_ID, AGENT);
  }

  /** 尚未完成首次接受的身份没有绑定：接受路径返回空，由平台只允许全新 NEW_SESSION。 */
  @Test
  void acceptanceOfUnboundIdentityReturnsEmpty() {
    when(bindings.findByIssueIdAndAgentName(ISSUE_ID, AGENT)).thenReturn(null);

    assertEquals(Optional.empty(), ownership.lockForAcceptance(OWNER));
  }

  /** owner 行缺失、层级不一致或已归档时接受路径确定性拒绝。 */
  @Test
  void acceptanceRejectsMissingArchivedOrInconsistentHierarchy() {
    when(issueRepository.getById(ISSUE_ID)).thenReturn(null);
    assertRejected("Issue owner does not exist");

    when(issueRepository.getById(ISSUE_ID)).thenReturn(issue(PROJECT_ID, null));
    when(projectRepository.lockForKeyShare(PROJECT_ID)).thenReturn(null);
    assertRejected("Project owner does not exist");

    when(projectRepository.lockForKeyShare(PROJECT_ID))
        .thenReturn(Project.builder().id(PROJECT_ID).archivedAt(ARCHIVED_AT).build());
    assertRejected("Cannot accept commands for archived project");

    when(projectRepository.lockForKeyShare(PROJECT_ID))
        .thenReturn(Project.builder().id(PROJECT_ID).build());
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(issue(new UUID(0L, 99), null));
    assertRejected("Issue owner hierarchy is inconsistent");

    when(issueRepository.lockById(ISSUE_ID)).thenReturn(null);
    assertRejected("Issue owner hierarchy is inconsistent");

    when(issueRepository.lockById(ISSUE_ID)).thenReturn(issue(PROJECT_ID, ARCHIVED_AT));
    assertRejected("Cannot accept commands for archived issue");
  }

  /** 深删除路径用排他锁：Project -> Issue -> 绑定；owner 缺失或未绑定时没有可删除的归属。 */
  @Test
  void deletionLocksExclusivelyAndTreatsMissingOwnerAsNothingToDelete() {
    assertEquals(Optional.of(THREAD_ID), ownership.lockForDeletion(OWNER));
    InOrder order = inOrder(projectRepository, issueRepository, bindings);
    order.verify(projectRepository).lockById(PROJECT_ID);
    order.verify(issueRepository).lockById(ISSUE_ID);
    order.verify(bindings).findByIssueIdAndAgentName(ISSUE_ID, AGENT);

    when(bindings.findByIssueIdAndAgentName(ISSUE_ID, AGENT)).thenReturn(null);
    assertEquals(Optional.empty(), ownership.lockForDeletion(OWNER));

    when(issueRepository.lockById(ISSUE_ID)).thenReturn(null);
    assertEquals(Optional.empty(), ownership.lockForDeletion(OWNER));

    when(projectRepository.lockById(PROJECT_ID)).thenReturn(null);
    assertEquals(Optional.empty(), ownership.lockForDeletion(OWNER));

    when(issueRepository.getById(ISSUE_ID)).thenReturn(null);
    assertEquals(Optional.empty(), ownership.lockForDeletion(OWNER));
  }

  /** 绑定读取不加锁；删除只删绑定行。 */
  @Test
  void readsAndDeletesBindingWithoutLocking() {
    assertEquals(Optional.of(THREAD_ID), ownership.findBoundThread(OWNER));
    ownership.deleteBinding(OWNER);

    verify(bindings).deleteByIssueIdAndAgentName(ISSUE_ID, AGENT);
    verify(projectRepository, never()).lockForKeyShare(any());
    verify(issueRepository, never()).lockById(any());
  }

  /** 交互路径：根有 Issue 绑定时按 Project KEY SHARE -> Issue UPDATE 加锁；暂停或归档不阻止人工回答被记录。 */
  @Test
  void interactionLocksBoundRootHierarchyEvenWhenArchived() {
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(issue(PROJECT_ID, ARCHIVED_AT));

    ownership.lockRootForInteraction(THREAD_ID);

    InOrder order = inOrder(issueRepository, projectRepository);
    order.verify(issueRepository).getById(ISSUE_ID);
    order.verify(projectRepository).lockForKeyShare(PROJECT_ID);
    order.verify(issueRepository).lockById(ISSUE_ID);
  }

  /** 交互路径：根没有 Issue 归属（Chat 或内部委派）时不需要产品锁。 */
  @Test
  void interactionOnUnboundRootTakesNoProductLock() {
    UUID chatRoot = new UUID(0L, 77);

    ownership.lockRootForInteraction(chatRoot);

    verifyNoInteractions(projectRepository, issueRepository);
  }

  /** 绑定仍存在但 Project/Issue 缺失或层级不一致属于归属事实损坏：fail closed，而不是退化成无产品锁的写入。 */
  @Test
  void interactionFailsClosedOnCorruptOwnerHierarchy() {
    when(issueRepository.getById(ISSUE_ID)).thenReturn(null);
    assertCorrupt("Issue owner does not exist");

    when(issueRepository.getById(ISSUE_ID)).thenReturn(issue(PROJECT_ID, null));
    when(projectRepository.lockForKeyShare(PROJECT_ID)).thenReturn(null);
    assertCorrupt("Project owner does not exist");

    when(projectRepository.lockForKeyShare(PROJECT_ID))
        .thenReturn(Project.builder().id(PROJECT_ID).build());
    when(issueRepository.lockById(ISSUE_ID)).thenReturn(null);
    assertCorrupt("Issue owner hierarchy is inconsistent");

    when(issueRepository.lockById(ISSUE_ID)).thenReturn(issue(new UUID(0L, 99), null));
    assertCorrupt("Issue owner hierarchy is inconsistent");
  }

  /** 交互查询投影根的 Issue 归属与标题；没有绑定的根没有 Issue 归属。 */
  @Test
  void projectsBoundRootWithIssueTitle() {
    when(issueRepository.getById(ISSUE_ID))
        .thenReturn(Issue.builder().id(ISSUE_ID).projectId(PROJECT_ID).title("fix it").build());

    assertEquals(
        Optional.of(new IssueAgentOwnership.BoundRoot(ISSUE_ID, AGENT, "fix it")),
        ownership.findBoundRoot(THREAD_ID));
    assertEquals(Optional.empty(), ownership.findBoundRoot(new UUID(0L, 77)));
  }

  private void assertRejected(String message) {
    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> ownership.lockForAcceptance(OWNER));
    assertEquals(message, error.getMessage());
  }

  private void assertCorrupt(String message) {
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> ownership.lockRootForInteraction(THREAD_ID));
    assertEquals(message, error.getMessage());
  }

  private static Issue issue(UUID projectId, Instant archivedAt) {
    return Issue.builder().id(ISSUE_ID).projectId(projectId).archivedAt(archivedAt).build();
  }
}
