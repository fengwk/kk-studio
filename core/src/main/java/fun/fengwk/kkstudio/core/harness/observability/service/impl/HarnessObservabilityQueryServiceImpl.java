package fun.fengwk.kkstudio.core.harness.observability.service.impl;

import static fun.fengwk.kkstudio.core.harness.observability.service.ObservabilityLimits.DEFAULT_LIMIT;
import static fun.fengwk.kkstudio.core.harness.observability.service.ObservabilityLimits.normalizeLimit;
import static fun.fengwk.kkstudio.core.harness.observability.service.ObservabilityLimits.requireNonNegativeCursor;

import fun.fengwk.kkstudio.core.harness.observability.service.HarnessArtifactResolution;
import fun.fengwk.kkstudio.core.harness.observability.service.HarnessObservabilityQueryService;
import fun.fengwk.kkstudio.core.harness.run.store.MysqlHarnessRunStore;
import fun.fengwk.kkstudio.core.harness.session.store.mapper.HarnessSessionMapper;
import fun.fengwk.kkstudio.core.harness.session.support.HarnessIds;
import fun.fengwk.kkstudio.core.harness.task.store.DatabaseRootActivityStore;
import fun.fengwk.kkstudio.core.harness.task.store.mapper.HarnessSubagentTaskMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.MysqlToolInvocationStore;
import fun.fengwk.kkstudio.core.harness.tool.worker.DatabaseArtifactStore;
import fun.fengwk.kkstudio.harness.runtime.task.RootActivity;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.share.model.RootActivityDTO;
import fun.fengwk.kkstudio.share.model.RunEventDTO;
import fun.fengwk.kkstudio.share.model.SubagentTaskDTO;
import fun.fengwk.kkstudio.share.model.ToolInvocationDTO;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Default observability query implementation. Validates existence of Run/Session up front so the
 * controller can map to HTTP 404 deterministically.
 */
@Service
public class HarnessObservabilityQueryServiceImpl implements HarnessObservabilityQueryService {

  private final MysqlHarnessRunStore runStore;
  private final DatabaseRootActivityStore rootActivityStore;
  private final MysqlToolInvocationStore toolInvocationStore;
  private final HarnessSubagentTaskMapper subagentTaskMapper;
  private final HarnessSessionMapper sessionMapper;
  private final DatabaseArtifactStore artifactStore;
  private final HarnessObservabilityDtoConverter converter;

  public HarnessObservabilityQueryServiceImpl(
      MysqlHarnessRunStore runStore,
      DatabaseRootActivityStore rootActivityStore,
      MysqlToolInvocationStore toolInvocationStore,
      HarnessSubagentTaskMapper subagentTaskMapper,
      HarnessSessionMapper sessionMapper,
      DatabaseArtifactStore artifactStore,
      HarnessObservabilityDtoConverter converter) {
    this.runStore = runStore;
    this.rootActivityStore = rootActivityStore;
    this.toolInvocationStore = toolInvocationStore;
    this.subagentTaskMapper = subagentTaskMapper;
    this.sessionMapper = sessionMapper;
    this.artifactStore = artifactStore;
    this.converter = converter;
  }

  @Override
  public List<RunEventDTO> listRunEvents(String runId, long afterSequence, int limit) {
    long parsedRunId = HarnessIds.parsePositive(runId, "runId");
    requireNonNegativeCursor(afterSequence, "afterSequence");
    int boundedLimit = normalizeLimit(limit, DEFAULT_LIMIT);
    if (runStore.find(parsedRunId).isEmpty()) {
      throw new IllegalArgumentException("unknown run: " + runId);
    }
    return runStore.listAfter(parsedRunId, afterSequence, boundedLimit).stream()
        .map(converter::convert)
        .toList();
  }

  @Override
  public List<RootActivityDTO> listRootActivities(String sessionId, long afterEventId, int limit) {
    long parsedSessionId = HarnessIds.parsePositive(sessionId, "sessionId");
    requireNonNegativeCursor(afterEventId, "afterEventId");
    int boundedLimit = normalizeLimit(limit, DEFAULT_LIMIT);
    Long rootSessionId = resolveRootSessionId(parsedSessionId);
    if (rootSessionId == null) {
      throw new IllegalArgumentException("unknown session: " + sessionId);
    }
    List<RootActivity> rows = rootActivityStore.list(rootSessionId, afterEventId, boundedLimit);
    return rows.stream().map(converter::convert).toList();
  }

  @Override
  public List<ToolInvocationDTO> listToolInvocations(String runId) {
    long parsedRunId = HarnessIds.parsePositive(runId, "runId");
    if (runStore.find(parsedRunId).isEmpty()) {
      throw new IllegalArgumentException("unknown run: " + runId);
    }
    return toolInvocationStore.listByRun(parsedRunId).stream().map(converter::convert).toList();
  }

  @Override
  public ToolInvocationDTO getToolInvocation(String invocationId) {
    long parsed = HarnessIds.parsePositive(invocationId, "invocationId");
    Optional<ToolInvocation> found = toolInvocationStore.find(parsed);
    if (found.isEmpty()) {
      throw new IllegalArgumentException("unknown tool invocation: " + invocationId);
    }
    return converter.convert(found.get());
  }

  @Override
  public List<SubagentTaskDTO> listSessionTasks(String sessionId) {
    long parsedSessionId = HarnessIds.parsePositive(sessionId, "sessionId");
    if (sessionMapper.find(parsedSessionId) == null) {
      throw new IllegalArgumentException("unknown session: " + sessionId);
    }
    return subagentTaskMapper.listByParentSession(parsedSessionId).stream()
        .map(converter::convert)
        .toList();
  }

  @Override
  public HarnessArtifactResolution.Result resolveArtifact(String artifactId) {
    return new HarnessArtifactResolution(artifactStore).resolve(artifactId);
  }

  private Long resolveRootSessionId(long sessionId) {
    var row = sessionMapper.find(sessionId);
    if (row == null) {
      return null;
    }
    return row.getRootSessionId();
  }
}