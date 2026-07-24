package fun.fengwk.kkstudio.core.harness.observability.service;

import fun.fengwk.kkstudio.harness.runtime.tool.worker.Artifact;
import fun.fengwk.kkstudio.share.model.InteractionDTO;
import fun.fengwk.kkstudio.share.model.ModelInvocationDTO;
import fun.fengwk.kkstudio.share.model.RootActivityDTO;
import fun.fengwk.kkstudio.share.model.SubagentTaskDTO;
import fun.fengwk.kkstudio.share.model.ThreadEventDTO;
import fun.fengwk.kkstudio.share.model.ToolInvocationDTO;

import java.util.List;

/** Harness observability 查询：snapshot-first Invocation/Interaction、RootActivity、Artifact。 */
public interface HarnessObservabilityQueryService {

  /** ThreadEvent history 已停用；实现返回空列表以兼容旧 SSE 轮询路径。 */
  List<ThreadEventDTO> listThreadEvents(String threadId, long afterEventId, int limit);

  List<RootActivityDTO> listRootActivities(String sessionId, long afterEventId, int limit);

  List<ToolInvocationDTO> listToolInvocations(String threadId);

  ToolInvocationDTO getToolInvocation(String invocationId);

  List<ModelInvocationDTO> listModelInvocations(String threadId);

  List<InteractionDTO> listOpenInteractions(String threadId);

  List<SubagentTaskDTO> listSessionTasks(String sessionId);

  Artifact getArtifact(String artifactId);
}
