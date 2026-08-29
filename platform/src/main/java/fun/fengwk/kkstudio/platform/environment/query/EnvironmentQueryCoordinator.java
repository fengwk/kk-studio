package fun.fengwk.kkstudio.platform.environment.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentDirectoryFailureCode;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentDirectoryListResult;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentDirectoryDTO;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 跨节点只读 Environment 查询协调器（信箱 + PostgreSQL NOTIFY + 本地执行）。
 *
 * <p>仅支持只读白名单能力 {@code fs.list-directory}。非持有节点将查询插入 {@code environment_query} 表并触发 {@code
 * environment_query_request} 通知；持有节点监听到通知后以 {@code for update skip locked} 原子认领（更新为 RUNNING 并设置
 * lease_token），调用本地 daemon 执行并将结果/错误以围栏方式回填到数据库并触发 {@code environment_query_response} 通知；调用方解析并完成
 * future。
 */
@Slf4j
@Service
public class EnvironmentQueryCoordinator {

  public static final String REQUEST_CHANNEL = "environment_query_request";
  public static final String RESPONSE_CHANNEL = "environment_query_response";
  public static final String CAPABILITY_FS_LIST_DIRECTORY = "fs.list-directory";

  private static final Duration CLAIM_LEASE_DURATION = Duration.ofSeconds(15);

  private static final String INSERT_QUERY_SQL =
      """
      insert into environment_query (
          id, environment_name, capability_id, arguments,
          status, available_at, expires_at, created_at, updated_at
      ) values (
          ?, ?, ?, ?::jsonb,
          'PENDING', statement_timestamp(), statement_timestamp() + (? * interval '1 millisecond'),
          statement_timestamp(), statement_timestamp()
      )
      """;

  private static final String NOTIFY_SQL = "select pg_notify(?, ?)";

  private static final String CLAIM_QUERY_SQL =
      """
      with candidate as (
          select eq.id
          from environment_query eq
          join live_environment le on le.environment_name = eq.environment_name
          where eq.environment_name = ?
            and le.owner_node_id = ?
            and le.status = 'READY'
            and le.lease_until > statement_timestamp()
            and (
                (eq.status = 'PENDING' and eq.available_at <= statement_timestamp())
                or (eq.status = 'RUNNING' and eq.lease_until < statement_timestamp())
            )
            and eq.expires_at > statement_timestamp()
          order by eq.created_at asc
          for update of eq skip locked
          limit 1
      )
      update environment_query
      set status = 'RUNNING',
          available_at = null,
          lease_token = ?,
          lease_until = statement_timestamp() + (? * interval '1 millisecond'),
          updated_at = statement_timestamp()
      from candidate
      where environment_query.id = candidate.id
      returning environment_query.id, environment_query.environment_name,
                environment_query.capability_id, environment_query.arguments::text
      """;

  private static final String COMPLETE_QUERY_SQL =
      """
      update environment_query
      set status = 'COMPLETED',
          result = ?::jsonb,
          error = null,
          available_at = null,
          lease_token = null,
          lease_until = null,
          updated_at = statement_timestamp()
      where id = ?
        and lease_token = ?
        and status = 'RUNNING'
      """;

  private static final String FAIL_QUERY_SQL =
      """
      update environment_query
      set status = 'FAILED',
          result = null,
          error = ?::jsonb,
          available_at = null,
          lease_token = null,
          lease_until = null,
          updated_at = statement_timestamp()
      where id = ?
        and lease_token = ?
        and status = 'RUNNING'
      """;

  private static final String FIND_QUERY_SQL =
      """
      select id, status, result::text, error::text
      from environment_query
      where id = ?
      """;

  private static final String EXPIRE_QUERIES_SQL =
      """
      update environment_query
      set status = 'EXPIRED',
          available_at = null,
          lease_token = null,
          lease_until = null,
          updated_at = statement_timestamp()
      where status in ('PENDING', 'RUNNING')
        and expires_at <= statement_timestamp()
      """;

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final UUID ownerNodeId;
  private final Map<UUID, PendingQuery> pendingQueries = new ConcurrentHashMap<>();
  private volatile LocalDirectoryQueryExecutor localExecutor;
  private volatile Supplier<Set<EnvironmentName>> localReadyEnvironmentsSupplier = Set::of;

  public EnvironmentQueryCoordinator(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      @Qualifier("nodeInstanceId") UUID ownerNodeId) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.ownerNodeId = Objects.requireNonNull(ownerNodeId, "ownerNodeId");
  }

  public void registerLocalExecutor(
      LocalDirectoryQueryExecutor executor,
      Supplier<Set<EnvironmentName>> readyEnvironmentsSupplier) {
    this.localExecutor = Objects.requireNonNull(executor, "executor");
    this.localReadyEnvironmentsSupplier =
        Objects.requireNonNull(readyEnvironmentsSupplier, "readyEnvironmentsSupplier");
  }

  /**
   * 提交跨节点只读目录查询信箱任务并等待结果。
   *
   * @param environmentName 目标环境名
   * @param path 相对路径
   * @param timeout 超时时长
   * @return 异步结果
   */
  public CompletableFuture<EnvironmentDirectoryListResult> executeRemoteDirectoryQuery(
      EnvironmentName environmentName, String path, Duration timeout) {
    Objects.requireNonNull(environmentName, "environmentName");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(timeout, "timeout");

    UUID queryId = UUID.randomUUID();
    CompletableFuture<EnvironmentDirectoryListResult> future = new CompletableFuture<>();
    PendingQuery pending = new PendingQuery(queryId, environmentName, future);
    pendingQueries.put(queryId, pending);

    ObjectNode arguments = objectMapper.createObjectNode();
    arguments.put("path", path);
    String argumentsJson = arguments.toString();

    try {
      jdbcTemplate.update(
          INSERT_QUERY_SQL,
          queryId,
          environmentName.value(),
          CAPABILITY_FS_LIST_DIRECTORY,
          argumentsJson,
          timeout.toMillis());
      jdbcTemplate.queryForObject(
          NOTIFY_SQL, String.class, REQUEST_CHANNEL, environmentName.value());
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
              boolean isTimeout =
                  error instanceof TimeoutException || error.getCause() instanceof TimeoutException;
              EnvironmentDirectoryFailureCode code =
                  isTimeout
                      ? EnvironmentDirectoryFailureCode.TIMEOUT
                      : EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE;
              String message =
                  code == EnvironmentDirectoryFailureCode.TIMEOUT
                      ? environmentName
                          + " directory listing timed out after "
                          + timeout.toMillis()
                          + "ms"
                      : environmentName + " is not ready; directory listing is unavailable";
              return new EnvironmentDirectoryListResult.Failed(code, message);
            });
  }

  /**
   * 收到 {@code environment_query_request} 通知时的处理逻辑。
   *
   * @param payload 环境名字符串
   */
  public void onRequestNotification(String payload) {
    if (payload == null || payload.isBlank()) {
      return;
    }
    EnvironmentName environmentName;
    try {
      environmentName = new EnvironmentName(payload);
    } catch (IllegalArgumentException ignored) {
      return;
    }
    if (localReadyEnvironmentsSupplier.get().contains(environmentName)) {
      claimAndExecuteQueriesFor(environmentName);
    }
  }

  /**
   * 收到 {@code environment_query_response} 通知时的处理逻辑。
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

  /** 定期或重连后的 resync 回调。 */
  public void onResync() {
    try {
      jdbcTemplate.update(EXPIRE_QUERIES_SQL);
    } catch (DataAccessException error) {
      log.warn("failed to expire outdated environment queries", error);
    }

    // 检查本地等待中但可能错过 NOTIFY 的 query
    for (PendingQuery pending : List.copyOf(pendingQueries.values())) {
      resolvePendingQuery(pending);
    }

    // 检查本地持有的 READY 环境是否有未认领的 query
    for (EnvironmentName environmentName : localReadyEnvironmentsSupplier.get()) {
      claimAndExecuteQueriesFor(environmentName);
    }
  }

  private void claimAndExecuteQueriesFor(EnvironmentName environmentName) {
    if (localExecutor == null) {
      return;
    }
    while (true) {
      if (!localReadyEnvironmentsSupplier.get().contains(environmentName)) {
        break;
      }
      ClaimedQuery claimed = claimNextQuery(environmentName);
      if (claimed == null) {
        break;
      }
      executeClaimedQuery(claimed);
    }
  }

  private ClaimedQuery claimNextQuery(EnvironmentName environmentName) {
    UUID leaseToken = UUID.randomUUID();
    try {
      List<ClaimedQuery> results =
          jdbcTemplate.query(
              CLAIM_QUERY_SQL,
              (rs, rowNum) ->
                  new ClaimedQuery(
                      (UUID) rs.getObject("id"),
                      new EnvironmentName(rs.getString("environment_name")),
                      rs.getString("capability_id"),
                      rs.getString("arguments"),
                      leaseToken),
              environmentName.value(),
              ownerNodeId,
              leaseToken,
              CLAIM_LEASE_DURATION.toMillis());
      return results.isEmpty() ? null : results.get(0);
    } catch (DataAccessException error) {
      log.warn("failed to claim environment query for {}", environmentName, error);
      return null;
    }
  }

  private void executeClaimedQuery(ClaimedQuery query) {
    String path = ".";
    try {
      JsonNode argsNode = objectMapper.readTree(query.argumentsJson);
      if (argsNode.has("path") && argsNode.get("path").isTextual()) {
        path = argsNode.get("path").asText(".");
      }
    } catch (JsonProcessingException error) {
      failQuery(
          query.id,
          query.leaseToken,
          EnvironmentDirectoryFailureCode.INVALID_PATH,
          "cannot parse arguments: " + error.getMessage());
      return;
    }

    LocalDirectoryQueryExecutor executor = this.localExecutor;
    if (executor == null || !localReadyEnvironmentsSupplier.get().contains(query.environmentName)) {
      return;
    }

    executor
        .executeLocalDirectoryList(query.environmentName, path, CLAIM_LEASE_DURATION)
        .whenComplete(
            (result, error) -> {
              if (error != null) {
                failQuery(
                    query.id,
                    query.leaseToken,
                    EnvironmentDirectoryFailureCode.IO_ERROR,
                    error.getMessage());
              } else if (result instanceof EnvironmentDirectoryListResult.Loaded loaded) {
                completeQuery(query.id, query.leaseToken, loaded.listing());
              } else if (result instanceof EnvironmentDirectoryListResult.Failed failed) {
                failQuery(query.id, query.leaseToken, failed.code(), failed.message());
              }
            });
  }

  private void completeQuery(UUID queryId, UUID leaseToken, EnvironmentDirectoryDTO listing) {
    try {
      String resultJson = objectMapper.writeValueAsString(listing);
      int updated = jdbcTemplate.update(COMPLETE_QUERY_SQL, resultJson, queryId, leaseToken);
      if (updated > 0) {
        jdbcTemplate.queryForObject(NOTIFY_SQL, String.class, RESPONSE_CHANNEL, queryId.toString());
      }
    } catch (Exception error) {
      log.warn("failed to mark environment query completed {}", queryId, error);
    }
  }

  private void failQuery(
      UUID queryId, UUID leaseToken, EnvironmentDirectoryFailureCode code, String message) {
    try {
      ObjectNode errorNode = objectMapper.createObjectNode();
      errorNode.put("code", code.name());
      errorNode.put("message", message);
      int updated = jdbcTemplate.update(FAIL_QUERY_SQL, errorNode.toString(), queryId, leaseToken);
      if (updated > 0) {
        jdbcTemplate.queryForObject(NOTIFY_SQL, String.class, RESPONSE_CHANNEL, queryId.toString());
      }
    } catch (Exception error) {
      log.warn("failed to mark environment query failed {}", queryId, error);
    }
  }

  private void resolvePendingQuery(PendingQuery pending) {
    try {
      List<QueryRow> rows =
          jdbcTemplate.query(
              FIND_QUERY_SQL,
              (rs, rowNum) ->
                  new QueryRow(
                      (UUID) rs.getObject("id"),
                      EnvironmentQueryStatus.valueOf(rs.getString("status")),
                      rs.getString("result"),
                      rs.getString("error")),
              pending.queryId);

      if (rows.isEmpty()) {
        return;
      }
      QueryRow row = rows.get(0);
      switch (row.status) {
        case COMPLETED -> {
          if (pendingQueries.remove(pending.queryId, pending)) {
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
          }
        }
        case FAILED -> {
          if (pendingQueries.remove(pending.queryId, pending)) {
            EnvironmentDirectoryFailureCode code = EnvironmentDirectoryFailureCode.IO_ERROR;
            String message = "remote query failed";
            try {
              JsonNode errNode = objectMapper.readTree(row.errorJson);
              if (errNode.has("code")) {
                code = EnvironmentDirectoryFailureCode.valueOf(errNode.get("code").asText());
              }
              if (errNode.has("message")) {
                message = errNode.get("message").asText();
              }
            } catch (Exception ignored) {
            }
            pending.future.complete(new EnvironmentDirectoryListResult.Failed(code, message));
          }
        }
        case EXPIRED -> {
          if (pendingQueries.remove(pending.queryId, pending)) {
            pending.future.complete(
                new EnvironmentDirectoryListResult.Failed(
                    EnvironmentDirectoryFailureCode.ENVIRONMENT_UNAVAILABLE,
                    pending.environmentName + " query expired"));
          }
        }
        case PENDING, RUNNING -> {
          // 仍在执行中，保持等待
        }
      }
    } catch (DataAccessException error) {
      log.warn("failed to resolve pending query {}", pending.queryId, error);
    }
  }

  private record PendingQuery(
      UUID queryId,
      EnvironmentName environmentName,
      CompletableFuture<EnvironmentDirectoryListResult> future) {}

  private record ClaimedQuery(
      UUID id,
      EnvironmentName environmentName,
      String capabilityId,
      String argumentsJson,
      UUID leaseToken) {}

  private record QueryRow(
      UUID id, EnvironmentQueryStatus status, String resultJson, String errorJson) {}
}
