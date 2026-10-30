package fun.fengwk.kkstudio.project.service;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.project.domain.ProjectStateCode;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflow;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowReservedState;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowState;
import fun.fengwk.kkstudio.project.error.ProjectNotFoundException;
import fun.fengwk.kkstudio.project.error.ProjectValidationException;
import fun.fengwk.kkstudio.project.error.ProjectVersionConflictException;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueActivity;
import fun.fengwk.kkstudio.project.model.IssueActivityActorType;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.repo.IssueActivityRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.project.service.IssueActivityIdempotency.Identity;
import fun.fengwk.kkstudio.work.DurableWorkQueue;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Issue 写用例共用的 owner 锁、幂等 receipt 与事务内落库逻辑。
 *
 * <p>只集中三种 Issue 写用例重复的加锁顺序、归档/版本门禁、活动追加与 Work 唤醒；不承载具体动作、状态迁移或额度规则。调用方必须在自己的
 * {@code @Transactional} 边界内使用，本类不代理事务。
 */
@Component
@AllArgsConstructor
final class IssueMutationSupport {

  private final ProjectRepository projectRepository;
  private final IssueRepository issueRepository;
  private final IssueActivityRepository issueActivityRepository;
  private final DurableWorkQueue issueWorkQueue;
  private final ProjectWorkflowJsonCodec workflowCodec;
  private final ObjectMapper objectMapper;

  /**
   * 锁前快速路径：命中同键同指纹时跳过加锁与业务校验。
   *
   * <p>未命中不能作为最终结论；调用方必须在 {@link #lockOwner(UUID)} 之后再次判定。
   */
  boolean replayed(UUID issueId, Identity identity) {
    currentIssue(issueId);
    return IssueActivityIdempotency.findApplied(issueActivityRepository, issueId, identity) != null;
  }

  /** owner 锁内的精确重试判定。 */
  boolean replayedUnderLock(Issue issue, Identity identity) {
    return IssueActivityIdempotency.findApplied(issueActivityRepository, issue.getId(), identity)
        != null;
  }

  /** 锁前重放返回的权威当前事实：重新读取已提交的行，不复用一级缓存快照。 */
  Issue currentIssue(UUID issueId) {
    Objects.requireNonNull(issueId, "issueId");
    Issue issue = issueRepository.getById(issueId);
    if (issue == null) {
      throw new ProjectNotFoundException("issue");
    }
    return issue;
  }

  /**
   * 以 Project FOR KEY SHARE → Issue FOR UPDATE 的固定锁序加锁，并做版本与归属校验。
   *
   * <p>没有活动 receipt 的写路径使用本方法。带请求键的路径必须先 {@link #lockOwner(UUID)}，在锁内查 receipt，再做版本校验。
   */
  Locked lock(UUID issueId, long expectedVersion, boolean allowArchivedIssue) {
    Locked locked = lockOwner(issueId);
    requireMutable(locked, allowArchivedIssue);
    requireVersion(locked, expectedVersion);
    return locked;
  }

  /**
   * 只取得 owner 锁与归属校验。
   *
   * <p>归档只拒绝首次写入。带 receipt 的路径必须先重放，再调用 {@link #requireMutable}。
   */
  Locked lockOwner(UUID issueId) {
    Objects.requireNonNull(issueId, "issueId");
    Issue initial = currentIssue(issueId);
    Project project = projectRepository.lockForKeyShare(initial.getProjectId());
    if (project == null) {
      throw new ProjectNotFoundException("project");
    }
    Issue issue = issueRepository.lockById(issueId);
    if (issue == null) {
      throw new ProjectNotFoundException("issue");
    }
    if (!issue.getProjectId().equals(project.getId())) {
      throw new ProjectValidationException("issue", "Issue hierarchy is inconsistent");
    }
    return new Locked(issue, project, workflowCodec.decode(project.getWorkflowJson()));
  }

  /** 首次写入前的归档门禁：项目或 Issue 已归档时拒绝；精确重放不经过这里。 */
  void requireMutable(Locked locked, boolean allowArchivedIssue) {
    if (locked.project().isArchived()) {
      throw new ProjectValidationException(
          "project", "Cannot modify an issue in an archived project");
    }
    if (locked.issue().isArchived() && !allowArchivedIssue) {
      throw new ProjectValidationException("issue", "Cannot modify an archived issue");
    }
  }

  void requireVersion(Locked locked, long expectedVersion) {
    if (locked.issue().getVersion() != expectedVersion) {
      throw new ProjectVersionConflictException(
          "issue", Long.toString(expectedVersion), Long.toString(locked.issue().getVersion()));
    }
  }

  void appendStateChange(Issue issue, Identity identity, String action, String from, String to) {
    ObjectNode data = newData();
    data.put("action", action);
    data.put("from", from);
    data.put("to", to);
    appendActivity(issue, identity, IssueActivityActorType.SYSTEM, null, null, null, data);
  }

  /**
   * 追加一条活动：请求键与指纹来自调用方身份，正文与 Run 引用按动作填写。
   *
   * <p>调用方必须在 owner 锁内、版本与状态校验之前完成精确重试判定；本方法只在首次执行路径调用。写完活动后必须在同一事务内以版本 CAS 写回 Issue 行。
   */
  void appendActivity(
      Issue issue,
      Identity identity,
      IssueActivityActorType actorType,
      String agentName,
      UUID runId,
      String body,
      ObjectNode data) {
    long sequence = issue.getNextActivitySequence();
    IssueActivity activity =
        IssueActivity.builder()
            .issueId(issue.getId())
            .sequence(sequence)
            .kind(identity.kind())
            .actorType(actorType)
            .actorAgentName(agentName)
            .runId(runId)
            .body(body)
            .data(writeJson(data))
            .idempotencyKey(identity.key())
            .requestHash(identity.requestHash())
            .build();
    if (!issueActivityRepository.insert(activity)) {
      throw new IllegalStateException("failed to insert issue activity");
    }
    issue.setNextActivitySequence(sequence + 1);
  }

  Issue persist(Locked locked, long expectedVersion) {
    if (!issueRepository.updateById(locked.issue(), expectedVersion)) {
      throw new ProjectVersionConflictException(
          "issue", Long.toString(expectedVersion), Long.toString(locked.issue().getVersion()));
    }
    return issueRepository.getById(locked.issue().getId());
  }

  /**
   * 状态或门禁变化后，若当前阶段是启用且有 Agent 的工作阶段就登记一次 Work。
   *
   * <p>mailbox 只表达「重新检查当前 Issue」，因此这里只登记事实，不预判额度或派发决定；保留阶段、人工阶段与暂停门禁都不产生 Work。
   */
  void wakeIfExecutable(Issue issue, ProjectWorkflow workflow) {
    if (issue.isPaused()) {
      return;
    }
    ProjectStateCode state = ProjectStateCode.of(issue.getState());
    if (ProjectWorkflowReservedState.isReserved(state)) {
      return;
    }
    ProjectWorkflowState stage = workflow.find(state).orElse(null);
    if (stage == null || !stage.enabled() || !stage.hasAgent()) {
      return;
    }
    issueWorkQueue.request(issue.getId(), Duration.ZERO);
  }

  ObjectNode newData() {
    return objectMapper.createObjectNode();
  }

  private String writeJson(ObjectNode node) {
    try {
      return objectMapper.writeValueAsString(node);
    } catch (JacksonException error) {
      throw new IllegalStateException("failed to encode activity data", error);
    }
  }

  record Locked(Issue issue, Project project, ProjectWorkflow workflow) {}
}
