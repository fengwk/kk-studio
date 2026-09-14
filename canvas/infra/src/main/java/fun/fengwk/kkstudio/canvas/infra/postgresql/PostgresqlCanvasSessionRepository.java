package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.canvas.CanvasSession;
import fun.fengwk.kkstudio.canvas.CanvasSessionRepository;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 基于 PostgreSQL 的 Canvas↔Session 归属仓库。 */
@Repository
public class PostgresqlCanvasSessionRepository implements CanvasSessionRepository {

  private final CanvasSessionMapper mapper;

  public PostgresqlCanvasSessionRepository(CanvasSessionMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  public boolean insert(UUID sessionId, UUID canvasId) {
    return mapper.insert(sessionId, canvasId) == 1;
  }

  @Override
  public List<UUID> listSessionIds(UUID canvasId) {
    return mapper.listSessionIds(canvasId);
  }

  @Override
  public CanvasSession findBySessionId(UUID sessionId) {
    CanvasSessionDO row = mapper.findBySessionId(sessionId);
    return row == null ? null : new CanvasSession(row.getSessionId(), row.getCanvasId());
  }

  @Override
  public int deleteBySessionId(UUID sessionId) {
    return mapper.deleteBySessionId(sessionId);
  }
}
