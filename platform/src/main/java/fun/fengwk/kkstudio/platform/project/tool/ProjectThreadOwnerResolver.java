package fun.fengwk.kkstudio.platform.project.tool;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueAgentSession;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentSessionRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Resolves active IssueRun product role context for a Harness thread. */
public class ProjectThreadOwnerResolver {

  private static final String INCONSISTENT_OWNERSHIP = "Project thread ownership is inconsistent";

  private final Supplier<HarnessStore> harnessStoreSupplier;
  private final IssueAgentSessionRepository issueAgentSessionRepository;
  private final ProjectRepository projectRepository;
  private final IssueRepository issueRepository;
  private final IssueRunRepository issueRunRepository;

  public ProjectThreadOwnerResolver(
      HarnessStore harnessStore,
      IssueAgentSessionRepository issueAgentSessionRepository,
      ProjectRepository projectRepository,
      IssueRepository issueRepository,
      IssueRunRepository issueRunRepository) {
    this(
        fixedStore(harnessStore),
        issueAgentSessionRepository,
        projectRepository,
        issueRepository,
        issueRunRepository);
  }

  private ProjectThreadOwnerResolver(
      Supplier<HarnessStore> harnessStoreSupplier,
      IssueAgentSessionRepository issueAgentSessionRepository,
      ProjectRepository projectRepository,
      IssueRepository issueRepository,
      IssueRunRepository issueRunRepository) {
    this.harnessStoreSupplier =
        Objects.requireNonNull(harnessStoreSupplier, "harnessStoreSupplier");
    this.issueAgentSessionRepository =
        Objects.requireNonNull(issueAgentSessionRepository, "issueAgentSessionRepository");
    this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
    this.issueRepository = Objects.requireNonNull(issueRepository, "issueRepository");
    this.issueRunRepository = Objects.requireNonNull(issueRunRepository, "issueRunRepository");
  }

  static ProjectThreadOwnerResolver withStoreSupplier(
      Supplier<HarnessStore> harnessStoreSupplier,
      IssueAgentSessionRepository issueAgentSessionRepository,
      ProjectRepository projectRepository,
      IssueRepository issueRepository,
      IssueRunRepository issueRunRepository) {
    return new ProjectThreadOwnerResolver(
        harnessStoreSupplier,
        issueAgentSessionRepository,
        projectRepository,
        issueRepository,
        issueRunRepository);
  }

  /**
   * 该 thread 是否属于稳定的 Issue+Agent 归属。
   *
   * <p>只回答归属本身，与「当前是否有活动 Run」无关：Run 结束或尚未启动时，Issue Agent Branch 仍然是 Issue Agent Branch，必须继续对 Goal
   * 等语义封闭。
   */
  public boolean isIssueAgentBranch(UUID threadId) {
    Objects.requireNonNull(threadId, "threadId");
    return findAgentSession(threadId) != null;
  }

  public Optional<ProjectThreadOwnerContext> resolve(UUID threadId) {
    Objects.requireNonNull(threadId, "threadId");
    IssueAgentSession agentSession = findAgentSession(threadId);
    if (agentSession == null) {
      return Optional.empty();
    }

    Issue issue = issueRepository.getById(agentSession.getIssueId());
    if (issue == null || !agentSession.getIssueId().equals(issue.getId())) {
      throw inconsistent();
    }
    Project project = projectRepository.getById(issue.getProjectId());
    if (project == null || !issue.getProjectId().equals(project.getId())) {
      throw inconsistent();
    }

    IssueRun activeRun = issueRunRepository.findActiveByIssueId(issue.getId());
    if (activeRun == null
        || !activeRun.isActive()
        || !Objects.equals(activeRun.getAgentName(), agentSession.getAgentName())) {
      return Optional.empty();
    }

    ProjectRole role =
        activeRun.getRole() == IssueRunRole.EXECUTOR ? ProjectRole.EXECUTOR : ProjectRole.REVIEWER;

    return Optional.of(
        new ProjectThreadOwnerContext(
            role, project.getId(), issue.getId(), activeRun.getId(), agentSession.getAgentName()));
  }

  /** 按 thread 直接命中归属，其次退化为「thread 的 Session 是否已有稳定归属」；两者皆无时返回 null。 */
  private IssueAgentSession findAgentSession(UUID threadId) {
    IssueAgentSession agentSession = issueAgentSessionRepository.findByThreadId(threadId);
    if (agentSession != null) {
      return agentSession;
    }
    HarnessStore harnessStore = harnessStoreSupplier.get();
    if (harnessStore == null) {
      return null;
    }
    return harnessStore
        .transaction(tx -> tx.findThread(threadId))
        .map(ThreadState::sessionId)
        .map(issueAgentSessionRepository::findBySessionId)
        .orElse(null);
  }

  private static Supplier<HarnessStore> fixedStore(HarnessStore store) {
    Objects.requireNonNull(store, "harnessStore");
    return () -> store;
  }

  private static IllegalStateException inconsistent() {
    return new IllegalStateException(INCONSISTENT_OWNERSHIP);
  }
}
