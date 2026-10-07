package fun.fengwk.kkstudio.platform.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.LeaseBindResult;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.repo.impl.PostgresqlEnvironmentChangeNotifier;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentService;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCardDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCreateDTO;

import java.time.Duration;
import java.util.UUID;

/**
 * environment 注册行的 Java 通知契约：每次真实 insert/update/delete 在同一事务内发布 {@code environment_changed}。
 *
 * <p>测试在隔离测试库中删除遗留触发器后，用独立 LISTEN 连接直接观测：仅提交后可见；未提交与回滚不可见；删除级联连接仍由同一 environment 通知覆盖，不需要连接级第二来源。
 */
class EnvironmentRepositoryChangeNotificationIntegrationTest
    extends EnvironmentNotificationTestSupport {

  private static final Duration LEASE_DURATION = Duration.ofSeconds(60);

  @Autowired private EnvironmentService environmentService;
  @Autowired private EnvironmentRepository environmentRepository;
  @Autowired private EnvironmentRegistry environmentRegistry;
  @Autowired private PostgresqlEnvironmentChangeNotifier notifier;

  /** 测试意图：Card 行写入只在事务提交后投递一次；未提交与回滚都不投递，CAS 更新与删除同样投递。 */
  @Test
  void environmentRowWritesNotifyOnlyAfterCommit() throws Exception {
    try (EnvironmentChannelListener listener = listen()) {
      TransactionTemplate transaction = new TransactionTemplate(transactionManager);

      EnvironmentCardDTO[] created = new EnvironmentCardDTO[1];
      transaction.executeWithoutResult(
          status -> {
            created[0] = environmentService.create(createDto("notify-commit"));
            // 同一事务内尚未提交：独立观察者必须看不到任何提示。
            listener.assertSilent();
          });
      listener.assertNotification(created[0].getId());

      // 回滚不得留下通知，也不得留下行。
      transaction.executeWithoutResult(
          status -> {
            environmentService.create(createDto("notify-rollback"));
            status.setRollbackOnly();
          });
      listener.assertSilent();
      assertEquals(0, countEnvironments("notify-rollback"));

      // rotateToken 是真实 CAS 更新。
      EnvironmentCardDTO rotated =
          environmentService.rotateToken(
              EnvironmentId.parse(created[0].getId()), created[0].getVersion());
      listener.assertNotification(rotated.getId());

      // 删除同样投递。
      environmentService.delete(EnvironmentId.parse(rotated.getId()), rotated.getVersion());
      listener.assertNotification(rotated.getId());
      assertNull(environmentRepository.getById(UUID.fromString(rotated.getId())));
    }
  }

  /** 测试意图：环境删除级联删除连接行时，同一 environment 通知恰好一次；自然到期改期不是通知事件。 */
  @Test
  void cascadedConnectionDeleteIsCoveredByTheSameEnvironmentNotification() throws Exception {
    try (EnvironmentChannelListener listener = listen()) {
      EnvironmentCardDTO created = environmentService.create(createDto("notify-cascade"));
      listener.assertNotification(created.getId());

      EnvironmentId environmentId = EnvironmentId.parse(created.getId());
      LeaseBindResult acquired =
          environmentRegistry.tryAcquire(
              environmentId, created.getRegistrationToken(), LEASE_DURATION);
      assertInstanceOf(LeaseBindResult.Acquired.class, acquired);
      listener.assertNotification(created.getId());

      // 租约自然到期没有数据库写事件：手工改期后观察者保持静默（触发器已删除）。
      expireConnectionLease(UUID.fromString(created.getId()));
      listener.assertSilent();

      environmentService.delete(environmentId, created.getVersion());
      listener.assertNotification(created.getId());
      assertTrue(environmentRegistry.find(environmentId).isEmpty());
      assertEquals(0, countEnvironments("notify-cascade"));
    }
  }

  /** 测试意图：脱离写事务使用 notifier 必须拒绝，避免 autocommit 提前发出信号。 */
  @Test
  void environmentNotifierRequiresTransaction() {
    assertThrows(IllegalStateException.class, () -> notifier.environmentChanged(UUID.randomUUID()));
  }

  private static EnvironmentCreateDTO createDto(String name) {
    EnvironmentCreateDTO dto = new EnvironmentCreateDTO();
    dto.setName(name);
    return dto;
  }

  private void expireConnectionLease(UUID environmentId) {
    jdbcTemplate.update(
        "update environment_connection set last_seen_at = statement_timestamp() - interval '100"
            + " seconds', lease_until = statement_timestamp() - interval '1 second' where"
            + " environment_id = ?",
        environmentId);
  }

  private int countEnvironments(String name) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(1) from environment where name = ?", Integer.class, name);
    return count == null ? 0 : count;
  }
}
