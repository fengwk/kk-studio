package fun.fengwk.kkstudio.core.studio.repo.impl;

import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.core.studio.function.CanvasFunctionRunStateCodec;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasFunctionRunMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasFunctionRunDO;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@code canvas_function_run} 的持久化实现。 */
@Repository
public class PostgresqlCanvasFunctionRunRepository implements CanvasFunctionRunRepository {

  private final CanvasFunctionRunMapper runMapper;
  private final CanvasFunctionRunStateCodec stateCodec;

  public PostgresqlCanvasFunctionRunRepository(
      CanvasFunctionRunMapper runMapper, CanvasFunctionRunStateCodec stateCodec) {
    this.runMapper = Objects.requireNonNull(runMapper, "runMapper");
    this.stateCodec = Objects.requireNonNull(stateCodec, "stateCodec");
  }

  @Override
  public Optional<CanvasFunctionRun> findByNodeId(UUID nodeId) {
    return Optional.ofNullable(runMapper.getByNodeId(nodeId)).map(this::toDomain);
  }

  @Override
  public Optional<CanvasFunctionRun> findByNodeIdForUpdate(UUID nodeId) {
    return Optional.ofNullable(runMapper.getByNodeIdForUpdate(nodeId)).map(this::toDomain);
  }

  @Override
  public List<CanvasFunctionRun> findByCanvasId(UUID canvasId) {
    List<CanvasFunctionRun> runs = new ArrayList<>();
    for (CanvasFunctionRunDO run : runMapper.listByCanvas(canvasId)) {
      runs.add(toDomain(run));
    }
    return List.copyOf(runs);
  }

  @Override
  public List<CanvasFunctionRun> findRunning() {
    List<CanvasFunctionRun> runs = new ArrayList<>();
    for (CanvasFunctionRunDO run : runMapper.listRunning()) {
      runs.add(toDomain(run));
    }
    return List.copyOf(runs);
  }

  @Override
  public void insertRunning(CanvasFunctionRun run) {
    requireStatus(run, CanvasFunctionRunStatus.RUNNING);
    if (runMapper.insert(toData(run)) != 1) {
      throw new IllegalStateException("insert canvas function run failed: " + run.nodeId());
    }
  }

  @Override
  public boolean replaceTerminalWithRunning(CanvasFunctionRun run) {
    requireStatus(run, CanvasFunctionRunStatus.RUNNING);
    return runMapper.replaceTerminalWithRunning(toData(run)) == 1;
  }

  @Override
  public boolean checkpoint(
      UUID nodeId, UUID requestId, String stateJson, String stage, Instant updatedAt) {
    if (!stage.equals(stateCodec.stage(stateJson))) {
      throw new IllegalArgumentException("checkpoint stage must match stateJson");
    }
    return runMapper.checkpoint(
            nodeId, requestId, stateJson, OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC))
        == 1;
  }

  @Override
  public boolean transitionTerminal(CanvasFunctionRun run) {
    if (run.status() == CanvasFunctionRunStatus.RUNNING) {
      throw new IllegalArgumentException("terminal transition requires terminal status");
    }
    return runMapper.transitionTerminal(toData(run)) == 1;
  }

  @Override
  public boolean deleteByNodeId(UUID nodeId) {
    return runMapper.deleteByNodeId(nodeId) >= 0;
  }

  @Override
  public boolean deleteByCanvasId(UUID canvasId) {
    return runMapper.deleteByCanvas(canvasId) >= 0;
  }

  private CanvasFunctionRun toDomain(CanvasFunctionRunDO run) {
    return new CanvasFunctionRun(
        run.getNodeId(),
        run.getRequestId(),
        CanvasFunctionRunStatus.valueOf(run.getStatus()),
        stateCodec.stage(run.getStateJson()),
        run.getStateJson(),
        run.getError(),
        run.getUpdatedAt().toInstant());
  }

  private static CanvasFunctionRunDO toData(CanvasFunctionRun run) {
    CanvasFunctionRunDO data = new CanvasFunctionRunDO();
    data.setNodeId(run.nodeId());
    data.setRequestId(run.requestId());
    data.setStatus(run.status().name());
    data.setStateJson(run.stateJson());
    data.setError(run.error());
    data.setUpdatedAt(OffsetDateTime.ofInstant(run.updatedAt(), ZoneOffset.UTC));
    return data;
  }

  private static void requireStatus(CanvasFunctionRun run, CanvasFunctionRunStatus expectedStatus) {
    if (run.status() != expectedStatus) {
      throw new IllegalArgumentException("FunctionRun status must be " + expectedStatus);
    }
  }
}
