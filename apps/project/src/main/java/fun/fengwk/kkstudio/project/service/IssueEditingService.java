package fun.fengwk.kkstudio.project.service;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.project.domain.ProjectWorkflowReservedState;
import fun.fengwk.kkstudio.project.error.ProjectNotFoundException;
import fun.fengwk.kkstudio.project.error.ProjectValidationException;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueActivity;
import fun.fengwk.kkstudio.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.project.model.IssueActivityKind;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.IssueRun;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.project.service.IssueActivityIdempotency.Identity;
import fun.fengwk.kkstudio.work.DurableWorkStore;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Issue 编辑用例：创建、查询、标题/描述编辑、评论、指示与活动/线程读取。
 *
 * <p>写路径复用 {@link IssueMutationSupport} 的 Project SHARE → Issue UPDATE 锁序与幂等
 * receipt；状态迁移、门禁和阶段预算由相邻用例负责。
 */
@Service
@AllArgsConstructor
public class IssueEditingService {

  /** 单次活动窗口上限：读取面与投递面都只检视有界窗口，绝不无界加载整条事实流。 */
  private static final int MAX_ACTIVITY_LIMIT = 200;

  private final ProjectRepository projectRepository;
  private final IssueRepository issueRepository;
  private final IssueRunRepository issueRunRepository;
  private final IssueActivityRepository issueActivityRepository;
  private final IssueAgentThreadRepository issueAgentThreadRepository;
  private final DurableWorkStore issueWorkQueue;
  private final IssueMutationSupport mutationSupport;

  @Transactional
  public Issue createIssue(UUID projectId, String title, String description) {
    Objects.requireNonNull(projectId, "projectId");
    Project project = projectRepository.getById(projectId);
    if (project == null) {
      throw new ProjectNotFoundException("project");
    }
    if (project.isArchived()) {
      throw new ProjectValidationException(
          "project", "Cannot create an issue in an archived project");
    }
    String validTitle =
        ProjectValidationUtils.requireDisplayName(
            title, "title", ProjectValidationUtils.MAX_TITLE_LENGTH);
    String validDescription =
        ProjectValidationUtils.optionalUtf8Text(
            description == null ? "" : description,
            "description",
            ProjectValidationUtils.MAX_LARGE_TEXT_BYTES);
    long number = projectRepository.allocateNextIssueNumber(projectId);
    Issue issue =
        Issue.builder()
            .id(UUID.randomUUID())
            .projectId(projectId)
            .number(number)
            .title(validTitle)
            .description(validDescription)
            .state(ProjectWorkflowReservedState.INIT.code().value())
            .nextRunOrdinal(1)
            .nextActivitySequence(1)
            .build();
    if (!issueRepository.create(issue)) {
      throw new IllegalStateException("failed to insert issue");
    }
    return issueRepository.getById(issue.getId());
  }

  public Issue getIssue(UUID issueId) {
    return mutationSupport.currentIssue(issueId);
  }

  public List<Issue> listIssues(UUID projectId, boolean archived) {
    Objects.requireNonNull(projectId, "projectId");
    return issueRepository.listByProjectIdAndArchived(projectId, archived);
  }

  /** 编辑 Issue 需求事实（标题与描述）；当前阶段与执行事实不由本操作改变。 */
  @Transactional
  public Issue updateIssue(UUID issueId, long expectedVersion, String title, String description) {
    Objects.requireNonNull(issueId, "issueId");
    IssueMutationSupport.Locked locked = mutationSupport.lock(issueId, expectedVersion, false);
    locked
        .issue()
        .setTitle(
            ProjectValidationUtils.requireDisplayName(
                title, "title", ProjectValidationUtils.MAX_TITLE_LENGTH));
    locked
        .issue()
        .setDescription(
            ProjectValidationUtils.optionalUtf8Text(
                description == null ? "" : description,
                "description",
                ProjectValidationUtils.MAX_LARGE_TEXT_BYTES));
    return mutationSupport.persist(locked, expectedVersion);
  }

  /** 追加普通评论：只留痕，不自动唤醒 Agent。 */
  @Transactional
  public Issue appendComment(UUID issueId, long expectedVersion, String requestKey, String body) {
    Objects.requireNonNull(issueId, "issueId");
    String key = ProjectValidationUtils.requireRequestKey(requestKey);
    String validBody =
        ProjectValidationUtils.requireUtf8Text(
            body, "body", ProjectValidationUtils.MAX_LARGE_TEXT_BYTES);
    Identity identity =
        IssueActivityIdempotency.identity(IssueActivityKind.COMMENT, "COMMENT", key, validBody);
    if (mutationSupport.replayed(issueId, identity)) {
      return mutationSupport.currentIssue(issueId);
    }
    IssueMutationSupport.Locked locked = mutationSupport.lockOwner(issueId);
    if (mutationSupport.replayedUnderLock(locked.issue(), identity)) {
      return locked.issue();
    }
    mutationSupport.requireMutable(locked, false);
    mutationSupport.requireVersion(locked, expectedVersion);
    mutationSupport.appendActivity(
        locked.issue(),
        identity,
        IssueActivityActorType.HUMAN,
        null,
        null,
        validBody,
        mutationSupport.newData());
    mutationSupport.persist(locked, expectedVersion);
    // 普通评论不自动唤醒 Agent（设计 §4.6）。
    return mutationSupport.currentIssue(issueId);
  }

  /** 追加定向指示：必须投递给明确的当前 Run；没有活动 Run 时先明确启动或恢复，绝不构造隐藏的未来阶段队列。 */
  @Transactional
  public Issue appendInstruction(
      UUID issueId, long expectedVersion, String requestKey, String body) {
    Objects.requireNonNull(issueId, "issueId");
    String key = ProjectValidationUtils.requireRequestKey(requestKey);
    String validBody =
        ProjectValidationUtils.requireUtf8Text(
            body, "body", ProjectValidationUtils.MAX_LARGE_TEXT_BYTES);
    Identity identity =
        IssueActivityIdempotency.identity(
            IssueActivityKind.INSTRUCTION, "INSTRUCTION", key, validBody);
    if (mutationSupport.replayed(issueId, identity)) {
      return mutationSupport.currentIssue(issueId);
    }
    IssueMutationSupport.Locked locked = mutationSupport.lockOwner(issueId);
    if (mutationSupport.replayedUnderLock(locked.issue(), identity)) {
      return locked.issue();
    }
    mutationSupport.requireMutable(locked, false);
    mutationSupport.requireVersion(locked, expectedVersion);
    IssueRun activeRun = issueRunRepository.lockActiveByIssueId(issueId);
    if (activeRun == null) {
      // 没有活动 Run 时不构造隐藏的未来阶段消息队列：先明确启动或恢复。
      throw new ProjectValidationException(
          "issue",
          "Cannot deliver an instruction without an active run; start or resume the issue first");
    }
    mutationSupport.appendActivity(
        locked.issue(),
        identity,
        IssueActivityActorType.HUMAN,
        null,
        activeRun.getId(),
        validBody,
        mutationSupport.newData());
    mutationSupport.persist(locked, expectedVersion);
    // 指示已持久接受，由 mailbox 在安全点投递给明确的当前 Run。
    issueWorkQueue.request(issueId, Duration.ZERO);
    return mutationSupport.currentIssue(issueId);
  }

  /** 读取 Issue 事实流的有界窗口，按序号升序。 */
  public List<IssueActivity> listActivities(UUID issueId, long afterSequence, int limit) {
    Objects.requireNonNull(issueId, "issueId");
    getIssue(issueId);
    if (afterSequence < 0) {
      throw new ProjectValidationException("afterSequence", "afterSequence must be non-negative");
    }
    return issueActivityRepository.listPage(issueId, afterSequence, clampLimit(limit));
  }

  /** 读取 Issue 的稳定 Agent Thread 归属；绑定不可重绑，因此只需返回事实。 */
  public List<IssueAgentThread> listAgentThreads(UUID issueId) {
    Objects.requireNonNull(issueId, "issueId");
    getIssue(issueId);
    return issueAgentThreadRepository.listByIssueId(issueId);
  }

  private static int clampLimit(int limit) {
    if (limit <= 0) {
      throw new ProjectValidationException("limit", "limit must be positive");
    }
    return Math.min(limit, MAX_ACTIVITY_LIMIT);
  }
}
