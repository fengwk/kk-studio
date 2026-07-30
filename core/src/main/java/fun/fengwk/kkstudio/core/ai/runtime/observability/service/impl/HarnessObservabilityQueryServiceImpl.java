package fun.fengwk.kkstudio.core.ai.runtime.observability.service.impl;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.ai.runtime.interaction.store.mapper.InteractionMapper;
import fun.fengwk.kkstudio.core.ai.runtime.model.worker.ModelInvocationMapper;
import fun.fengwk.kkstudio.core.ai.runtime.observability.service.HarnessObservabilityQueryService;
import fun.fengwk.kkstudio.core.ai.runtime.observability.service.model.ArtifactContent;
import fun.fengwk.kkstudio.core.ai.runtime.query.HarnessQueryDtoConverter;
import fun.fengwk.kkstudio.core.ai.runtime.query.PostgresqlHarnessQueryMapper;
import fun.fengwk.kkstudio.core.ai.runtime.session.support.HarnessIds;
import fun.fengwk.kkstudio.core.ai.runtime.tool.worker.PostgresqlToolInvocationMapper;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.Artifact;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ArtifactStore;
import fun.fengwk.kkstudio.share.ai.runtime.InteractionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.ModelInvocationDTO;
import fun.fengwk.kkstudio.share.ai.runtime.ToolInvocationDTO;

import java.util.List;
import java.util.Objects;

/** final PostgreSQL observability read model。 */
@Service
public class HarnessObservabilityQueryServiceImpl implements HarnessObservabilityQueryService {
  private final PostgresqlHarnessQueryMapper queryMapper;
  private final PostgresqlToolInvocationMapper toolInvocationMapper;
  private final ModelInvocationMapper modelInvocationMapper;
  private final InteractionMapper interactionMapper;
  private final ArtifactStore artifactStore;
  private final HarnessQueryDtoConverter queryConverter;
  private final HarnessObservabilityDtoConverter converter;

  public HarnessObservabilityQueryServiceImpl(
      PostgresqlHarnessQueryMapper queryMapper,
      PostgresqlToolInvocationMapper toolInvocationMapper,
      ModelInvocationMapper modelInvocationMapper,
      InteractionMapper interactionMapper,
      ArtifactStore artifactStore,
      HarnessQueryDtoConverter queryConverter,
      HarnessObservabilityDtoConverter converter) {
    this.queryMapper = Objects.requireNonNull(queryMapper, "queryMapper");
    this.toolInvocationMapper =
        Objects.requireNonNull(toolInvocationMapper, "toolInvocationMapper");
    this.modelInvocationMapper =
        Objects.requireNonNull(modelInvocationMapper, "modelInvocationMapper");
    this.interactionMapper = Objects.requireNonNull(interactionMapper, "interactionMapper");
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.queryConverter = Objects.requireNonNull(queryConverter, "queryConverter");
    this.converter = Objects.requireNonNull(converter, "converter");
  }

  @Override
  public List<ToolInvocationDTO> listToolInvocations(String threadId) {
    long id = HarnessIds.parsePositive(threadId, "threadId");
    return toolInvocationMapper.listByThread(id).stream().map(converter::convert).toList();
  }

  @Override
  public ToolInvocationDTO getToolInvocation(String invocationId) {
    long id = HarnessIds.parsePositive(invocationId, "invocationId");
    var row = toolInvocationMapper.find(id);
    if (row == null) {
      throw new IllegalArgumentException("unknown tool invocation: " + invocationId);
    }
    return converter.convert(row);
  }

  @Override
  public ArtifactContent getArtifact(String artifactId) {
    HarnessIds.parsePositive(artifactId, "artifactId");
    Artifact artifact =
        artifactStore
            .find(artifactId)
            .orElseThrow(() -> new IllegalArgumentException("unknown artifact: " + artifactId));
    return new ArtifactContent(
        artifact.id(),
        artifact.mediaType(),
        artifact.encoding(),
        artifact.content(),
        artifact.sizeBytes(),
        artifact.sha256());
  }

  @Override
  public List<ModelInvocationDTO> listModelInvocations(String threadId) {
    long id = HarnessIds.parsePositive(threadId, "threadId");
    if (queryMapper.findThreadView(id) == null) {
      throw new IllegalArgumentException("unknown thread: " + threadId);
    }
    return modelInvocationMapper.listByThread(id).stream()
        .map(queryConverter::toModelInvocation)
        .toList();
  }

  @Override
  public List<InteractionDTO> listOpenInteractions(String threadId) {
    long id = HarnessIds.parsePositive(threadId, "threadId");
    if (queryMapper.findThreadView(id) == null) {
      throw new IllegalArgumentException("unknown thread: " + threadId);
    }
    return interactionMapper.listOpenByThread(id).stream()
        .map(queryConverter::toInteraction)
        .toList();
  }
}
