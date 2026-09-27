package fun.fengwk.kkstudio.platform.project.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.orchestration.OwnerRef;
import fun.fengwk.kkstudio.platform.orchestration.SessionDeletionOrchestrator;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.platform.project.model.PauseReason;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueStageBudgetRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueWorkRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueEvidenceService;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;
import fun.fengwk.kkstudio.project.domain.ProjectStateCode;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflow;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowReservedState;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowState;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Project 配置用例实现：所有写路径先持有 Project 行锁，再以版本 CAS 提交。
 *
 * <p>锁序与设计 §7.1 一致（Project 独占锁下改配置）；配置更新前的活动主 Run 检查只读取项目自身下级 Issue，绝不跨产品写入。
 */
@Service
@AllArgsConstructor
public class ProjectServiceImpl implements ProjectService {

  /** 创建项目时提供的默认流程：INIT → WORK（人工阶段）→ DONE。 */
  private static final ProjectWorkflow DEFAULT_WORKFLOW =
      new ProjectWorkflow(
          List.of(
              new ProjectWorkflowState(
                  ProjectStateCode.of("INIT"),
                  "待开始",
                  null,
                  null,
                  null,
                  null,
                  true,
                  List.of(ProjectStateCode.of("WORK"))),
              new ProjectWorkflowState(
                  ProjectStateCode.of("WORK"),
                  "处理中",
                  null,
                  null,
                  null,
                  null,
                  true,
                  List.of(ProjectStateCode.of("DONE"))),
              new ProjectWorkflowState(
                  ProjectWorkflowReservedState.BLOCKED.code(),
                  "业务阻塞",
                  null,
                  null,
                  null,
                  null,
                  true,
                  List.of()),
              new ProjectWorkflowState(
                  ProjectWorkflowReservedState.DONE.code(),
                  "完成",
                  null,
                  null,
                  null,
                  null,
                  true,
                  List.of())));

  private final ProjectRepository projectRepository;
  private final IssueRepository issueRepository;
  private final IssueRunRepository issueRunRepository;
  private final IssueEvidenceService issueEvidenceService;
  private final IssueActivityRepository issueActivityRepository;
  private final IssueWorkRepository issueWorkRepository;
  private final IssueStageBudgetRepository issueStageBudgetRepository;
  private final IssueAgentThreadRepository issueAgentThreadRepository;
  private final SessionDeletionOrchestrator sessionDeletionOrchestrator;
  private final ProjectWorkflowJsonCodec workflowCodec;

  @Override
  @Transactional
  public Project createProject(String title, String description, boolean yoloEnabled) {
    String validTitle =
        ProjectValidationUtils.requireDisplayName(
            title, "title", ProjectValidationUtils.MAX_TITLE_LENGTH);
    String validDescription =
        ProjectValidationUtils.optionalUtf8Text(
            description == null ? "" : description,
            "description",
            ProjectValidationUtils.MAX_DESCRIPTION_BYTES);
    Project project =
        Project.builder()
            .id(UUID.randomUUID())
            .title(validTitle)
            .description(validDescription)
            .workflowJson(workflowCodec.encode(DEFAULT_WORKFLOW))
            .yoloEnabled(yoloEnabled)
            .build();
    if (!projectRepository.create(project)) {
      throw new IllegalStateException("failed to insert project");
    }
    return projectRepository.getById(project.getId());
  }

  @Override
  public Project getProject(UUID projectId) {
    Objects.requireNonNull(projectId, "projectId");
    Project project = projectRepository.getById(projectId);
    if (project == null) {
      throw new AiResourceNotFoundException("project");
    }
    return project;
  }

  @Override
  public List<Project> listProjects(boolean archived) {
    return projectRepository.listByArchived(archived);
  }

  @Override
  @Transactional
  public Project updateConfiguration(
      UUID projectId, long expectedVersion, String title, String description) {
    Objects.requireNonNull(projectId, "projectId");
    Project locked = lockProject(projectId);
    requireEditable(locked);
    requireVersion(locked, expectedVersion);
    locked.setTitle(
        ProjectValidationUtils.requireDisplayName(
            title, "title", ProjectValidationUtils.MAX_TITLE_LENGTH));
    locked.setDescription(
        ProjectValidationUtils.optionalUtf8Text(
            description == null ? "" : description,
            "description",
            ProjectValidationUtils.MAX_DESCRIPTION_BYTES));
    return applyConfiguration(locked, expectedVersion);
  }

  @Override
  @Transactional
  public Project updateWorkflow(UUID projectId, long expectedVersion, String workflowJson) {
    Objects.requireNonNull(projectId, "projectId");
    Project locked = lockProject(projectId);
    requireEditable(locked);
    requireVersion(locked, expectedVersion);
    requireNoActiveRun(projectId);
    ProjectWorkflow workflow = decodeWorkflow(workflowJson);
    locked.setWorkflowJson(workflowCodec.encode(workflow));
    return applyConfiguration(locked, expectedVersion);
  }

  @Override
  @Transactional
  public Project updateYolo(UUID projectId, long expectedVersion, boolean yoloEnabled) {
    Objects.requireNonNull(projectId, "projectId");
    Project locked = lockProject(projectId);
    requireEditable(locked);
    requireVersion(locked, expectedVersion);
    locked.setYoloEnabled(yoloEnabled);
    return applyConfiguration(locked, expectedVersion);
  }

  @Override
  @Transactional
  public Project archiveProject(UUID projectId, long expectedVersion) {
    Objects.requireNonNull(projectId, "projectId");
    Project locked = lockProject(projectId);
    requireVersion(locked, expectedVersion);
    requireNoActiveRun(projectId);
    if (locked.isArchived()) {
      // 缺少响应后的重试：归档已经是当前事实，不重复写行。
      return locked;
    }
    if (!projectRepository.updateArchivedAt(projectId, Instant.now(), expectedVersion)) {
      throw conflict(locked, expectedVersion);
    }
    return projectRepository.getById(projectId);
  }

  @Override
  @Transactional
  public Project unarchiveProject(UUID projectId, long expectedVersion) {
    Objects.requireNonNull(projectId, "projectId");
    Project locked = lockProject(projectId);
    requireVersion(locked, expectedVersion);
    if (!locked.isArchived()) {
      // 缺少响应后的重试：已经是可编辑状态，不重复写行。
      return locked;
    }
    if (!projectRepository.updateArchivedAt(projectId, null, expectedVersion)) {
      throw conflict(locked, expectedVersion);
    }
    return projectRepository.getById(projectId);
  }

  @Override
  @Transactional
  public void deleteProject(UUID projectId, long expectedVersion) {
    Objects.requireNonNull(projectId, "projectId");
    Project locked = lockProject(projectId);
    requireVersion(locked, expectedVersion);
    List<Issue> issues = issueRepository.listByProjectId(projectId);
    for (Issue issue : issues) {
      if (issueRunRepository.lockActiveByIssueId(issue.getId()) != null) {
        throw new AiValidationException(
            "project",
            "Cannot delete a project while an issue has an active run; stop or settle it first");
      }
      if (PauseReason.UNKNOWN.name().equals(issue.getPauseReason())) {
        throw new AiValidationException(
            "project",
            "Cannot delete a project while an issue has an unresolved UNKNOWN gate; resolve unknown verification first");
      }
    }
    for (Issue issue : issues) {
      issueEvidenceService.releaseAll(issue.getId());
      issueActivityRepository.deleteByIssueId(issue.getId());
      issueWorkRepository.deleteByIssueId(issue.getId());
      issueRunRepository.deleteByIssueId(issue.getId());
      issueStageBudgetRepository.deleteByIssueId(issue.getId());
      List<IssueAgentThread> bindings = issueAgentThreadRepository.listByIssueId(issue.getId());
      for (IssueAgentThread binding : bindings) {
        sessionDeletionOrchestrator.deleteSessionsByOwner(
            new OwnerRef.IssueAgent(issue.getId(), binding.agentName()));
      }
      if (!issueRepository.deleteById(issue.getId(), issue.getVersion())) {
        throw new AiVersionConflictException(
            "issue", Long.toString(issue.getVersion()), Long.toString(issue.getVersion()));
      }
    }
    if (!projectRepository.deleteById(projectId, expectedVersion)) {
      throw conflict(locked, expectedVersion);
    }
  }

  private ProjectWorkflow decodeWorkflow(String workflowJson) {
    String json =
        ProjectValidationUtils.requireUtf8Text(
            workflowJson, "workflow", ProjectValidationUtils.MAX_LARGE_TEXT_BYTES);
    try {
      return workflowCodec.decode(json);
    } catch (IllegalArgumentException error) {
      throw ProjectValidationUtils.validation("workflow", error);
    }
  }

  private Project applyConfiguration(Project locked, long expectedVersion) {
    if (!projectRepository.updateConfiguration(locked, expectedVersion)) {
      throw conflict(locked, expectedVersion);
    }
    return projectRepository.getById(locked.getId());
  }

  private Project lockProject(UUID projectId) {
    Project locked = projectRepository.lockById(projectId);
    if (locked == null) {
      throw new AiResourceNotFoundException("project");
    }
    return locked;
  }

  private void requireVersion(Project locked, long expectedVersion) {
    if (locked.getVersion() != expectedVersion) {
      throw conflict(locked, expectedVersion);
    }
  }

  /** 归档项目禁止任何配置修改；先恢复归档再编辑，避免归档状态下的隐式改动。 */
  private static void requireEditable(Project locked) {
    if (locked.isArchived()) {
      throw new AiValidationException("project", "Cannot modify an archived project");
    }
  }

  private void requireNoActiveRun(UUID projectId) {
    for (Issue issue : issueRepository.listByProjectId(projectId)) {
      if (issueRunRepository.lockActiveByIssueId(issue.getId()) == null) {
        continue;
      }
      throw new AiValidationException(
          "project", "Cannot change project configuration while an issue has an active run");
    }
  }

  private static AiVersionConflictException conflict(Project locked, long expectedVersion) {
    return new AiVersionConflictException(
        "project", Long.toString(expectedVersion), Long.toString(locked.getVersion()));
  }
}
