package fun.fengwk.kkstudio.platform.environment.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.environment.directory.LocalDirectoryQueryPort;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentDirectoryFailureCode;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentDirectoryListResult;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentDirectoryDTO;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 跨节点只读 Environment 目录查询协调器（弱交付信箱 + PostgreSQL NOTIFY + 本地执行）。
 *
 * <p>基于 KISS weak-delivery 契约：
 *
 * <ol>
 *   <li>Requester 插入 PENDING 查询，带硬截止时间 {@code deadline_at = now + timeout}；
 *   <li>持有 READY 路由的 owner 节点通过 {@code for update skip locked} 原子认领（更新为 RUNNING）；
 *   <li>运行中 owner 崩溃绝不重领（crash non-reclaim，只领 PENDING）；
 *   <li>执行完成后 owner 回填 SUCCEEDED/FAILED 并发出 response 通知；
 *   <li>Requester 收到通知后以 {@code DELETE RETURNING} 原子领取终态结果；
 *   <li>Requester 超时或过期未领取的记录由 requester 或 periodic resync 直接 {@code DELETE}。
 * </ol>
 */
@Slf4j
@Service
public class EnvironmentQueryCoordinator {

  public static final String REQUEST_CHANNEL = "environment_directory_query_request";
  public static final String RESPONSE_CHANNEL = "environment_directory_query_response";

  private static final String INSERT_QUERY_SQL =
      """
      insert into environment_directory_query (
          id, environment_id, path, status, deadline_at, created_at
      ) values (
          ?, ?, ?, 'PENDING', statement_timestamp() + (? * interval '1 millisecond'), statement_timestamp()
      )
      """;

  private static final String NOTIFY_SQL = "select pg_notify(?, ?)";

  private static final String CLAIM_QUERY_SQL =
      """
      with candidate as (
          select eq.id
          from environment_directory_query eq
          join environment_connection ec on ec.environment_id = eq.environment_id
          where eq.environment_id = ?
            and ec.owner_node_id = ?
            and ec.status = 'READY'
            and ec.lease_until > statement_timestamp()
            and eq.status = 'PENDING'
            and eq.deadline_at > statement_timestamp()
          order by eq.created_at asc
          for update of eq skip locked
          limit 1
      )
      update environment_directory_query
      set status = 'RUNNING'
      from candidate
      where environment_directory_query.id = candidate.id
      returning environment_directory_query.id, environment_directory_query.environment_id,
                environment_directory_query.path, environment_directory_query.deadline_at
      """;

  private static final String COMPLETE_QUERY_SQL =
      """
      update environment_directory_query
      set status = 'SUCCEEDED',
          result = ?::jsonb
      where id = ?
        and status = 'RUNNING'
        and deadline_at > statement_timestamp()
      """;

  private static final String FAIL_QUERY_SQL =
      """
      update environment_directory_query
      set status = 'FAILED',
          failure_code = ?,
          failure_message = ?
      where id = ?
        and status = 'RUNNING'
        and deadline_at > statement_timestamp()
      """;

  private static final String DELETE_AND_FETCH_TERMINAL_SQL =
      """
      delete from environment_directory_query
      where id = ?
        and status in ('SUCCEEDED', 'FAILED')
      returning status, result::text, failure_code, failure_message
      """;

  private static final String DELETE_QUERY_BY_ID_SQL =
      """
      delete from environment_directory_query
      where id = ?
      """;

  private static final String CLEANUP_DEADLINE_QUERIES_SQL =
      """
      delete from environment_directory_query
      where deadline_at <= statement_timestamp()
      """;

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final UUID ownerNodeId;
  private final LocalDirectoryQueryPort localDirectoryQueryPort;
  private final Map<UUID, PendingQuery> pendingQueries = new ConcurrentHashMap<>();
  private final Map<EnvironmentId, QueryDrain> queryDrains = new ConcurrentHashMap<>();

  public EnvironmentQueryCoordinator(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      @Qualifier("nodeInstanceId") UUID ownerNodeId,
      LocalDirectoryQueryPort localDirectoryQueryPort) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.ownerNodeId = Objects.requireNonNull(ownerNodeId, "ownerNodeId");
    this.localDirectoryQueryPort =
        Objects.requireNonNull(localDirectoryQueryPort, "localDirectoryQueryPort");
  }

  /**
   * 提交跨节点只读目录查询信箱任务并等待结果。
   *
   * @param environmentId 目标环境 UUID
   * @param path 相对路径
   * @param timeout 超时时长
   * @return 异步结果
   */
  public CompletableFuture<EnvironmentDirectoryListResult> executeRemoteDirectoryQuery(
      EnvironmentId environmentId, String path, Duration timeout) {
    Objects.requireNonNull(environmentId, "environmentId");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(timeout, "timeout");

    UUID queryId = UUID.randomUUID();
    CompletableFuture<EnvironmentDirectoryListResult> future = new CompletableFuture<>();
    PendingQuery pending = new PendingQuery(queryId, environmentId, future);
    pendingQueries.put(queryId, pending);

    try {
      jdbcTemplate.update(
          INSERT_QUERY_SQL, queryId, environmentId.value(), path, timeout.toMillis());
      jdbcTemplate.queryForObject(
          NOTIFY_SQL, String.class, REQUEST_CHANNEL, environmentId.toString());
    } catch (DataAccessException error) {
      pendingQueries.remove(queryId, pending);
      future.complete(
          new EnvironmentDirectoryListResult.Failed(
              EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE,
              "failed to enqueue query in mailbox: " + error.getMessage()));
      return future;
    }

    return future
        .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
        .exceptionally(
            error -> {
              pendingQueries.remove(queryId, pending);
              try {
                jdbcTemplate.update(DELETE_QUERY_BY_ID_SQL, queryId);
              } catch (DataAccessException cleanupError) {
                log.warn("failed to delete timed-out query {}", queryId, cleanupError);
              }
              boolean isTimeout =
                  error instanceof TimeoutException || error.getCause() instanceof TimeoutException;
              EnvironmentDirectoryFailureCode code =
                  isTimeout
                      ? EnvironmentDirectoryFailureCode.TIMEOUT
                      : EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE;
              String message =
                  code == EnvironmentDirectoryFailureCode.TIMEOUT
                      ? "environment "
                          + environmentId
                          + " directory listing timed out after "
                          + timeout.toMillis()
                          + "ms"
                      : "environment "
                          + environmentId
                          + " is not ready; directory listing is unavailable";
              return new EnvironmentDirectoryListResult.Failed(code, message);
            });
  }

  /**
   * 收到 {@code environment_directory_query_request} 通知时的处理逻辑。
   *
   * @param payload 环境 UUID 字符串
   */
  public void onRequestNotification(String payload) {
    if (payload == null || payload.isBlank()) {
      return;
    }
    EnvironmentId environmentId;
    try {
      environmentId = EnvironmentId.parse(payload);
    } catch (IllegalArgumentException ignored) {
      return;
    }
    if (localReadyEnvironments().contains(environmentId)) {
      claimAndExecuteQueriesFor(environmentId);
    }
  }

  /**
   * 收到 {@code environment_directory_query_response} 通知时的处理逻辑。
   *
   * @param payload queryId 字符串
   */
  public void onResponseNotification(String payload) {
    if (payload == null || payload.isBlank()) {
      return;
    }
    UUID queryId;
    try {
      queryId = UUID.fromString(payload);
    } catch (IllegalArgumentException ignored) {
      return;
    }
    PendingQuery pending = pendingQueries.get(queryId);
    if (pending != null) {
      resolvePendingQuery(pending);
    }
  }

  /** 当本地环境变为 READY 时唤醒 mailbox 排空（READY 双唤醒的一部分）。 */
  public void onEnvironmentReady(EnvironmentId environmentId) {
    if (environmentId != null && localReadyEnvironments().contains(environmentId)) {
      claimAndExecuteQueriesFor(environmentId);
    }
  }

  /** 定期或重连后的 resync 回调。 */
  public void onResync() {
    try {
      jdbcTemplate.update(CLEANUP_DEADLINE_QUERIES_SQL);
    } catch (DataAccessException error) {
      log.warn("failed to cleanup expired environment directory queries", error);
    }

    // 检查本地等待中但可能错过 NOTIFY 的 query
    for (PendingQuery pending : List.copyOf(pendingQueries.values())) {
      resolvePendingQuery(pending);
    }

    // 检查本地持有的 READY 环境是否有未认领的 query
    for (EnvironmentId environmentId : localReadyEnvironments()) {
      claimAndExecuteQueriesFor(environmentId);
    }
  }

  private Set<EnvironmentId> localReadyEnvironments() {
    return localDirectoryQueryPort.localReadyEnvironments();
  }

  private void claimAndExecuteQueriesFor(EnvironmentId environmentId) {
    QueryDrain drain = queryDrains.computeIfAbsent(environmentId, ignored -> new QueryDrain());
    synchronized (drain) {
      drain.requested = true;
      if (drain.draining || drain.inFlight) {
        return;
      }
      drain.draining = true;
    }
    drainQueries(environmentId, drain);
  }

  private void drainQueries(EnvironmentId environmentId, QueryDrain drain) {
    while (true) {
      synchronized (drain) {
        if (drain.inFlight || !drain.requested) {
          drain.draining = false;
          return;
        }
        drain.requested = false;
      }

      if (!localReadyEnvironments().contains(environmentId)) {
        if (retryRequestedOrStop(drain)) {
          continue;
        }
        return;
      }

      ClaimedQuery claimed = claimNextQuery(environmentId);
      if (claimed == null) {
        if (retryRequestedOrStop(drain)) {
          continue;
        }
        return;
      }

      synchronized (drain) {
        drain.inFlight = true;
      }
      executeClaimedQuery(claimed, () -> queryFinished(environmentId, drain));

      synchronized (drain) {
        if (drain.inFlight) {
          drain.draining = false;
          return;
        }
      }
    }
  }

  private static boolean retryRequestedOrStop(QueryDrain drain) {
    synchronized (drain) {
      if (drain.requested) {
        return true;
      }
      drain.draining = false;
      return false;
    }
  }

  private void queryFinished(EnvironmentId environmentId, QueryDrain drain) {
    boolean startDraining;
    synchronized (drain) {
      if (!drain.inFlight) {
        return;
      }
      drain.inFlight = false;
      drain.requested = true;
      startDraining = !drain.draining;
      if (startDraining) {
        drain.draining = true;
      }
    }
    if (startDraining) {
      drainQueries(environmentId, drain);
    }
  }

  private ClaimedQuery claimNextQuery(EnvironmentId environmentId) {
    try {
      List<ClaimedQuery> results =
          jdbcTemplate.query(
              CLAIM_QUERY_SQL,
              (rs, rowNum) ->
                  new ClaimedQuery(
                      (UUID) rs.getObject("id"),
                      EnvironmentId.of((UUID) rs.getObject("environment_id")),
                      rs.getString("path"),
                      rs.getObject("deadline_at", OffsetDateTime.class).toInstant()),
              environmentId.value(),
              ownerNodeId);
      return results.isEmpty() ? null : results.get(0);
    } catch (DataAccessException error) {
      log.warn("failed to claim environment directory query for {}", environmentId, error);
      return null;
    }
  }

  private void executeClaimedQuery(ClaimedQuery query, Runnable onFinished) {
    if (!localReadyEnvironments().contains(query.environmentId)) {
      failQuery(
          query.id,
          EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE,
          "environment " + query.environmentId + " is not ready locally");
      onFinished.run();
      return;
    }

    long remainingMillis = Duration.between(Instant.now(), query.deadlineAt).toMillis();
    if (remainingMillis <= 0) {
      failQuery(
          query.id,
          EnvironmentDirectoryFailureCode.TIMEOUT,
          "directory listing deadline expired before local execution");
      onFinished.run();
      return;
    }
    Duration timeout = Duration.ofMillis(remainingMillis);

    CompletableFuture<EnvironmentDirectoryListResult> execution;
    try {
      execution =
          localDirectoryQueryPort.executeLocalDirectoryList(
              query.environmentId, query.path, timeout);
    } catch (RuntimeException error) {
      failQuery(query.id, EnvironmentDirectoryFailureCode.IO_ERROR, error.getMessage());
      onFinished.run();
      return;
    }
    if (execution == null) {
      failQuery(
          query.id,
          EnvironmentDirectoryFailureCode.IO_ERROR,
          "local directory query executor returned null");
      onFinished.run();
      return;
    }
    execution.whenComplete(
        (result, error) -> {
          try {
            if (error != null) {
              failQuery(query.id, EnvironmentDirectoryFailureCode.IO_ERROR, error.getMessage());
            } else if (result instanceof EnvironmentDirectoryListResult.Loaded loaded) {
              completeQuery(query.id, loaded.listing());
            } else if (result instanceof EnvironmentDirectoryListResult.Failed failed) {
              failQuery(query.id, failed.code(), failed.message());
            } else {
              failQuery(
                  query.id,
                  EnvironmentDirectoryFailureCode.IO_ERROR,
                  "local directory query executor returned unexpected result");
            }
          } finally {
            onFinished.run();
          }
        });
  }

  private void completeQuery(UUID queryId, EnvironmentDirectoryDTO listing) {
    try {
      String resultJson = objectMapper.writeValueAsString(listing);
      int updated = jdbcTemplate.update(COMPLETE_QUERY_SQL, resultJson, queryId);
      if (updated > 0) {
        jdbcTemplate.queryForObject(NOTIFY_SQL, String.class, RESPONSE_CHANNEL, queryId.toString());
      }
    } catch (Exception error) {
      log.warn("failed to mark environment directory query completed {}", queryId, error);
    }
  }

  private void failQuery(UUID queryId, EnvironmentDirectoryFailureCode code, String message) {
    try {
      String codeName =
          code == null ? EnvironmentDirectoryFailureCode.IO_ERROR.name() : code.name();
      String errorMsg = message == null ? "directory query failed" : message;
      int updated = jdbcTemplate.update(FAIL_QUERY_SQL, codeName, errorMsg, queryId);
      if (updated > 0) {
        jdbcTemplate.queryForObject(NOTIFY_SQL, String.class, RESPONSE_CHANNEL, queryId.toString());
      }
    } catch (Exception error) {
      log.warn("failed to mark environment directory query failed {}", queryId, error);
    }
  }

  /** Requester 通过 DELETE RETURNING 原子领取并删除终态结果。 */
  private void resolvePendingQuery(PendingQuery pending) {
    try {
      List<DeletedTerminalRow> rows =
          jdbcTemplate.query(
              DELETE_AND_FETCH_TERMINAL_SQL,
              (rs, rowNum) ->
                  new DeletedTerminalRow(
                      EnvironmentQueryStatus.valueOf(rs.getString("status")),
                      rs.getString("result"),
                      rs.getString("failure_code"),
                      rs.getString("failure_message")),
              pending.queryId);

      if (rows.isEmpty()) {
        return;
      }
      DeletedTerminalRow row = rows.get(0);
      if (pendingQueries.remove(pending.queryId, pending)) {
        if (row.status == EnvironmentQueryStatus.SUCCEEDED) {
          try {
            EnvironmentDirectoryDTO dto =
                objectMapper.readValue(row.resultJson, EnvironmentDirectoryDTO.class);
            pending.future.complete(new EnvironmentDirectoryListResult.Loaded(dto));
          } catch (JsonProcessingException error) {
            pending.future.complete(
                new EnvironmentDirectoryListResult.Failed(
                    EnvironmentDirectoryFailureCode.IO_ERROR,
                    "failed to decode result: " + error.getMessage()));
          }
        } else if (row.status == EnvironmentQueryStatus.FAILED) {
          EnvironmentDirectoryFailureCode code = EnvironmentDirectoryFailureCode.IO_ERROR;
          if (row.failureCode != null) {
            try {
              code = EnvironmentDirectoryFailureCode.valueOf(row.failureCode);
            } catch (IllegalArgumentException ignored) {
            }
          }
          String message =
              row.failureMessage != null ? row.failureMessage : "remote directory query failed";
          pending.future.complete(new EnvironmentDirectoryListResult.Failed(code, message));
        }
      }
    } catch (DataAccessException error) {
      log.warn("failed to resolve pending query via DELETE RETURNING {}", pending.queryId, error);
    }
  }

  private record PendingQuery(
      UUID queryId,
      EnvironmentId environmentId,
      CompletableFuture<EnvironmentDirectoryListResult> future) {}

  private static final class QueryDrain {
    private boolean draining;
    private boolean inFlight;
    private boolean requested;
  }

  private record ClaimedQuery(
      UUID id, EnvironmentId environmentId, String path, Instant deadlineAt) {}

  private record DeletedTerminalRow(
      EnvironmentQueryStatus status,
      String resultJson,
      String failureCode,
      String failureMessage) {}
}
