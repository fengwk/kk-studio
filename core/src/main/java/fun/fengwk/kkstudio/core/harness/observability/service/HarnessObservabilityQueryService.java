package fun.fengwk.kkstudio.core.harness.observability.service;

import fun.fengwk.kkstudio.harness.runtime.tool.worker.Artifact;
import fun.fengwk.kkstudio.share.model.RootActivityDTO;
import fun.fengwk.kkstudio.share.model.SubagentTaskDTO;
import fun.fengwk.kkstudio.share.model.ThreadEventDTO;
import fun.fengwk.kkstudio.share.model.ToolInvocationDTO;

import java.util.List;

/** Harness observability 查询：Thread 事件、ToolInvocation、RootActivity、Artifact。 */
public interface HarnessObservabilityQueryService {

  /** 按 eventId 升序分页 Thread 事件；afterEventId 为 SSE cursor。 */
  List<ThreadEventDTO> listThreadEvents(String threadId, long afterEventId, int limit);

  List<RootActivityDTO> listRootActivities(String sessionId, long afterEventId, int limit);

  List<ToolInvocationDTO> listToolInvocations(String threadId);

  ToolInvocationDTO getToolInvocation(String invocationId);

  List<SubagentTaskDTO> listSessionTasks(String sessionId);

  Artifact getArtifact(String artifactId);
}
