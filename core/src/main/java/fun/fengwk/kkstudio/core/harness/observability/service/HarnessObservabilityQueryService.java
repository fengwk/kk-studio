package fun.fengwk.kkstudio.core.harness.observability.service;

import fun.fengwk.kkstudio.core.harness.observability.service.model.ArtifactContent;
import fun.fengwk.kkstudio.share.model.InteractionDTO;
import fun.fengwk.kkstudio.share.model.ModelInvocationDTO;
import fun.fengwk.kkstudio.share.model.RootActivityDTO;
import fun.fengwk.kkstudio.share.model.SubagentTaskDTO;
import fun.fengwk.kkstudio.share.model.ToolInvocationDTO;

import java.util.List;

/** Harness observability 查询：snapshot-first Invocation/Interaction、RootActivity、Artifact。 */
public interface HarnessObservabilityQueryService {

  List<RootActivityDTO> listRootActivities(String sessionId, long afterEventId, int limit);

  List<ToolInvocationDTO> listToolInvocations(String threadId);

  ToolInvocationDTO getToolInvocation(String invocationId);

  List<ModelInvocationDTO> listModelInvocations(String threadId);

  List<InteractionDTO> listOpenInteractions(String threadId);

  List<SubagentTaskDTO> listSessionTasks(String sessionId);

  ArtifactContent getArtifact(String artifactId);
}
