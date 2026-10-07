package fun.fengwk.kkstudio.platform.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.LeaseBindResult;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.repo.impl.PostgresqlEnvironmentChangeNotifier;
import fun.fengwk.kkstudio.platform.environment.repo.impl.PostgresqlEnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.repo.impl.mapper.EnvironmentMapper;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentService;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCardDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCreateDTO;

import java.time.Duration;
import java.util.UUID;

/**
 * environment 注册行的 Java 通知契约：每次真实 insert/update/delete 在同一事务内发布 {@code environment_changed}。
 *
 * <p>测试基座已删除两个数据库行触发器，并用独立 LISTEN 连接直接观测：仅提交后可见；未提交与回滚不可见；删除级联连接仍由同一 environment 通知覆盖，不需要连接级第二来源。
 */
class EnvironmentRepositoryChangeNotificationIntegrationTest
    extends EnvironmentNotificationTestSupport {

  private static final Duration LEASE_DURATION = Duration.ofSeconds(60);

  @Autowired private EnvironmentService environmentService;
  @Autowired private EnvironmentRepository environmentRepository;
  @Autowired private EnvironmentRegistry environmentRegistry;
  @Autowired private EnvironmentMapper environmentMapper;
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

  /** 测试意图：写方法在执行 SQL 前校验外层真实事务；无事务调用必须拒绝，数据与 version 都不变且观察者静默。 */
  @Test
  void repositoryWritesRequireAnOuterTransaction() throws Exception {
    UUID seededId = seedEnvironment("require-tx-seeded", "require-tx-token", 7);
    try (EnvironmentChannelListener listener = listen()) {
      Environment created = new Environment();
      created.setId(UUID.randomUUID());
      created.setName("require-tx-created");
      created.setRegistrationToken("require-tx-created-token");
      assertThrows(IllegalStateException.class, () -> environmentRepository.create(created));
      assertNull(environmentRepository.getById(created.getId()));
      listener.assertSilent();

      Environment update = environmentRepository.getById(seededId);
      update.setRegistrationToken("require-tx-rotated");
      assertThrows(IllegalStateException.class, () -> environmentRepository.updateById(update, 7));
      Environment unchanged = environmentRepository.getById(seededId);
      assertEquals(7, unchanged.getVersion());
      assertEquals("require-tx-token", unchanged.getRegistrationToken());
      listener.assertSilent();

      assertThrows(
          IllegalStateException.class, () -> environmentRepository.deleteById(seededId, 7));
      Environment stillPresent = environmentRepository.getById(seededId);
      assertNotNull(stillPresent);
      assertEquals(7, stillPresent.getVersion());
      listener.assertSilent();
    }
  }

  /** 测试意图：发布失败时，真实 mapper 写入必须随外层事务回滚，不留下已写事实。 */
  @Test
  void notificationFailureRollsBackTheRepositoryWrite() throws Exception {
    UUID seededId = seedEnvironment("notify-failure-seeded", "notify-failure-token", 7);
    try (EnvironmentChannelListener listener = listen()) {
      PostgresqlEnvironmentChangeNotifier failingNotifier =
          mock(PostgresqlEnvironmentChangeNotifier.class);
      doThrow(new DataAccessResourceFailureException("environment notification unavailable"))
          .when(failingNotifier)
          .environmentChanged(any());
      PostgresqlEnvironmentRepository failingRepository =
          new PostgresqlEnvironmentRepository(environmentMapper, failingNotifier);
      TransactionTemplate transaction = new TransactionTemplate(transactionManager);

      Environment created = new Environment();
      created.setId(UUID.randomUUID());
      created.setName("notify-failure-created");
      created.setRegistrationToken("notify-failure-created-token");
      assertThrows(
          DataAccessResourceFailureException.class,
          () -> transaction.executeWithoutResult(status -> failingRepository.create(created)));
      assertNull(environmentRepository.getById(created.getId()));
      listener.assertSilent();

      Environment update = environmentRepository.getById(seededId);
      update.setRegistrationToken("notify-failure-rotated");
      assertThrows(
          DataAccessResourceFailureException.class,
          () ->
              transaction.executeWithoutResult(
                  status -> failingRepository.updateById(update, update.getVersion())));
      Environment unchanged = environmentRepository.getById(seededId);
      assertEquals(7, unchanged.getVersion());
      assertEquals("notify-failure-token", unchanged.getRegistrationToken());
      listener.assertSilent();
    }
  }

  /** 测试意图：CAS 未命中（0 行）保持静默且不改数据，只有真实写入才投递。 */
  @Test
  void casMissStaysSilentAndPreservesData() throws Exception {
    UUID seededId = seedEnvironment("cas-miss-seeded", "cas-miss-token", 7);
    try (EnvironmentChannelListener listener = listen()) {
      TransactionTemplate transaction = new TransactionTemplate(transactionManager);

      Environment update = environmentRepository.getById(seededId);
      update.setRegistrationToken("cas-miss-rotated");
      boolean updated = transaction.execute(status -> environmentRepository.updateById(update, 6));
      assertFalse(updated);
      listener.assertSilent();
      Environment unchanged = environmentRepository.getById(seededId);
      assertEquals(7, unchanged.getVersion());
      assertEquals("cas-miss-token", unchanged.getRegistrationToken());

      boolean deleted =
          transaction.execute(status -> environmentRepository.deleteById(seededId, 6));
      assertFalse(deleted);
      listener.assertSilent();
      assertNotNull(environmentRepository.getById(seededId));
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

      // 租约到期没有数据库写事件：手工改期后观察者保持静默。
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

  /** 用固定 version 播种一行 environment，使拒绝与 CAS 未命中路径可以观测 version 是否被改写。 */
  private UUID seedEnvironment(String name, String token, long version) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "insert into environment (id, name, registration_token, version) values (?, ?, ?, ?)",
        id,
        name,
        token,
        version);
    return id;
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
