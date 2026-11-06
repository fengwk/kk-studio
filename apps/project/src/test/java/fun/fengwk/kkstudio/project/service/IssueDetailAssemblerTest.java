package fun.fengwk.kkstudio.project.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.project.domain.ProjectStateCode;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflow;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowState;
import fun.fengwk.kkstudio.project.dto.ProjectDtoMapper;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.Project;

import java.util.List;
import java.util.UUID;

class IssueDetailAssemblerTest {
  private final UUID issueId = UUID.randomUUID();
  private final UUID projectId = UUID.randomUUID();
  private final IssueEditingService issues = mock(IssueEditingService.class);
  private final IssueStageBudgetService budgets = mock(IssueStageBudgetService.class);
  private final IssueRunService runs = mock(IssueRunService.class);
  private final ProjectService projects = mock(ProjectService.class);
  private final ProjectDtoMapper mapper = mock(ProjectDtoMapper.class);
  private final ProjectWorkflowJsonCodec codec = mock(ProjectWorkflowJsonCodec.class);
  private final IssueDetailAssembler assembler =
      new IssueDetailAssembler(issues, budgets, runs, projects, mapper, codec);

  @Test
  void missingProjectIsSkippedButOtherProjectErrorsPropagate() {
    // 仅 project not-found 可跳过；其他 code 或 resource 必须原样上抛。
    when(issues.getIssue(issueId))
        .thenReturn(Issue.builder().id(issueId).projectId(projectId).build());
    doThrow(DomainException.notFound("project")).when(projects).getProject(projectId);
    assertNotNull(assembler.assemble(issueId, 0, 10));
    DomainException unrelated = DomainException.notFound("issue");
    doThrow(unrelated).when(projects).getProject(projectId);
    assertSame(
        unrelated, assertThrows(DomainException.class, () -> assembler.assemble(issueId, 0, 10)));
    DomainException conflict = DomainException.conflict("BUSY", "busy");
    doThrow(conflict).when(projects).getProject(projectId);
    assertSame(
        conflict, assertThrows(DomainException.class, () -> assembler.assemble(issueId, 0, 10)));
  }

  @Test
  void missingBudgetIsSkippedButOtherBudgetErrorsPropagate() {
    // 即使错误 resource 为 project，也不能被外层 Project 查找的 not-found 分支吞掉。
    when(issues.getIssue(issueId))
        .thenReturn(Issue.builder().id(issueId).projectId(projectId).build());
    when(projects.getProject(projectId)).thenReturn(Project.builder().workflowJson("{}").build());
    ProjectWorkflow workflow = mock(ProjectWorkflow.class);
    ProjectWorkflowState state = mock(ProjectWorkflowState.class);
    when(codec.decode("{}")).thenReturn(workflow);
    when(workflow.workStages()).thenReturn(List.of(state));
    when(state.state()).thenReturn(ProjectStateCode.of("WORK"));
    doThrow(DomainException.notFound("stage_budget")).when(budgets).getStageBudget(issueId, "WORK");
    assertNotNull(assembler.assemble(issueId, 0, 10));
    DomainException unrelated = DomainException.notFound("project");
    doThrow(unrelated).when(budgets).getStageBudget(issueId, "WORK");
    assertSame(
        unrelated, assertThrows(DomainException.class, () -> assembler.assemble(issueId, 0, 10)));
    DomainException conflict = DomainException.conflict("BUSY", "busy");
    doThrow(conflict).when(budgets).getStageBudget(issueId, "WORK");
    assertSame(
        conflict, assertThrows(DomainException.class, () -> assembler.assemble(issueId, 0, 10)));
  }
}
