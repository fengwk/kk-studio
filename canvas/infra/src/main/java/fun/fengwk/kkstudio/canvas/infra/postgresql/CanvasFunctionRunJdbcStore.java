package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@code canvas_function_run} 的原子 SQL 入口。 */
@Repository
public class CanvasFunctionRunJdbcStore {

  private static final String COLUMNS =
      "node_id, request_id, status, attempt, available_at, lease_token, lease_until,"
          + " state_json, error, updated_at, created_at";

  /** {@code canvas_function_run} 行的唯一映射：claim/lease 语句复用同一投影，不定义第二份行形状。 */
  static final RowMapper<CanvasFunctionRunDO> ROW_MAPPER =
      (resultSet, rowNumber) -> {
        CanvasFunctionRunDO run = new CanvasFunctionRunDO();
        run.setNodeId(resultSet.getObject("node_id", UUID.class));
        run.setRequestId(resultSet.getObject("request_id", UUID.class));
        run.setStatus(resultSet.getString("status"));
        run.setAttempt(resultSet.getInt("attempt"));
        run.setAvailableAt(resultSet.getObject("available_at", OffsetDateTime.class));
        run.setLeaseToken(resultSet.getString("lease_token"));
        run.setLeaseUntil(resultSet.getObject("lease_until", OffsetDateTime.class));
        run.setStateJson(resultSet.getString("state_json"));
        run.setError(resultSet.getString("error"));
        run.setUpdatedAt(resultSet.getObject("updated_at", OffsetDateTime.class));
        run.setCreatedAt(resultSet.getObject("created_at", OffsetDateTime.class));
        return run;
      };

  private final JdbcClient jdbc;

  public CanvasFunctionRunJdbcStore(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  public int insert(CanvasFunctionRunDO run) {
    return jdbc.sql(
            """
            insert into canvas_function_run (
                node_id, request_id, status, attempt, available_at, lease_token, lease_until,
                state_json, error, updated_at, created_at)
            values (
                :nodeId, :requestId, :status, :attempt, :availableAt, :leaseToken,
                :leaseUntil, cast(:stateJson as jsonb), :error, :updatedAt, :createdAt)
            """)
        .param("nodeId", run.getNodeId())
        .param("requestId", run.getRequestId())
        .param("status", run.getStatus())
        .param("attempt", run.getAttempt())
        .param("availableAt", run.getAvailableAt())
        .param("leaseToken", run.getLeaseToken())
        .param("leaseUntil", run.getLeaseUntil())
        .param("stateJson", run.getStateJson())
        .param("error", run.getError())
        .param("updatedAt", run.getUpdatedAt())
        .param("createdAt", run.getCreatedAt())
        .update();
  }

  public Optional<CanvasFunctionRunDO> findByNodeId(UUID nodeId) {
    return jdbc.sql("select " + COLUMNS + " from canvas_function_run where node_id = :nodeId")
        .param("nodeId", nodeId)
        .query(ROW_MAPPER)
        .optional();
  }

  public Optional<CanvasFunctionRunDO> findByNodeIdForUpdate(UUID nodeId) {
    return jdbc.sql(
            "select " + COLUMNS + " from canvas_function_run where node_id = :nodeId for update")
        .param("nodeId", nodeId)
        .query(ROW_MAPPER)
        .optional();
  }

  public boolean ownsRunningRequest(UUID canvasId, UUID nodeId, UUID requestId, String leaseToken) {
    return jdbc.sql(
            """
            select exists (
              select 1 from canvas_function_run r
              join canvas_node n on n.id = r.node_id
              where n.canvas_id = :canvasId and r.node_id = :nodeId
                and r.request_id = :requestId and r.status = 'RUNNING'
                and r.lease_token = :leaseToken and r.lease_until > clock_timestamp()
            )
            """)
        .param("canvasId", canvasId)
        .param("nodeId", nodeId)
        .param("requestId", requestId)
        .param("leaseToken", leaseToken)
        .query((resultSet, rowNumber) -> resultSet.getBoolean(1))
        .single();
  }

  public List<CanvasFunctionRunDO> listByCanvas(UUID canvasId) {
    return jdbc.sql(
            """
            select r.node_id, r.request_id, r.status, r.attempt, r.available_at, r.lease_token,
                   r.lease_until, r.state_json, r.error, r.updated_at, r.created_at
            from canvas_function_run r
            join canvas_node n on n.id = r.node_id
            where n.canvas_id = :canvasId
            order by r.node_id
            """)
        .param("canvasId", canvasId)
        .query(ROW_MAPPER)
        .list();
  }

  public int replaceTerminalWithReady(CanvasFunctionRunDO run) {
    return jdbc.sql(
            """
            update canvas_function_run
            set request_id = :requestId, status = :status, attempt = :attempt,
                available_at = :availableAt, lease_token = :leaseToken, lease_until = :leaseUntil,
                state_json = cast(:stateJson as jsonb), error = :error, updated_at = :updatedAt,
                created_at = :createdAt
            where node_id = :nodeId and status in ('SUCCEEDED', 'FAILED', 'CANCELLED')
            """)
        .param("nodeId", run.getNodeId())
        .param("requestId", run.getRequestId())
        .param("status", run.getStatus())
        .param("attempt", run.getAttempt())
        .param("availableAt", run.getAvailableAt())
        .param("leaseToken", run.getLeaseToken())
        .param("leaseUntil", run.getLeaseUntil())
        .param("stateJson", run.getStateJson())
        .param("error", run.getError())
        .param("updatedAt", run.getUpdatedAt())
        .param("createdAt", run.getCreatedAt())
        .update();
  }

  public int checkpoint(
      UUID nodeId, UUID requestId, String leaseToken, String stateJson, OffsetDateTime updatedAt) {
    return jdbc.sql(
            """
            update canvas_function_run
            set state_json = cast(:stateJson as jsonb), updated_at = :updatedAt
            where node_id = :nodeId
              and request_id = :requestId
              and status = 'RUNNING'
              and lease_token = :leaseToken
              and lease_until > :updatedAt
            """)
        .param("nodeId", nodeId)
        .param("requestId", requestId)
        .param("leaseToken", leaseToken)
        .param("stateJson", stateJson)
        .param("updatedAt", updatedAt)
        .update();
  }

  public int transitionTerminal(CanvasFunctionRunDO run, String leaseToken) {
    return jdbc.sql(
            """
            update canvas_function_run
            set status = :status, state_json = cast(:stateJson as jsonb),
                error = :error, available_at = null, lease_token = null, lease_until = null,
                updated_at = :updatedAt
            where node_id = :nodeId
              and request_id = :requestId
              and status = 'RUNNING'
              and lease_token = :leaseToken
              and lease_until > :updatedAt
            """)
        .param("nodeId", run.getNodeId())
        .param("requestId", run.getRequestId())
        .param("status", run.getStatus())
        .param("stateJson", run.getStateJson())
        .param("error", run.getError())
        .param("updatedAt", run.getUpdatedAt())
        .param("leaseToken", leaseToken)
        .update();
  }

  public int markUnknown(CanvasFunctionRunDO run, String leaseToken) {
    return jdbc.sql(
            """
            update canvas_function_run
            set status = 'UNKNOWN', state_json = cast(:stateJson as jsonb), error = :error,
                available_at = null, lease_token = null, lease_until = null, updated_at = :updatedAt
            where node_id = :nodeId
              and request_id = :requestId
              and status = 'RUNNING'
              and lease_token = :leaseToken
              and lease_until > :updatedAt
            """)
        .param("nodeId", run.getNodeId())
        .param("requestId", run.getRequestId())
        .param("stateJson", run.getStateJson())
        .param("error", run.getError())
        .param("updatedAt", run.getUpdatedAt())
        .param("leaseToken", leaseToken)
        .update();
  }

  public int resumeUnknown(
      UUID nodeId,
      UUID requestId,
      String stateJson,
      OffsetDateTime availableAt,
      OffsetDateTime updatedAt) {
    return jdbc.sql(
            """
            update canvas_function_run
            set status = 'READY', state_json = cast(:stateJson as jsonb), error = null,
                available_at = :availableAt, lease_token = null, lease_until = null,
                updated_at = :updatedAt
            where node_id = :nodeId
              and request_id = :requestId
              and status = 'UNKNOWN'
            """)
        .param("nodeId", nodeId)
        .param("requestId", requestId)
        .param("stateJson", stateJson)
        .param("availableAt", availableAt)
        .param("updatedAt", updatedAt)
        .update();
  }

  public int resolveUnknownTerminal(CanvasFunctionRunDO run) {
    return jdbc.sql(
            """
            update canvas_function_run
            set status = :status, state_json = cast(:stateJson as jsonb), error = :error,
                available_at = null, lease_token = null, lease_until = null, updated_at = :updatedAt
            where node_id = :nodeId
              and request_id = :requestId
              and status = 'UNKNOWN'
            """)
        .param("nodeId", run.getNodeId())
        .param("requestId", run.getRequestId())
        .param("status", run.getStatus())
        .param("stateJson", run.getStateJson())
        .param("error", run.getError())
        .param("updatedAt", run.getUpdatedAt())
        .update();
  }

  public int cancelActive(CanvasFunctionRunDO run) {
    return jdbc.sql(
            """
            update canvas_function_run
            set status = :status, state_json = cast(:stateJson as jsonb), error = :error,
                available_at = null, lease_token = null, lease_until = null, updated_at = :updatedAt
            where node_id = :nodeId
              and request_id = :requestId
              and status in ('READY', 'RUNNING')
            """)
        .param("nodeId", run.getNodeId())
        .param("requestId", run.getRequestId())
        .param("status", run.getStatus())
        .param("stateJson", run.getStateJson())
        .param("error", run.getError())
        .param("updatedAt", run.getUpdatedAt())
        .update();
  }

  public int deleteByNodeId(UUID nodeId) {
    return jdbc.sql("delete from canvas_function_run where node_id = :nodeId")
        .param("nodeId", nodeId)
        .update();
  }

  public int deleteByCanvas(UUID canvasId) {
    return jdbc.sql(
            """
            delete from canvas_function_run r
            using canvas_node n
            where r.node_id = n.id and n.canvas_id = :canvasId
            """)
        .param("canvasId", canvasId)
        .update();
  }
}
