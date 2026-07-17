package fun.fengwk.kkstudio.core.harness.observability.service;

import fun.fengwk.kkstudio.harness.runtime.tool.worker.Artifact;
import fun.fengwk.kkstudio.share.model.RootActivityDTO;
import fun.fengwk.kkstudio.share.model.RunEventDTO;
import fun.fengwk.kkstudio.share.model.SubagentTaskDTO;
import fun.fengwk.kkstudio.share.model.ToolInvocationDTO;

import java.util.List;

/**
 * T15 harness observability query service. All persistence I/O goes through the existing stores and
 * mappers. There is no in-memory event bus — call sites observe only what has already been
 * committed to the database.
 */
public interface HarnessObservabilityQueryService {

  /** Page Run events in ascending sequence order, requiring the Run to exist. */
  List<RunEventDTO> listRunEvents(String runId, long afterSequence, int limit);

  /**
   * Page root-tree activity in ascending event-id order. Any descendant session id is resolved to
   * its rootSessionId before paging.
   */
  List<RootActivityDTO> listRootActivities(String sessionId, long afterEventId, int limit);

  /** Project every tool invocation recorded against a Run in ordinal order. */
  List<ToolInvocationDTO> listToolInvocations(String runId);

  /** Read a single tool invocation by id. */
  ToolInvocationDTO getToolInvocation(String invocationId);

  /** Page durable parent-session tasks in mapper's parent-session order. */
  List<SubagentTaskDTO> listSessionTasks(String sessionId);

  /**
   * Resolve an artifact by its decimal-pattern id. Throws {@link IllegalArgumentException} for
   * blank, non-decimal or non-positive identifiers (400) and for unknown ids ("unknown artifact:"
   * prefix → 404). Returns the {@link Artifact} on success.
   */
  Artifact getArtifact(String artifactId);
}
