package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code canvas_function_run} 的原子 SQL 入口。
 *
 * <p>Run 行只保存业务状态；调度与执行所有权在共享的 {@code durable_work}（target type {@code CANVAS_RUN}）。持有者的写入都以 {@link
 * #OWNED} 围栏：同一 lease token 在数据库时钟下仍有效，节点时钟不参与判断。
 */
@Repository
public class CanvasFunctionRunJdbcStore {

  /** 持有者写入的所有权围栏：本 Run 的 CANVAS_RUN Work 仍由 {@code :leaseToken} 持有且租约未过期。 */
  static final String OWNED =
      " exists (select 1 from durable_work w"
          + " where w.target_type = 'CANVAS_RUN' and w.target_id = canvas_function_run.node_id"
          + " and w.lease_token = :leaseToken"
          + " and w.lease_until > date_trunc('milliseconds', statement_timestamp()))";

  private static final String COLUMNS =
      "node_id, request_id, status, attempt, state_json, error, updated_at, created_at";

  /** {@code canvas_function_run} 行的唯一映射：claim/lease 语句复用同一投影，不定义第二份行形状。 */
  static final RowMapper<CanvasFunctionRunDO> ROW_MAPPER =
      (resultSet, rowNumber) -> {
        CanvasFunctionRunDO run = new CanvasFunctionRunDO();
        run.setNodeId(resultSet.getObject("node_id", UUID.class));
        run.setRequestId(resultSet.getObject("request_id", UUID.class));
        run.setStatus(resultSet.getString("status"));
        run.setAttempt(resultSet.getInt("attempt"));
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
                node_id, request_id, status, attempt, state_json, error, updated_at, created_at)
            values (
                :nodeId, :requestId, :status, :attempt, cast(:stateJson as jsonb), :error,
                :updatedAt, :createdAt)
            """)
        .param("nodeId", run.getNodeId())
        .param("requestId", run.getRequestId())
        .param("status", run.getStatus())
        .param("attempt", run.getAttempt())
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
              select 1 from canvas_function_run
              join canvas_node n on n.id = canvas_function_run.node_id
              where n.canvas_id = :canvasId and canvas_function_run.node_id = :nodeId
                and canvas_function_run.request_id = :requestId
                and canvas_function_run.status = 'RUNNING'
                and """
                + OWNED
                + """
            )
            """)
        .param("canvasId", canvasId)
        .param("nodeId", nodeId)
        .param("requestId", requestId)
        .param("leaseToken", leaseToken)
        .query((resultSet, rowNumber) -> resultSet.getBoolean(1))
        .single();
  }

  /** 以数据库时钟判断 {@code leaseToken} 是否仍持有该 Run 的 CANVAS_RUN Work 租约。 */
  public boolean holdsLease(UUID nodeId, String leaseToken) {
    return jdbc.sql(
            "select exists (select 1 from canvas_function_run where node_id = :nodeId and "
                + OWNED
                + ")")
        .param("nodeId", nodeId)
        .param("leaseToken", leaseToken)
        .query((resultSet, rowNumber) -> resultSet.getBoolean(1))
        .single();
  }

  public List<CanvasFunctionRunDO> listByCanvas(UUID canvasId) {
    return jdbc.sql(
            """
            select r.node_id, r.request_id, r.status, r.attempt, r.state_json, r.error,
                   r.updated_at, r.created_at
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
                state_json = cast(:stateJson as jsonb), error = :error, updated_at = :updatedAt,
                created_at = :createdAt
            where node_id = :nodeId and status in ('SUCCEEDED', 'FAILED', 'CANCELLED')
            """)
        .param("nodeId", run.getNodeId())
        .param("requestId", run.getRequestId())
        .param("status", run.getStatus())
        .param("attempt", run.getAttempt())
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
              and """
                + OWNED)
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
                error = :error, updated_at = :updatedAt
            where node_id = :nodeId
              and request_id = :requestId
              and status = 'RUNNING'
              and """
                + OWNED)
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
                updated_at = :updatedAt
            where node_id = :nodeId
              and request_id = :requestId
              and status = 'RUNNING'
              and """
                + OWNED)
        .param("nodeId", run.getNodeId())
        .param("requestId", run.getRequestId())
        .param("stateJson", run.getStateJson())
        .param("error", run.getError())
        .param("updatedAt", run.getUpdatedAt())
        .param("leaseToken", leaseToken)
        .update();
  }

  public int resumeUnknown(
      UUID nodeId, UUID requestId, String stateJson, OffsetDateTime updatedAt) {
    return jdbc.sql(
            """
            update canvas_function_run
            set status = 'READY', state_json = cast(:stateJson as jsonb), error = null,
                updated_at = :updatedAt
            where node_id = :nodeId
              and request_id = :requestId
              and status = 'UNKNOWN'
            """)
        .param("nodeId", nodeId)
        .param("requestId", requestId)
        .param("stateJson", stateJson)
        .param("updatedAt", updatedAt)
        .update();
  }

  public int resolveUnknownTerminal(CanvasFunctionRunDO run) {
    return jdbc.sql(
            """
            update canvas_function_run
            set status = :status, state_json = cast(:stateJson as jsonb), error = :error,
                updated_at = :updatedAt
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
                updated_at = :updatedAt
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

  /** 领取后开始新一次尝试：READY 或租约过期遗留的 RUNNING 进入 RUNNING 并递增 attempt；其余状态不写入。 */
  public Optional<CanvasFunctionRunDO> startAttempt(UUID nodeId, OffsetDateTime updatedAt) {
    return jdbc.sql(
            """
            update canvas_function_run
            set status = 'RUNNING', attempt = attempt + 1, updated_at = :updatedAt
            where node_id = :nodeId and status in ('READY', 'RUNNING')
            returning
            """
                + COLUMNS)
        .param("nodeId", nodeId)
        .param("updatedAt", updatedAt)
        .query(ROW_MAPPER)
        .optional();
  }

  /** 持有者归还 claim 时把 RUNNING 退回 READY，等待下一次领取。 */
  public int returnToReady(UUID nodeId, String leaseToken, OffsetDateTime updatedAt) {
    return jdbc.sql(
            """
            update canvas_function_run
            set status = 'READY', updated_at = :updatedAt
            where node_id = :nodeId
              and status = 'RUNNING'
              and """
                + OWNED)
        .param("nodeId", nodeId)
        .param("leaseToken", leaseToken)
        .param("updatedAt", updatedAt)
        .update();
  }

  /** 同一 Canvas 下全部 Run 的节点 id；调用方用它删除对应调度行，本类不写 {@code durable_work}。 */
  public List<UUID> listNodeIdsByCanvas(UUID canvasId) {
    return jdbc.sql(
            """
            select r.node_id
            from canvas_function_run r
            join canvas_node n on n.id = r.node_id
            where n.canvas_id = :canvasId
            order by r.node_id
            """)
        .param("canvasId", canvasId)
        .query((resultSet, rowNumber) -> resultSet.getObject("node_id", UUID.class))
        .list();
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
