package fun.fengwk.kkstudio.platform.project.tool;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.platform.project.model.Issue;
import fun.fengwk.kkstudio.platform.project.model.IssueRun;
import fun.fengwk.kkstudio.platform.project.model.IssueRunActorType;
import fun.fengwk.kkstudio.platform.project.model.IssueRunRole;
import fun.fengwk.kkstudio.platform.project.model.IssueRunSession;
import fun.fengwk.kkstudio.platform.project.model.Project;
import fun.fengwk.kkstudio.platform.project.model.ProjectSession;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunSessionRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectSessionRepository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Resolves Project role ownership from the durable owner of a Harness thread. */
public class ProjectThreadOwnerResolver {

  private static final String INCONSISTENT_OWNERSHIP = "Project thread ownership is inconsistent";

  private final Supplier<HarnessStore> harnessStoreSupplier;
  private final ProjectSessionRepository projectSessionRepository;
  private final IssueRunSessionRepository issueRunSessionRepository;
  private final ProjectRepository projectRepository;
  private final IssueRepository issueRepository;
  private final IssueRunRepository issueRunRepository;

  public ProjectThreadOwnerResolver(
      HarnessStore harnessStore,
      ProjectSessionRepository projectSessionRepository,
      IssueRunSessionRepository issueRunSessionRepository,
      ProjectRepository projectRepository,
      IssueRepository issueRepository,
      IssueRunRepository issueRunRepository) {
    this(
        fixedStore(harnessStore),
        projectSessionRepository,
        issueRunSessionRepository,
        projectRepository,
        issueRepository,
        issueRunRepository);
  }

  private ProjectThreadOwnerResolver(
      Supplier<HarnessStore> harnessStoreSupplier,
      ProjectSessionRepository projectSessionRepository,
      IssueRunSessionRepository issueRunSessionRepository,
      ProjectRepository projectRepository,
      IssueRepository issueRepository,
      IssueRunRepository issueRunRepository) {
    this.harnessStoreSupplier =
        Objects.requireNonNull(harnessStoreSupplier, "harnessStoreSupplier");
    this.projectSessionRepository =
        Objects.requireNonNull(projectSessionRepository, "projectSessionRepository");
    this.issueRunSessionRepository =
        Objects.requireNonNull(issueRunSessionRepository, "issueRunSessionRepository");
    this.projectRepository = Objects.requireNonNull(projectRepository, "projectRepository");
    this.issueRepository = Objects.requireNonNull(issueRepository, "issueRepository");
    this.issueRunRepository = Objects.requireNonNull(issueRunRepository, "issueRunRepository");
  }

  static ProjectThreadOwnerResolver withStoreSupplier(
      Supplier<HarnessStore> harnessStoreSupplier,
      ProjectSessionRepository projectSessionRepository,
      IssueRunSessionRepository issueRunSessionRepository,
      ProjectRepository projectRepository,
      IssueRepository issueRepository,
      IssueRunRepository issueRunRepository) {
    return new ProjectThreadOwnerResolver(
        harnessStoreSupplier,
        projectSessionRepository,
        issueRunSessionRepository,
        projectRepository,
        issueRepository,
        issueRunRepository);
  }

  public Optional<ProjectThreadOwnerContext> resolve(UUID threadId) {
    Objects.requireNonNull(threadId, "threadId");
    HarnessStore harnessStore = harnessStoreSupplier.get();
    if (harnessStore == null) {
      throw inconsistent();
    }
    Optional<ThreadState> thread = harnessStore.transaction(tx -> tx.findThread(threadId));
    if (thread.isEmpty()) {
      return Optional.empty();
    }

    UUID sessionId = thread.orElseThrow().sessionId();
    ProjectSession projectSession = projectSessionRepository.findBySessionId(sessionId);
    IssueRunSession issueRunSession = issueRunSessionRepository.findBySessionId(sessionId);
    if (projectSession != null && issueRunSession != null) {
      throw inconsistent();
    }
    if (projectSession != null) {
      return Optional.of(resolveProject(sessionId, projectSession));
    }
    if (issueRunSession != null) {
      return Optional.of(resolveIssueRun(sessionId, issueRunSession));
    }
    return Optional.empty();
  }

  private ProjectThreadOwnerContext resolveProject(UUID sessionId, ProjectSession projectSession) {
    if (!sessionId.equals(projectSession.getSessionId())) {
      throw inconsistent();
    }
    Project project = projectRepository.getById(projectSession.getProjectId());
    if (project == null
        || !projectSession.getProjectId().equals(project.getId())
        || project.getCoordinatorAgentName() == null
        || project.getCoordinatorAgentName().isBlank()) {
      throw inconsistent();
    }
    return new ProjectThreadOwnerContext(
        ProjectRole.COORDINATOR, project.getId(), null, null, project.getCoordinatorAgentName());
  }

  private ProjectThreadOwnerContext resolveIssueRun(
      UUID sessionId, IssueRunSession issueRunSession) {
    if (!sessionId.equals(issueRunSession.getSessionId())) {
      throw inconsistent();
    }
    IssueRun run = issueRunRepository.getById(issueRunSession.getRunId());
    if (run == null
        || !issueRunSession.getRunId().equals(run.getId())
        || run.getActorType() != IssueRunActorType.AGENT
        || run.getAgentName() == null
        || run.getAgentName().isBlank()) {
      throw inconsistent();
    }
    Issue issue = issueRepository.getById(run.getIssueId());
    if (issue == null || !run.getIssueId().equals(issue.getId())) {
      throw inconsistent();
    }
    Project project = projectRepository.getById(issue.getProjectId());
    if (project == null || !issue.getProjectId().equals(project.getId())) {
      throw inconsistent();
    }

    IssueRunRole runRole = run.getRole();
    if (runRole == null) {
      throw inconsistent();
    }
    ProjectRole role =
        switch (runRole) {
          case EXECUTOR -> ProjectRole.EXECUTOR;
          case REVIEWER -> ProjectRole.REVIEWER;
        };
    return new ProjectThreadOwnerContext(
        role, project.getId(), issue.getId(), run.getId(), run.getAgentName());
  }

  private static IllegalStateException inconsistent() {
    return new IllegalStateException(INCONSISTENT_OWNERSHIP);
  }

  private static Supplier<HarnessStore> fixedStore(HarnessStore harnessStore) {
    Objects.requireNonNull(harnessStore, "harnessStore");
    return () -> harnessStore;
  }
}
