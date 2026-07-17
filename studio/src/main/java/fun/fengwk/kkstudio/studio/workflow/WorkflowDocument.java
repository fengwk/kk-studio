package fun.fengwk.kkstudio.studio.workflow;

import java.util.Objects;

public record WorkflowDocument(
    long id,
    long workspaceId,
    String name,
    WorkflowLifecycle lifecycle,
    Long publishedVersionId,
    long revision) {

  public WorkflowDocument {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(lifecycle, "lifecycle");
  }
}
