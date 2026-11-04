package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@code canvas_command_dedup} 的原子 SQL 入口。 */
@Repository
public class CanvasCommandDedupJdbcStore {

  private static final RowMapper<CanvasCommandDedupDO> ROW_MAPPER =
      (resultSet, rowNumber) -> {
        CanvasCommandDedupDO dedup = new CanvasCommandDedupDO();
        dedup.setCanvasId(resultSet.getObject("canvas_id", UUID.class));
        dedup.setIdempotencyKey(resultSet.getObject("idempotency_key", UUID.class));
        dedup.setRequestHash(resultSet.getString("request_hash"));
        dedup.setAcceptedRevision(resultSet.getLong("accepted_revision"));
        return dedup;
      };

  private final JdbcClient jdbc;

  public CanvasCommandDedupJdbcStore(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  public int insert(CanvasCommandDedupDO dedup) {
    return jdbc.sql(
            """
            insert into canvas_command_dedup (
                canvas_id, idempotency_key, request_hash, accepted_revision
            ) values (:canvasId, :idempotencyKey, :requestHash, :acceptedRevision)
            """)
        .param("canvasId", dedup.getCanvasId())
        .param("idempotencyKey", dedup.getIdempotencyKey())
        .param("requestHash", dedup.getRequestHash())
        .param("acceptedRevision", dedup.getAcceptedRevision())
        .update();
  }

  public Optional<CanvasCommandDedupDO> findById(UUID canvasId, UUID idempotencyKey) {
    return jdbc.sql(
            """
            select canvas_id, idempotency_key, request_hash, accepted_revision
            from canvas_command_dedup
            where canvas_id = :canvasId and idempotency_key = :idempotencyKey
            """)
        .param("canvasId", canvasId)
        .param("idempotencyKey", idempotencyKey)
        .query(ROW_MAPPER)
        .optional();
  }

  public int deleteByCanvas(UUID canvasId) {
    return jdbc.sql("delete from canvas_command_dedup where canvas_id = :canvasId")
        .param("canvasId", canvasId)
        .update();
  }
}
