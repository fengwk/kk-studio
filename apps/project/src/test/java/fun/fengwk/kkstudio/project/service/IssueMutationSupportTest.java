package fun.fengwk.kkstudio.project.service;

import static fun.fengwk.kkstudio.project.ProjectTestAssertions.assertNotFound;
import static fun.fengwk.kkstudio.project.ProjectTestAssertions.assertValidation;
import static fun.fengwk.kkstudio.project.ProjectTestAssertions.assertVersionConflict;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.work.DurableWorkStore;

import java.util.UUID;

class IssueMutationSupportTest {

  private final UUID issueId = UUID.randomUUID();
  private final UUID projectId = UUID.randomUUID();
  private final ProjectRepository projects = mock(ProjectRepository.class);
  private final IssueRepository issues = mock(IssueRepository.class);
  private final IssueActivityRepository activities = mock(IssueActivityRepository.class);
  private final DurableWorkStore work = mock(DurableWorkStore.class);
  private final IssueMutationSupport support =
      new IssueMutationSupport(
          projects,
          issues,
          activities,
          work,
          new ProjectWorkflowJsonCodec(),
          JsonMapper.builder().build());

  /** owner 消失时按 Project→Issue 锁序失败，不得进入写路径。 */
  @Test
  void missingOwnerFailsBeforeAnyWrite() {
    Issue initial = Issue.builder().id(issueId).projectId(projectId).build();
    when(issues.getById(issueId)).thenReturn(initial);
    assertNotFound(() -> support.lockOwner(issueId));
    verify(issues, never()).lockById(any());

    Project project = Project.builder().id(projectId).build();
    when(projects.lockForKeyShare(projectId)).thenReturn(project);
    assertNotFound(() -> support.lockOwner(issueId));
    InOrder order = inOrder(issues, projects);
    order.verify(issues).getById(issueId);
    order.verify(projects).lockForKeyShare(projectId);
    order.verify(issues).getById(issueId);
    order.verify(projects).lockForKeyShare(projectId);
    order.verify(issues).lockById(issueId);
    verify(issues, never()).updateById(any(), anyLong());
    verify(activities, never()).insert(any());
  }

  /** 锁后归属变化不能使用错误项目工作流或写入 Issue。 */
  @Test
  void changedHierarchyFailsClosedAfterOrderedLocks() {
    Issue initial = Issue.builder().id(issueId).projectId(projectId).build();
    Issue moved = Issue.builder().id(issueId).projectId(UUID.randomUUID()).build();
    when(issues.getById(issueId)).thenReturn(initial);
    when(projects.lockForKeyShare(projectId)).thenReturn(Project.builder().id(projectId).build());
    when(issues.lockById(issueId)).thenReturn(moved);

    DomainException error = assertValidation(() -> support.lockOwner(issueId));
    assertTrue(error.getMessage().contains("hierarchy"));
    InOrder order = inOrder(issues, projects);
    order.verify(issues).getById(issueId);
    order.verify(projects).lockForKeyShare(projectId);
    order.verify(issues).lockById(issueId);
    verify(issues, never()).updateById(any(), anyLong());
    verify(activities, never()).insert(any());
  }

  /** CAS 失败不能返回看似成功的当前记录。 */
  @Test
  void failedCompareAndSetDoesNotReadSuccessResult() {
    Issue issue = Issue.builder().id(issueId).projectId(projectId).version(4).build();
    IssueMutationSupport.Locked locked = new IssueMutationSupport.Locked(issue, null, null);
    when(issues.updateById(issue, 4)).thenReturn(false);

    assertVersionConflict(() -> support.persist(locked, 4));
    verify(issues).updateById(issue, 4);
    verify(issues, never()).getById(any());
  }

  /** writeJson 正常序列化并在 Jackson 异常时包装为 IllegalStateException。 */
  @Test
  void writeJsonSerializesAndWrapsJacksonException() {
    ObjectNode node = support.newData();
    node.put("key", "value");
    assertEquals("{\"key\":\"value\"}", support.writeJson(node));

    ObjectMapper failingMapper = mock(ObjectMapper.class);
    when(failingMapper.writeValueAsString(any())).thenThrow(mock(JacksonException.class));
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class, () -> IssueMutationSupport.writeJson(failingMapper, node));
    assertEquals("failed to encode activity data", error.getMessage());
  }
}
