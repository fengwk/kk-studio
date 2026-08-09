package fun.fengwk.kkstudio.core.studio.repo.impl;

import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasFunctionRunMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasNodeMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasFunctionRunDO;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasNodeDO;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunStatus;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class PostgresqlCanvasFunctionRunRepository implements CanvasFunctionRunRepository {

  private final CanvasFunctionRunMapper runMapper;
  private final CanvasNodeMapper nodeMapper;

  public PostgresqlCanvasFunctionRunRepository(
      CanvasFunctionRunMapper runMapper, CanvasNodeMapper nodeMapper) {
    this.runMapper = runMapper;
    this.nodeMapper = nodeMapper;
  }

  @Override
  public Optional<CanvasFunctionRun> findByNodeId(long nodeId) {
    return Optional.ofNullable(runMapper.getByNodeId(nodeId))
        .map(PostgresqlCanvasFunctionRunRepository::toDomain);
  }

  @Override
  public List<CanvasFunctionRun> findByCanvasId(long canvasId) {
    List<CanvasFunctionRun> runs = new ArrayList<>();
    for (CanvasFunctionRunDO run : runMapper.listByCanvas(canvasId)) {
      runs.add(toDomain(run));
    }
    return List.copyOf(runs);
  }

  @Override
  public void save(CanvasFunctionRun run) {
    CanvasNodeDO node = nodeMapper.getByGlobalId(run.nodeId());
    if (node == null || node.getModelKey() == null) {
      throw new IllegalArgumentException("FunctionRun node must have a Function");
    }
    CanvasFunctionRunDO data = new CanvasFunctionRunDO();
    data.setNodeId(run.nodeId());
    data.setRequestId(run.requestId());
    data.setStatus(run.status().name());
    data.setStateJson(run.stateJson());
    data.setError(run.error());
    data.setUpdatedAt(OffsetDateTime.ofInstant(run.updatedAt(), ZoneOffset.UTC));
    runMapper.upsert(data);
  }

  private static CanvasFunctionRun toDomain(CanvasFunctionRunDO run) {
    return new CanvasFunctionRun(
        run.getNodeId(),
        run.getRequestId(),
        CanvasFunctionRunStatus.valueOf(run.getStatus()),
        run.getStateJson(),
        run.getError(),
        run.getUpdatedAt().toInstant());
  }
}
