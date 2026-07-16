package fun.fengwk.kkstudio.core.harness.observability.service;

import fun.fengwk.kkstudio.share.model.RootActivityDTO;
import fun.fengwk.kkstudio.share.model.RunEventDTO;
import fun.fengwk.kkstudio.share.model.SubagentTaskDTO;
import fun.fengwk.kkstudio.share.model.ToolInvocationDTO;
import java.util.List;

/**
 * T15 harness observability query service. All persistence I/O goes through the existing
 * {@link fun.fengwk.kkstudio.core.harness.run.store.MysqlHarnessRunStore} (run event cursor),
 * {@link fun.fengwk.kkstudio.core.harness.task.store.DatabaseRootActivityStore} (root-tree event-id
 * cursor), {@link fun.fengwk.kkstudio.core.harness.tool.store.MysqlToolInvocationStore},
 * {@link fun.fengwk.kkstudio.core.harness.task.store.mapper.HarnessSubagentTaskMapper} and
 * {@link fun.fengwk.kkstudio.core.harness.tool.worker.DatabaseArtifactStore}. There is no in-memory
 * event bus — call sites observe only what has already been committed to the database.
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
   * Strict artifact lookup that mirrors HTTP 400/404 semantics: blank or non-positive ids become
   * 400, unknown ids become 404, otherwise the artifact is returned via {@link
   * HarnessArtifactResolution.Result#artifact()}.
   */
  HarnessArtifactResolution.Result resolveArtifact(String artifactId);
}