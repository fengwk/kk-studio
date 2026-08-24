package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunStateCodecPort;

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
  private final CanvasFunctionRunStateCodecPort stateCodec;

  public PostgresqlCanvasFunctionRunRepository(
      CanvasFunctionRunMapper runMapper, CanvasFunctionRunStateCodecPort stateCodec) {
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
  public void insertReady(CanvasFunctionRun run) {
    requireStatus(run, CanvasFunctionRunStatus.READY);
    if (runMapper.insert(toData(run)) != 1) {
      throw new IllegalStateException("insert canvas function run failed: " + run.nodeId());
    }
  }

  @Override
  public boolean replaceTerminalWithReady(CanvasFunctionRun run) {
    requireStatus(run, CanvasFunctionRunStatus.READY);
    return runMapper.replaceTerminalWithReady(toData(run)) == 1;
  }

  @Override
  public boolean checkpoint(
      UUID nodeId,
      UUID requestId,
      String leaseToken,
      String stateJson,
      String stage,
      Instant updatedAt) {
    if (!stage.equals(stateCodec.stage(stateJson))) {
      throw new IllegalArgumentException("checkpoint stage must match stateJson");
    }
    return runMapper.checkpoint(
            nodeId,
            requestId,
            leaseToken,
            stateJson,
            OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC))
        == 1;
  }

  @Override
  public boolean transitionTerminal(CanvasFunctionRun run, String leaseToken) {
    if (run.status() == CanvasFunctionRunStatus.READY
        || run.status() == CanvasFunctionRunStatus.RUNNING) {
      throw new IllegalArgumentException("terminal transition requires terminal status");
    }
    return runMapper.transitionTerminal(toData(run), leaseToken) == 1;
  }

  @Override
  public boolean cancelActive(CanvasFunctionRun run) {
    requireStatus(run, CanvasFunctionRunStatus.CANCELLED);
    return runMapper.cancelActive(toData(run)) == 1;
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
        run.getAttempt(),
        toInstant(run.getAvailableAt()),
        run.getLeaseToken(),
        toInstant(run.getLeaseUntil()),
        stateCodec.stage(run.getStateJson()),
        run.getStateJson(),
        run.getError(),
        run.getUpdatedAt().toInstant(),
        run.getCreatedAt().toInstant());
  }

  private static CanvasFunctionRunDO toData(CanvasFunctionRun run) {
    CanvasFunctionRunDO data = new CanvasFunctionRunDO();
    data.setNodeId(run.nodeId());
    data.setRequestId(run.requestId());
    data.setStatus(run.status().name());
    data.setAttempt(run.attempt());
    data.setAvailableAt(toOffsetDateTime(run.availableAt()));
    data.setLeaseToken(run.leaseToken());
    data.setLeaseUntil(toOffsetDateTime(run.leaseUntil()));
    data.setStateJson(run.stateJson());
    data.setError(run.error());
    data.setUpdatedAt(OffsetDateTime.ofInstant(run.updatedAt(), ZoneOffset.UTC));
    data.setCreatedAt(OffsetDateTime.ofInstant(run.createdAt(), ZoneOffset.UTC));
    return data;
  }

  private static Instant toInstant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  private static OffsetDateTime toOffsetDateTime(Instant value) {
    return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
  }

  private static void requireStatus(CanvasFunctionRun run, CanvasFunctionRunStatus expectedStatus) {
    if (run.status() != expectedStatus) {
      throw new IllegalArgumentException("FunctionRun status must be " + expectedStatus);
    }
  }
}
