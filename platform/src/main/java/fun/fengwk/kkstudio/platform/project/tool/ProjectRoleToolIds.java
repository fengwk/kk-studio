package fun.fengwk.kkstudio.platform.project.tool;

import fun.fengwk.kkstudio.harness.tool.AgentToolId;

import java.util.List;
import java.util.Objects;

/** Stable internal Tool identities for Project roles. */
public final class ProjectRoleToolIds {

  public static final AgentToolId PROJECT_READ = new AgentToolId("project.read");
  public static final AgentToolId ISSUE_READ = new AgentToolId("issue.read");
  public static final AgentToolId ISSUE_LIST = new AgentToolId("issue.list");
  public static final AgentToolId ISSUE_CREATE = new AgentToolId("issue.create");
  public static final AgentToolId ISSUE_UPDATE = new AgentToolId("issue.update");
  public static final AgentToolId ISSUE_ADD_DEPENDENCY = new AgentToolId("issue.add-dependency");
  public static final AgentToolId ISSUE_REMOVE_DEPENDENCY =
      new AgentToolId("issue.remove-dependency");
  public static final AgentToolId ISSUE_SET_STATUS = new AgentToolId("issue.set-status");
  public static final AgentToolId ISSUE_CANCEL = new AgentToolId("issue.cancel");
  public static final AgentToolId ISSUE_SUBMIT = new AgentToolId("issue.submit");
  public static final AgentToolId ISSUE_REQUEST_INPUT = new AgentToolId("issue.request-input");
  public static final AgentToolId ISSUE_REVIEW = new AgentToolId("issue.review");

  public static final List<AgentToolId> COORDINATOR =
      List.of(
          PROJECT_READ,
          ISSUE_READ,
          ISSUE_LIST,
          ISSUE_CREATE,
          ISSUE_UPDATE,
          ISSUE_ADD_DEPENDENCY,
          ISSUE_REMOVE_DEPENDENCY,
          ISSUE_SET_STATUS,
          ISSUE_CANCEL);

  public static final List<AgentToolId> EXECUTOR = List.of(ISSUE_SUBMIT, ISSUE_REQUEST_INPUT);
  public static final List<AgentToolId> REVIEWER = List.of(ISSUE_REVIEW);

  private ProjectRoleToolIds() {}

  public static List<AgentToolId> forRole(ProjectRole role) {
    return switch (Objects.requireNonNull(role, "role")) {
      case COORDINATOR -> COORDINATOR;
      case EXECUTOR -> EXECUTOR;
      case REVIEWER -> REVIEWER;
    };
  }
}
