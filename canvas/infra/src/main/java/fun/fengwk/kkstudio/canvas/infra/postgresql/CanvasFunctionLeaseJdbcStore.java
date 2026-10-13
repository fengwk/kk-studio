package fun.fengwk.kkstudio.canvas.infra.postgresql;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** {@code canvas_function_run} durable claim/lease 的原子 SQL 入口。 */
@Repository
public class CanvasFunctionLeaseJdbcStore {

  private final JdbcClient jdbc;

  public CanvasFunctionLeaseJdbcStore(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  public Optional<CanvasFunctionRunDO> claimNext(
      OffsetDateTime now, String leaseToken, OffsetDateTime leaseUntil) {
    return jdbc.sql(
            """
            with candidate as (
                select node_id
                from canvas_function_run
                where (status = 'READY' and available_at <= :now)
                   or (status = 'RUNNING' and lease_until <= :now)
                order by
                    case when status = 'READY' then available_at else lease_until end,
                    created_at,
                    node_id
                for update skip locked
                limit 1
            )
            update canvas_function_run run
            set status = 'RUNNING',
                attempt = run.attempt + 1,
                available_at = null,
                lease_token = :leaseToken,
                lease_until = :leaseUntil,
                updated_at = :now
            from candidate
            where run.node_id = candidate.node_id
            returning run.node_id, run.request_id, run.status, run.attempt, run.available_at,
                      run.lease_token, run.lease_until, run.state_json, run.error, run.updated_at,
                      run.created_at
            """)
        .param("now", now)
        .param("leaseToken", leaseToken)
        .param("leaseUntil", leaseUntil)
        .query(CanvasFunctionRunJdbcStore.ROW_MAPPER)
        .optional();
  }

  public int renew(
      UUID nodeId,
      UUID requestId,
      String leaseToken,
      OffsetDateTime now,
      OffsetDateTime leaseUntil) {
    return jdbc.sql(
            """
            update canvas_function_run
            set lease_until = :leaseUntil
            where node_id = :nodeId
              and request_id = :requestId
              and status = 'RUNNING'
              and lease_token = :leaseToken
              and lease_until > :now
              and :leaseUntil > lease_until
            """)
        .param("nodeId", nodeId)
        .param("requestId", requestId)
        .param("leaseToken", leaseToken)
        .param("now", now)
        .param("leaseUntil", leaseUntil)
        .update();
  }

  public int reschedule(
      UUID nodeId,
      UUID requestId,
      String leaseToken,
      OffsetDateTime now,
      OffsetDateTime availableAt) {
    return jdbc.sql(
            """
            update canvas_function_run
            set status = 'READY',
                available_at = :availableAt,
                lease_token = null,
                lease_until = null,
                updated_at = :now
            where node_id = :nodeId
              and request_id = :requestId
              and status = 'RUNNING'
              and lease_token = :leaseToken
              and lease_until > :now
            """)
        .param("nodeId", nodeId)
        .param("requestId", requestId)
        .param("leaseToken", leaseToken)
        .param("now", now)
        .param("availableAt", availableAt)
        .update();
  }

  public int countOwned(UUID nodeId, UUID requestId, String leaseToken, OffsetDateTime now) {
    return jdbc.sql(
            """
            select count(*)
            from canvas_function_run
            where node_id = :nodeId
              and request_id = :requestId
              and status = 'RUNNING'
              and lease_token = :leaseToken
              and lease_until > :now
            """)
        .param("nodeId", nodeId)
        .param("requestId", requestId)
        .param("leaseToken", leaseToken)
        .param("now", now)
        .query((resultSet, rowNumber) -> resultSet.getInt(1))
        .single();
  }
}
