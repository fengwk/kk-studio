package fun.fengwk.kkstudio.core.harness.observability.service.impl;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.harness.interaction.store.mapper.InteractionMapper;
import fun.fengwk.kkstudio.core.harness.model.worker.ModelInvocationMapper;
import fun.fengwk.kkstudio.core.harness.observability.service.HarnessObservabilityQueryService;
import fun.fengwk.kkstudio.core.harness.observability.service.ObservabilityLimits;
import fun.fengwk.kkstudio.core.harness.observability.service.model.ArtifactContent;
import fun.fengwk.kkstudio.core.harness.query.HarnessQueryDtoConverter;
import fun.fengwk.kkstudio.core.harness.query.HarnessQueryRow;
import fun.fengwk.kkstudio.core.harness.query.PostgresqlHarnessQueryMapper;
import fun.fengwk.kkstudio.core.harness.session.support.HarnessIds;
import fun.fengwk.kkstudio.core.harness.tool.worker.PostgresqlToolInvocationMapper;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.Artifact;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ArtifactStore;
import fun.fengwk.kkstudio.share.model.InteractionDTO;
import fun.fengwk.kkstudio.share.model.ModelInvocationDTO;
import fun.fengwk.kkstudio.share.model.RootActivityDTO;
import fun.fengwk.kkstudio.share.model.SubagentTaskDTO;
import fun.fengwk.kkstudio.share.model.ToolInvocationDTO;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** final PostgreSQL observability read model；SubagentTask 表暂未落地时返回空列表。 */
@Service
public class HarnessObservabilityQueryServiceImpl implements HarnessObservabilityQueryService {
  private final PostgresqlHarnessQueryMapper queryMapper;
  private final PostgresqlToolInvocationMapper toolInvocationMapper;
  private final ModelInvocationMapper modelInvocationMapper;
  private final InteractionMapper interactionMapper;
  private final ArtifactStore artifactStore;
  private final HarnessQueryDtoConverter queryConverter;
  private final HarnessObservabilityDtoConverter converter;
  private final Clock clock;

  public HarnessObservabilityQueryServiceImpl(
      PostgresqlHarnessQueryMapper queryMapper,
      PostgresqlToolInvocationMapper toolInvocationMapper,
      ModelInvocationMapper modelInvocationMapper,
      InteractionMapper interactionMapper,
      ArtifactStore artifactStore,
      HarnessQueryDtoConverter queryConverter,
      HarnessObservabilityDtoConverter converter,
      Clock clock) {
    this.queryMapper = Objects.requireNonNull(queryMapper, "queryMapper");
    this.toolInvocationMapper =
        Objects.requireNonNull(toolInvocationMapper, "toolInvocationMapper");
    this.modelInvocationMapper =
        Objects.requireNonNull(modelInvocationMapper, "modelInvocationMapper");
    this.interactionMapper = Objects.requireNonNull(interactionMapper, "interactionMapper");
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.queryConverter = Objects.requireNonNull(queryConverter, "queryConverter");
    this.converter = Objects.requireNonNull(converter, "converter");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public List<RootActivityDTO> listRootActivities(String sessionId, long afterEventId, int limit) {
    long id = HarnessIds.parsePositive(sessionId, "sessionId");
    ObservabilityLimits.requireNonNegativeCursor(afterEventId, "afterEventId");
    int page = ObservabilityLimits.normalizeLimit(limit, ObservabilityLimits.DEFAULT_LIMIT);
    HarnessQueryRow seed = queryMapper.findSession(id);
    if (seed == null) {
      throw new IllegalArgumentException("unknown session: " + sessionId);
    }
    long rootId = resolveRootSessionId(seed);
    Instant now = clock.instant();
    List<RootActivityDTO> activities = new ArrayList<>();
    for (HarnessQueryRow session : queryMapper.listSessionTree(rootId)) {
      for (HarnessQueryRow thread : queryMapper.listThreadViewsBySession(session.getId())) {
        if (thread.getId() > afterEventId) {
          activities.add(queryConverter.toRootActivity(rootId, thread, now));
        }
      }
    }
    return activities.stream()
        .sorted(Comparator.comparingLong(a -> Long.parseLong(a.getEventId())))
        .limit(page)
        .toList();
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
  public List<SubagentTaskDTO> listSessionTasks(String sessionId) {
    // final schema 暂无 harness_subagent_task；Child Session 创建完成前返回空列表。
    HarnessIds.parsePositive(sessionId, "sessionId");
    return List.of();
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

  private long resolveRootSessionId(HarnessQueryRow session) {
    long currentId = session.getId();
    Long parent = session.getParentSessionId();
    int guard = 0;
    while (parent != null) {
      HarnessQueryRow parentRow = queryMapper.findSession(parent);
      if (parentRow == null) {
        break;
      }
      currentId = parentRow.getId();
      parent = parentRow.getParentSessionId();
      if (++guard > 10_000) {
        throw new IllegalStateException("session parent chain too deep: " + session.getId());
      }
    }
    return currentId;
  }
}
