package fun.fengwk.kkstudio.platform.environment.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * {@link PostgresqlEnvironmentUpdateRepository} 的真实 PostgreSQL 集成测试。
 *
 * <p>验证「一个 Environment 同一时刻至多一次活动更新」完全由 {@code uk_environment_update_active} 部分唯一索引强制：并发准入只有一次成功；
 * 终态历史行不阻塞下一次准入；{@code advance} 是带来源阶段集合的条件更新，迟到/重放的回执不能把阶段回退。测试只操作本测试自建的 Testcontainer 数据库。
 */
class PostgresqlEnvironmentUpdateRepositoryIntegrationTest extends PostgresSpringTestSupport {

  private static final String TARGET = "1.0.10";

  @Autowired private PostgresqlEnvironmentUpdateRepository repository;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void concurrentAdmissionAllowsExactlyOneActiveOperationPerEnvironment() throws Exception {
    EnvironmentId environment = insertEnvironment();

    int attempts = 8;
    List<EnvironmentUpdateOperation> operations = new ArrayList<>();
    for (int index = 0; index < attempts; index++) {
      operations.add(pending(environment, UUID.randomUUID()));
    }
    CountDownLatch ready = new CountDownLatch(attempts);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(attempts);
    try {
      List<Future<Boolean>> results = new ArrayList<>();
      for (EnvironmentUpdateOperation operation : operations) {
        results.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return repository.insertPending(operation);
                }));
      }
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      start.countDown();

      int admitted = 0;
      for (Future<Boolean> result : results) {
        if (result.get(10, TimeUnit.SECONDS)) {
          admitted++;
        }
      }
      // 部分唯一索引把并发准入原子收敛为恰好一次成功，其余收敛为 DuplicateKeyException -> false。
      assertEquals(1, admitted);
    } finally {
      executor.shutdownNow();
    }
    assertEquals(
        1L,
        queryLong(
            "select count(*) from environment_update_operation where environment_id = '"
                + environment.value()
                + "'"));
    assertTrue(repository.findActive(environment).isPresent());
  }

  @Test
  void terminalFailureDoesNotBlockTheNextUpdate() {
    EnvironmentId environment = insertEnvironment();
    EnvironmentUpdateOperation first = pending(environment, UUID.randomUUID());
    assertTrue(repository.insertPending(first));
    assertTrue(
        repository.advance(
            first.operationId(),
            EnvironmentUpdatePhase.FAILED,
            "checksum mismatch",
            Set.of(EnvironmentUpdatePhase.PENDING, EnvironmentUpdatePhase.RUNNING)));

    // 终态行保留为历史，因此同一 Environment 的下一次更新可以立即准入。
    EnvironmentUpdateOperation second = pending(environment, UUID.randomUUID());
    assertTrue(repository.insertPending(second));
    assertEquals(
        second.operationId(), repository.findActive(environment).orElseThrow().operationId());
    assertEquals(
        second.operationId(), repository.findLatest(environment).orElseThrow().operationId());
    assertEquals(
        2L,
        queryLong(
            "select count(*) from environment_update_operation where environment_id = '"
                + environment.value()
                + "'"));
  }

  @Test
  void lateReceiptCannotRegressThePhase() {
    EnvironmentId environment = insertEnvironment();
    EnvironmentUpdateOperation operation = pending(environment, UUID.randomUUID());
    assertTrue(repository.insertPending(operation));
    assertTrue(
        repository.advance(
            operation.operationId(),
            EnvironmentUpdatePhase.SUCCEEDED,
            null,
            Set.of(EnvironmentUpdatePhase.PENDING, EnvironmentUpdatePhase.RUNNING)));

    // 迟到回执：来源集合不再包含当前 SUCCEEDED，因此条件更新命中 0 行，阶段保持不变。
    assertFalse(
        repository.advance(
            operation.operationId(),
            EnvironmentUpdatePhase.RUNNING,
            null,
            Set.of(EnvironmentUpdatePhase.PENDING, EnvironmentUpdatePhase.RUNNING)));
    assertFalse(
        repository.advance(
            operation.operationId(),
            EnvironmentUpdatePhase.FAILED,
            "late",
            Set.of(EnvironmentUpdatePhase.PENDING)));

    // 使用生产查询路径确认阶段，UUID 参数必须按 PostgreSQL uuid 类型绑定。
    assertEquals(
        EnvironmentUpdatePhase.SUCCEEDED,
        repository.find(operation.operationId()).orElseThrow().phase());
    assertTrue(repository.find(UUID.randomUUID().toString()).isEmpty());
    assertTrue(repository.findActive(environment).isEmpty());
  }

  private EnvironmentId insertEnvironment() {
    UUID environmentId = UUID.randomUUID();
    jdbc.update(
        "insert into environment (id, name, registration_token) values (?, ?, ?)",
        environmentId,
        "update-repo-" + environmentId,
        "update-repo-token");
    return EnvironmentId.of(environmentId);
  }

  private static EnvironmentUpdateOperation pending(EnvironmentId environment, UUID operationId) {
    Instant now = Instant.now();
    return new EnvironmentUpdateOperation(
        operationId.toString(),
        environment,
        TARGET,
        EnvironmentUpdatePhase.PENDING,
        null,
        now,
        now);
  }

  private long queryLong(String sql) {
    Long value = jdbc.queryForObject(sql, Long.class);
    return value == null ? 0L : value;
  }
}
