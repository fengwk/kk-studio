package fun.fengwk.kkstudio.core.harness.observability.service.impl;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.harness.observability.service.HarnessObservabilityQueryService;
import fun.fengwk.kkstudio.core.harness.observability.service.ObservabilityLimits;
import fun.fengwk.kkstudio.core.harness.session.support.HarnessIds;
import fun.fengwk.kkstudio.core.harness.task.store.mapper.HarnessSubagentTaskMapper;
import fun.fengwk.kkstudio.core.harness.thread.store.mapper.HarnessThreadEventMapper;
import fun.fengwk.kkstudio.core.harness.tool.worker.PostgresqlToolInvocationMapper;
import fun.fengwk.kkstudio.harness.runtime.task.RootActivityStore;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.Artifact;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ArtifactStore;
import fun.fengwk.kkstudio.share.model.RootActivityDTO;
import fun.fengwk.kkstudio.share.model.SubagentTaskDTO;
import fun.fengwk.kkstudio.share.model.ThreadEventDTO;
import fun.fengwk.kkstudio.share.model.ToolInvocationDTO;

import java.util.List;
import java.util.Objects;

@Service
public class HarnessObservabilityQueryServiceImpl implements HarnessObservabilityQueryService {
  private final HarnessThreadEventMapper eventMapper;
  private final PostgresqlToolInvocationMapper invocationMapper;
  private final HarnessSubagentTaskMapper taskMapper;
  private final RootActivityStore rootActivityStore;
  private final ArtifactStore artifactStore;
  private final HarnessObservabilityDtoConverter converter;

  public HarnessObservabilityQueryServiceImpl(
      HarnessThreadEventMapper eventMapper,
      PostgresqlToolInvocationMapper invocationMapper,
      HarnessSubagentTaskMapper taskMapper,
      RootActivityStore rootActivityStore,
      ArtifactStore artifactStore,
      HarnessObservabilityDtoConverter converter) {
    this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper");
    this.invocationMapper = Objects.requireNonNull(invocationMapper, "invocationMapper");
    this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper");
    this.rootActivityStore = Objects.requireNonNull(rootActivityStore, "rootActivityStore");
    this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
    this.converter = Objects.requireNonNull(converter, "converter");
  }

  @Override
  public List<ThreadEventDTO> listThreadEvents(String threadId, long afterEventId, int limit) {
    long id = HarnessIds.parsePositive(threadId, "threadId");
    ObservabilityLimits.requireNonNegativeCursor(afterEventId, "afterEventId");
    int page = ObservabilityLimits.normalizeLimit(limit, ObservabilityLimits.DEFAULT_LIMIT);
    return eventMapper.listAfter(id, afterEventId, page).stream().map(converter::convert).toList();
  }

  @Override
  public List<RootActivityDTO> listRootActivities(String sessionId, long afterEventId, int limit) {
    long id = HarnessIds.parsePositive(sessionId, "sessionId");
    int page = ObservabilityLimits.normalizeLimit(limit, ObservabilityLimits.DEFAULT_LIMIT);
    return rootActivityStore.list(id, afterEventId, page).stream().map(converter::convert).toList();
  }

  @Override
  public List<ToolInvocationDTO> listToolInvocations(String threadId) {
    long id = HarnessIds.parsePositive(threadId, "threadId");
    return invocationMapper.listByThread(id).stream().map(converter::convert).toList();
  }

  @Override
  public ToolInvocationDTO getToolInvocation(String invocationId) {
    long id = HarnessIds.parsePositive(invocationId, "invocationId");
    var row = invocationMapper.find(id);
    if (row == null) {
      throw new IllegalArgumentException("unknown tool invocation: " + invocationId);
    }
    return converter.convert(row);
  }

  @Override
  public List<SubagentTaskDTO> listSessionTasks(String sessionId) {
    long id = HarnessIds.parsePositive(sessionId, "sessionId");
    return taskMapper.listByParentSession(id).stream().map(converter::convert).toList();
  }

  @Override
  public Artifact getArtifact(String artifactId) {
    HarnessIds.parsePositive(artifactId, "artifactId");
    return artifactStore
        .find(artifactId)
        .orElseThrow(() -> new IllegalArgumentException("unknown artifact: " + artifactId));
  }
}
