package fun.fengwk.kkstudio.project.service;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwner;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwnerDisplay;
import fun.fengwk.kkstudio.platform.orchestration.SessionOwnership;
import fun.fengwk.kkstudio.project.model.Issue;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.model.Project;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Issue-agent ownership, serialized in Project then Issue lock order. */
@Service
@AllArgsConstructor
public class ProjectSessionOwnership implements SessionOwnership {
  private static final String KIND = "ISSUE_AGENT";
  private final ProjectRepository projects;
  private final IssueRepository issues;
  private final IssueAgentThreadRepository bindings;

  public static SessionOwner owner(UUID issueId, String agentName) {
    Objects.requireNonNull(issueId, "issueId");
    if (agentName == null || agentName.isBlank()) {
      throw new IllegalArgumentException("Agent name must not be blank");
    }
    return new SessionOwner(KIND, issueId + ":" + agentName);
  }

  private record Key(UUID issueId, String agentName) {}

  private Key key(SessionOwner owner) {
    if (!KIND.equals(owner.kind())) {
      throw new IllegalArgumentException("Invalid owner kind");
    }
    int separator = owner.id().indexOf(':');
    if (separator < 0) {
      throw new IllegalArgumentException("Invalid issue agent owner");
    }
    String issueId = owner.id().substring(0, separator);
    UUID parsed;
    try {
      parsed = Uuids.parseCanonical(issueId, "issueId");
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("Invalid issue agent owner", error);
    }
    String agent = owner.id().substring(separator + 1);
    if (agent.isBlank()) {
      throw new IllegalArgumentException("Invalid issue agent owner");
    }
    return new Key(parsed, agent);
  }

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public boolean allowsFork() {
    return false;
  }

  @Override
  public boolean allowsDirectCommands() {
    return false;
  }

  private Issue lock(Key key, boolean deletion, boolean allowArchived) {
    Issue issue = issues.getById(key.issueId());
    if (issue == null) {
      if (deletion) {
        return null;
      }
      throw new IllegalArgumentException("Issue owner does not exist");
    }
    UUID projectId = issue.getProjectId();
    Project project = deletion ? projects.lockById(projectId) : projects.lockForShare(projectId);
    if (project == null) {
      throw new IllegalStateException("Project owner does not exist");
    }
    Issue locked = issues.lockById(key.issueId());
    if (locked == null || !projectId.equals(locked.getProjectId())) {
      throw new IllegalStateException("Issue owner hierarchy is inconsistent");
    }
    if (!allowArchived && project.isArchived()) {
      throw new IllegalArgumentException("Owner is archived");
    }
    return locked;
  }

  private IssueAgentThread binding(Key key) {
    return bindings.findByIssueIdAndAgentName(key.issueId(), key.agentName());
  }

  @Override
  public void lockForAcceptance(SessionOwner owner, UUID newRootThreadId) {
    Key key = key(owner);
    lock(key, false, false);
    IssueAgentThread existing = newRootThreadId == null ? null : binding(key);
    if (existing != null && !existing.threadId().equals(newRootThreadId)) {
      throw new IllegalArgumentException("Issue agent is already bound");
    }
  }

  @Override
  public boolean ownsSession(SessionOwner owner, UUID sessionId) {
    IssueAgentThread binding = binding(key(owner));
    return binding != null && binding.sessionId().equals(sessionId);
  }

  @Override
  public void sessionCreated(SessionOwner owner, UUID sessionId) {
    // The product caller creates the binding after runtime acceptance.
  }

  @Override
  public List<UUID> listSessions(SessionOwner owner) {
    Key key = key(owner);
    Issue issue = issues.getById(key.issueId());
    if (issue == null) {
      throw new IllegalArgumentException("Issue owner does not exist");
    }
    if (projects.getById(issue.getProjectId()) == null) {
      throw new IllegalStateException("Project owner does not exist");
    }
    IssueAgentThread binding = binding(key);
    if (binding == null) {
      throw new IllegalStateException("Issue agent binding does not exist");
    }
    return List.of(binding.sessionId());
  }

  @Override
  public List<UUID> lockForDeletion(SessionOwner owner) {
    Key key = key(owner);
    if (lock(key, true, true) == null) {
      return List.of();
    }
    IssueAgentThread binding = binding(key);
    return binding == null ? List.of() : List.of(binding.sessionId());
  }

  @Override
  public void beforeSessionDeletion(SessionOwner owner, UUID sessionId) {
    Key key = key(owner);
    IssueAgentThread binding = binding(key);
    if (binding == null
        || !binding.sessionId().equals(sessionId)
        || bindings.deleteByIssueIdAndAgentName(key.issueId(), key.agentName()) != 1) {
      throw new IllegalStateException("Issue agent binding is inconsistent");
    }
  }

  private IssueAgentThread rootBinding(UUID rootThreadId, UUID rootSessionId) {
    IssueAgentThread binding = bindings.findByThreadId(rootThreadId);
    if (binding != null && !binding.sessionId().equals(rootSessionId)) {
      throw new IllegalStateException("Issue agent root is inconsistent");
    }
    return binding;
  }

  @Override
  public Optional<SessionOwnerDisplay> findOwner(UUID rootThreadId, UUID rootSessionId) {
    IssueAgentThread binding = rootBinding(rootThreadId, rootSessionId);
    if (binding == null) {
      return Optional.empty();
    }
    Issue issue = issues.getById(binding.issueId());
    if (issue == null || projects.getById(issue.getProjectId()) == null) {
      throw new IllegalStateException("Issue owner hierarchy is inconsistent");
    }
    SessionOwner owner = owner(binding.issueId(), binding.agentName());
    return Optional.of(
        new SessionOwnerDisplay(
            KIND,
            owner.id(),
            issue.getTitle(),
            "/projects/" + issue.getProjectId() + "?issue=" + binding.issueId()));
  }

  @Override
  public void lockForInteraction(UUID rootThreadId, UUID rootSessionId) {
    IssueAgentThread binding = rootBinding(rootThreadId, rootSessionId);
    if (binding != null) {
      Key key = new Key(binding.issueId(), binding.agentName());
      lock(key, false, true);
      if (!binding.equals(rootBinding(rootThreadId, rootSessionId))) {
        throw new IllegalStateException("Issue agent binding is inconsistent");
      }
    }
  }
}
