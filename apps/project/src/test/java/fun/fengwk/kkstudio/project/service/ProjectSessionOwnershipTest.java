package fun.fengwk.kkstudio.project.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.orchestration.SessionOwner;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

class ProjectSessionOwnershipTest {
  @Test
  void acceptanceBindingCannotBeMovedAndRequiresExactSessionOwnership() {
    // 创建入口必须在产品锁下拒绝改绑；同一 thread 的 replay 仍需另行验证 session。
    ProjectRepository projects = mock(ProjectRepository.class);
    IssueRepository issues = mock(IssueRepository.class);
    IssueAgentThreadRepository bindings = mock(IssueAgentThreadRepository.class);
    ProjectSessionOwnership ownership = new ProjectSessionOwnership(projects, issues, bindings);
    UUID issueId = UUID.randomUUID();
    UUID projectId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UUID threadId = UUID.randomUUID();
    SessionOwner owner = ProjectSessionOwnership.owner(issueId, "agent");
    Issue issue = new Issue();
    issue.setProjectId(projectId);
    when(issues.getById(issueId)).thenReturn(issue);
    when(projects.lockForShare(projectId)).thenReturn(new Project());
    when(issues.lockById(issueId)).thenReturn(issue);
    when(bindings.findByIssueIdAndAgentName(issueId, "agent"))
        .thenReturn(new IssueAgentThread(issueId, "agent", sessionId, threadId));

    assertThrows(
        IllegalArgumentException.class,
        () -> ownership.lockForAcceptance(owner, UUID.randomUUID()));
    ownership.lockForAcceptance(owner, threadId);
    assertTrue(ownership.ownsSession(owner, sessionId));
    assertFalse(ownership.ownsSession(owner, UUID.randomUUID()));
    verify(bindings, never()).insert(any());
    var order = inOrder(projects, issues, bindings);
    order.verify(projects).lockForShare(projectId);
    order.verify(issues).lockById(issueId);
    order.verify(bindings).findByIssueIdAndAgentName(issueId, "agent");
  }

  @Test
  void bindingAndLockOrder() {
    // Existing bindings cannot be replaced and are resolved by stored session.
    ProjectRepository projects = mock(ProjectRepository.class);
    IssueRepository issues = mock(IssueRepository.class);
    IssueAgentThreadRepository bindings = mock(IssueAgentThreadRepository.class);
    ProjectSessionOwnership spi = new ProjectSessionOwnership(projects, issues, bindings);
    UUID issueId = UUID.randomUUID(), projectId = UUID.randomUUID();
    UUID session = UUID.randomUUID(), thread = UUID.randomUUID();
    SessionOwner owner = ProjectSessionOwnership.owner(issueId, "agent:variant");
    Issue issue = new Issue();
    issue.setProjectId(projectId);
    issue.setTitle("issue");
    Project project = new Project();
    IssueAgentThread binding = new IssueAgentThread(issueId, "agent:variant", session, thread);
    when(issues.getById(issueId)).thenReturn(issue);
    when(issues.lockById(issueId)).thenReturn(issue);
    when(projects.lockForShare(projectId)).thenReturn(project);
    when(projects.lockById(projectId)).thenReturn(project);
    when(projects.getById(projectId)).thenReturn(project);
    when(bindings.findByIssueIdAndAgentName(issueId, "agent:variant")).thenReturn(binding);
    when(bindings.findByThreadId(thread)).thenReturn(binding);
    assertFalse(spi.allowsFork());
    assertFalse(spi.allowsDirectCommands());
    assertThrows(
        IllegalArgumentException.class, () -> spi.lockForAcceptance(owner, UUID.randomUUID()));
    spi.lockForAcceptance(owner, thread);
    spi.lockForAcceptance(owner, null);
    assertEquals(List.of(session), spi.listSessions(owner));
    assertTrue(spi.ownsSession(owner, session));
    assertEquals(List.of(session), spi.lockForDeletion(owner));
    assertEquals(
        "/projects/" + projectId + "?issue=" + issueId,
        spi.findOwner(thread, session).orElseThrow().link());
    spi.lockForInteraction(thread, session);
    assertThrows(
        IllegalStateException.class, () -> spi.lockForInteraction(thread, UUID.randomUUID()));
    when(bindings.deleteByIssueIdAndAgentName(issueId, "agent:variant")).thenReturn(1);
    spi.beforeSessionDeletion(owner, session);
    var order = inOrder(projects, issues);
    order.verify(projects).lockForShare(projectId);
    order.verify(issues).lockById(issueId);
  }

  @Test
  void missingAndInvalidAreRejected() {
    // Missing list binding differs from an absent deletion target.
    ProjectRepository projects = mock(ProjectRepository.class);
    IssueRepository issues = mock(IssueRepository.class);
    IssueAgentThreadRepository bindings = mock(IssueAgentThreadRepository.class);
    ProjectSessionOwnership spi = new ProjectSessionOwnership(projects, issues, bindings);
    UUID issueId = UUID.randomUUID();
    SessionOwner owner = ProjectSessionOwnership.owner(issueId, "agent");
    assertEquals(List.of(), spi.lockForDeletion(owner));
    UUID projectId = UUID.randomUUID();
    Issue issue = new Issue();
    issue.setProjectId(projectId);
    when(issues.getById(issueId)).thenReturn(issue);
    when(projects.getById(projectId)).thenReturn(new Project());
    assertThrows(IllegalStateException.class, () -> spi.listSessions(owner));
    assertThrows(
        IllegalArgumentException.class,
        () -> spi.ownsSession(new SessionOwner("ISSUE_AGENT", "1-1-1-1-1:x"), UUID.randomUUID()));
    assertThrows(IllegalArgumentException.class, () -> ProjectSessionOwnership.owner(issueId, " "));
  }

  @Test
  void archivedAndDamagedOwnersFailClosed() {
    // Archived owners permit interactions but cannot accept new work.
    ProjectRepository projects = mock(ProjectRepository.class);
    IssueRepository issues = mock(IssueRepository.class);
    IssueAgentThreadRepository bindings = mock(IssueAgentThreadRepository.class);
    ProjectSessionOwnership spi = new ProjectSessionOwnership(projects, issues, bindings);
    UUID issueId = UUID.randomUUID(), projectId = UUID.randomUUID();
    UUID thread = UUID.randomUUID(), session = UUID.randomUUID();
    SessionOwner owner = ProjectSessionOwnership.owner(issueId, "agent");
    Issue issue = new Issue();
    issue.setProjectId(projectId);
    Project project = new Project();
    project.setArchivedAt(Instant.now());
    IssueAgentThread binding = new IssueAgentThread(issueId, "agent", session, thread);
    when(issues.getById(issueId)).thenReturn(issue);
    when(issues.lockById(issueId)).thenReturn(issue);
    when(projects.lockForShare(projectId)).thenReturn(project);
    when(bindings.findByThreadId(thread)).thenReturn(binding);
    assertEquals("ISSUE_AGENT", spi.kind());
    assertThrows(IllegalArgumentException.class, () -> spi.lockForAcceptance(owner, null));
    spi.lockForInteraction(thread, session);
    spi.sessionCreated(owner, session);
    assertThrows(IllegalStateException.class, () -> spi.findOwner(thread, session));
    assertThrows(IllegalStateException.class, () -> spi.beforeSessionDeletion(owner, session));
    when(projects.lockForShare(projectId)).thenReturn(null);
    assertThrows(IllegalStateException.class, () -> spi.lockForInteraction(thread, session));
    when(projects.lockForShare(projectId)).thenReturn(project);
    when(issues.lockById(issueId)).thenReturn(null);
    assertThrows(IllegalStateException.class, () -> spi.lockForInteraction(thread, session));
    when(issues.getById(issueId)).thenReturn(null);
    assertThrows(IllegalArgumentException.class, () -> spi.lockForAcceptance(owner, null));
    assertThrows(IllegalStateException.class, () -> spi.findOwner(thread, session));
    when(bindings.findByThreadId(thread)).thenReturn(null);
    assertTrue(spi.findOwner(thread, session).isEmpty());
    spi.lockForInteraction(thread, session);
    assertThrows(
        IllegalArgumentException.class,
        () -> spi.ownsSession(new SessionOwner("OTHER", "x"), session));
  }
}
