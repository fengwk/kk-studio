package fun.fengwk.kkstudio.studio.workflow;

import fun.fengwk.kkstudio.studio.model.FunctionRef;

import java.util.Objects;

/** Immutable published or draft snapshot of a Workflow program. */
public record WorkflowVersion(
    long id, long workflowId, long version, String astJson, FunctionRef publishedFunctionRef) {

  public WorkflowVersion {
    Objects.requireNonNull(astJson, "astJson");
  }
}
