package fun.fengwk.kkstudio.core.ai.runtime.observability.service;

import fun.fengwk.kkstudio.core.ai.runtime.observability.service.model.ArtifactContent;
import fun.fengwk.kkstudio.share.ai.runtime.InteractionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.ModelInvocationDTO;
import fun.fengwk.kkstudio.share.ai.runtime.ToolInvocationDTO;

import java.util.List;

/** Harness observability 查询：snapshot-first Invocation/Interaction/Artifact。 */
public interface HarnessObservabilityQueryService {

  List<ToolInvocationDTO> listToolInvocations(String threadId);

  ToolInvocationDTO getToolInvocation(String invocationId);

  List<ModelInvocationDTO> listModelInvocations(String threadId);

  List<InteractionDTO> listOpenInteractions(String threadId);

  ArtifactContent getArtifact(String artifactId);
}
