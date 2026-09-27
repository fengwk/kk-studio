package fun.fengwk.kkstudio.platform.project.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
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
  public Project updateWorkflow(UUID projectId, long expectedVersion, String workflowJson) {
    Objects.requireNonNull(projectId, "projectId");
    Project locked = lockProject(projectId);
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
    if (!projectRepository.updateArchivedAt(projectId, Instant.now(), expectedVersion)) {
      throw conflict(locked, expectedVersion);
    }
    return projectRepository.getById(projectId);
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
