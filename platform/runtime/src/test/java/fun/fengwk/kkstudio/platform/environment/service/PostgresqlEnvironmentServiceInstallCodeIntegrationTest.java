package fun.fengwk.kkstudio.platform.environment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.environment.RegistrationTokens;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.share.ai.environment.DaemonConfiguration;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCardDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInstallCodeDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInstallConfigDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInstallConfigUpdateDTO;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link EnvironmentService} 临时安装 code 业务在真实 PostgreSQL 17 容器上的集成测试。
 *
 * <p>验证覆盖：摘要伪造拒绝、错环境拒绝、重复兑换拒绝、新替旧 upsert 覆盖、凭据轮换 (rotate) 及配置导入 (import) 失效；
 * 跨真实服务实例共享、并发只一次原子兑换，以及脚本渲染或凭据写失败时的真实数据库事务回滚。
 */
class PostgresqlEnvironmentServiceInstallCodeIntegrationTest extends PostgresSpringTestSupport {

  @Autowired private EnvironmentService environmentService;
  @Autowired private EnvironmentRepository environmentRepository;
  @Autowired private EnvironmentRegistry environmentRegistry;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private JdbcClient jdbcClient;
  @Autowired private SystemSettingsSnapshot snapshot;
  @Autowired private PlatformTransactionManager transactionManager;

  /** 测试意图：摘要伪造的 code 必须被拒绝且不破坏已有记录，未被篡改的真实 code 仍可成功兑换。 */
  @Test
  void tamperedCodeRejectedAndGenuineCodePreserved() {
    EnvironmentId envId = createTestEnvironment("tampered-code-env");
    EnvironmentInstallCodeDTO issued = environmentService.issueInstallCode(envId, "1");
    assertThat(countInstallCodes(envId)).isEqualTo(1L);

    String originalCode = issued.getCode();
    // 导出/数据库可见的凭证摘要不是安装授权。
    String exposedHash = environmentRepository.getById(envId.value()).getRegistrationTokenHash();
    assertThrows(
        AiValidationException.class,
        () -> environmentService.installationScript(envId, exposedHash));
    assertThrows(
        AiValidationException.class,
        () ->
            environmentService.installationScript(
                envId, EnvironmentInstallCodes.hash(exposedHash)));
    String tamperedCode = (originalCode.charAt(0) == 'A' ? "B" : "A") + originalCode.substring(1);

    assertThrows(
        AiValidationException.class,
        () -> environmentService.installationScript(envId, tamperedCode));

    // 伪造失败后真实记录完整无损，正品 code 成功核销
    assertThat(countInstallCodes(envId)).isEqualTo(1L);
    String script = environmentService.installationScript(envId, originalCode);
    assertThat(script).isNotBlank().contains("install.sh");
    assertThat(countInstallCodes(envId)).isZero();
  }

  /** 测试意图：环境 A 签发的安装 code 兑换给环境 B 时必须拒绝，且环境 A 的 code 保持有效。 */
  @Test
  void wrongEnvironmentRejectedAndPreservesCode() {
    EnvironmentId envA = createTestEnvironment("wrong-env-a");
    EnvironmentId envB = createTestEnvironment("wrong-env-b");

    EnvironmentInstallCodeDTO issuedA = environmentService.issueInstallCode(envA, "1");
    assertThat(countInstallCodes(envA)).isEqualTo(1L);
    assertThat(countInstallCodes(envB)).isZero();

    assertThrows(
        AiValidationException.class,
        () -> environmentService.installationScript(envB, issuedA.getCode()));

    assertThat(countInstallCodes(envA)).isEqualTo(1L);
    assertThat(countInstallCodes(envB)).isZero();

    // 环境 A 正确兑换
    String script = environmentService.installationScript(envA, issuedA.getCode());
    assertThat(script).isNotBlank();
    assertThat(countInstallCodes(envA)).isZero();
  }

  /** 测试意图：安装 code 具有一次性语义，首次兑换成功后二次兑换必须被拒绝。 */
  @Test
  void duplicateConsumptionRejected() {
    EnvironmentId envId = createTestEnvironment("duplicate-consume-env");
    EnvironmentInstallCodeDTO issued = environmentService.issueInstallCode(envId, "1");

    String firstScript = environmentService.installationScript(envId, issued.getCode());
    assertThat(firstScript).isNotBlank();
    assertThat(countInstallCodes(envId)).isZero();

    assertThrows(
        AiValidationException.class,
        () -> environmentService.installationScript(envId, issued.getCode()));
  }

  /** 测试意图：对同一环境再次签发安装 code（新替旧）原子覆盖旧 code，旧 code 无法再消费。 */
  @Test
  void newCodeReplacesOldCodeViaUpsert() {
    EnvironmentId envId = createTestEnvironment("upsert-replace-env");
    EnvironmentInstallCodeDTO first = environmentService.issueInstallCode(envId, "1");
    EnvironmentInstallCodeDTO second = environmentService.issueInstallCode(envId, "1");

    // 仅保留最新的一条临时安装 code
    assertThat(countInstallCodes(envId)).isEqualTo(1L);

    // 旧 code 消费失败
    assertThrows(
        AiValidationException.class,
        () -> environmentService.installationScript(envId, first.getCode()));
    assertThat(countInstallCodes(envId)).isEqualTo(1L);

    // 新 code 消费成功
    String script = environmentService.installationScript(envId, second.getCode());
    assertThat(script).isNotBlank();
    assertThat(countInstallCodes(envId)).isZero();
  }

  /** 测试意图：签发 code 后发生凭证轮换（rotateToken），旧 code 携带的历史 tokenHash 失效拒绝消费。 */
  @Test
  void rotateTokenInvalidatesIssuedCode() {
    EnvironmentId envId = createTestEnvironment("rotate-invalidates-env");
    EnvironmentInstallCodeDTO issued = environmentService.issueInstallCode(envId, "1");
    assertThat(countInstallCodes(envId)).isEqualTo(1L);

    // 轮换凭证，使 Environment 持有的 registrationTokenHash 发生改变
    environmentService.rotateToken(envId, "1");

    assertThrows(
        AiValidationException.class,
        () -> environmentService.installationScript(envId, issued.getCode()));
  }

  /** 测试意图：签发 code 后配置导入（updateImportedEnvironment）更新了凭据摘要，旧 code 失效拒绝消费。 */
  @Test
  void importInvalidatesIssuedCode() {
    EnvironmentId envId = createTestEnvironment("import-invalidates-env");
    EnvironmentInstallCodeDTO issued = environmentService.issueInstallCode(envId, "1");
    assertThat(countInstallCodes(envId)).isEqualTo(1L);

    String newImportedTokenHash = RegistrationTokens.hash("imported-fresh-token");
    environmentService.updateImportedEnvironment(envId, newImportedTokenHash, validConfig(), "1");

    assertThrows(
        AiValidationException.class,
        () -> environmentService.installationScript(envId, issued.getCode()));
  }

  /** 测试意图：真实 PostgreSQL 下多个独立 Service 实例共享状态，实例 1 签发 code，实例 2 可成功核销。 */
  @Test
  void crossServiceInstancesShareStateViaRealPostgres() {
    EnvironmentId envId = createTestEnvironment("cross-instance-env");
    EnvironmentService serviceInstance1 = environmentService;
    EnvironmentService serviceInstance2 =
        transactional(
            new EnvironmentServiceImpl(
                environmentRepository,
                environmentRegistry,
                jdbcTemplate,
                snapshot,
                Clock.systemUTC()));

    EnvironmentInstallCodeDTO issued = serviceInstance1.issueInstallCode(envId, "1");
    assertThat(countInstallCodes(envId)).isEqualTo(1L);

    // 由第二个服务实例核销 code 并生成脚本
    String script = serviceInstance2.installationScript(envId, issued.getCode());
    assertThat(script).isNotBlank();
    assertThat(countInstallCodes(envId)).isZero();

    // 第一个实例观察到版本已递增
    EnvironmentCardDTO card = serviceInstance1.get(envId);
    assertThat(card.getVersion()).isEqualTo("2");

    // 再次在第一个实例尝试兑换失败
    assertThrows(
        AiValidationException.class,
        () -> serviceInstance1.installationScript(envId, issued.getCode()));
  }

  /** 测试意图：多线程并发核销同一安装 code，数据库行锁与原子消费保证严格只有一次成功，其余全被拒绝。 */
  @Test
  void concurrentConsumptionExactlyOneSucceeds() throws Exception {
    EnvironmentId envId = createTestEnvironment("concurrent-consume-env");
    EnvironmentInstallCodeDTO issued = environmentService.issueInstallCode(envId, "1");

    int threadCount = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threadCount);
    CountDownLatch startGate = new CountDownLatch(1);
    CountDownLatch doneGate = new CountDownLatch(threadCount);
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger validationExceptions = new AtomicInteger();
    AtomicInteger otherErrors = new AtomicInteger();

    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      futures.add(
          pool.submit(
              () -> {
                try {
                  startGate.await();
                  environmentService.installationScript(envId, issued.getCode());
                  successes.incrementAndGet();
                } catch (AiValidationException error) {
                  validationExceptions.incrementAndGet();
                } catch (Throwable error) {
                  otherErrors.incrementAndGet();
                } finally {
                  doneGate.countDown();
                }
              }));
    }

    startGate.countDown();
    boolean completed = doneGate.await(10, TimeUnit.SECONDS);
    pool.shutdown();
    assertThat(completed).isTrue();

    for (Future<?> future : futures) {
      future.get();
    }

    assertThat(successes.get()).isEqualTo(1);
    assertThat(validationExceptions.get()).isEqualTo(threadCount - 1);
    assertThat(otherErrors.get()).isZero();
    assertThat(countInstallCodes(envId)).isZero();

    EnvironmentCardDTO card = environmentService.get(envId);
    assertThat(card.getVersion()).isEqualTo("2");
  }

  /** 测试意图：安装脚本渲染失败抛异常时，外层事务回滚，code 消费行与环境行均恢复，之后仍可正常消费。 */
  @Test
  void renderFailureRollsBackCodeConsumption() {
    EnvironmentId envId = createTestEnvironment("render-failure-env");
    EnvironmentInstallCodeDTO issued = environmentService.issueInstallCode(envId, "1");
    assertThat(countInstallCodes(envId)).isEqualTo(1L);

    try (MockedStatic<EnvironmentInstallScripts> mocked =
        Mockito.mockStatic(EnvironmentInstallScripts.class)) {
      mocked
          .when(() -> EnvironmentInstallScripts.install(any(), any()))
          .thenThrow(new IllegalStateException("simulated render failure"));

      assertThrows(
          IllegalStateException.class,
          () -> environmentService.installationScript(envId, issued.getCode()));
    }

    // 事务回滚后：临时 code 未被删除，版本保持不变
    assertThat(countInstallCodes(envId)).isEqualTo(1L);
    EnvironmentCardDTO card = environmentService.get(envId);
    assertThat(card.getVersion()).isEqualTo("1");

    // 原 code 仍可成功兑换，且成功后版本推进为 2
    String script = environmentService.installationScript(envId, issued.getCode());
    assertThat(script).isNotBlank();
    assertThat(countInstallCodes(envId)).isZero();
    assertThat(environmentService.get(envId).getVersion()).isEqualTo("2");
  }

  /** 测试意图：凭据更新落库失败时，事务回滚撤销 code 消费，code 记录保留且之后可成功重试。 */
  @Test
  void tokenWriteFailureRollsBackCodeConsumption() {
    EnvironmentId envId = createTestEnvironment("token-write-failure-env");
    EnvironmentInstallCodeDTO issued = environmentService.issueInstallCode(envId, "1");
    assertThat(countInstallCodes(envId)).isEqualTo(1L);

    // 构造在 updateById 阶段抛出异常的 Repository 代理
    EnvironmentRepository failingRepo =
        (EnvironmentRepository)
            Proxy.newProxyInstance(
                EnvironmentRepository.class.getClassLoader(),
                new Class<?>[] {EnvironmentRepository.class},
                (proxy, method, args) -> {
                  if ("updateById".equals(method.getName())) {
                    throw new DataAccessResourceFailureException(
                        "simulated database update failure");
                  }
                  return method.invoke(environmentRepository, args);
                });

    EnvironmentService failingService =
        transactional(
            new EnvironmentServiceImpl(
                failingRepo, environmentRegistry, jdbcTemplate, snapshot, Clock.systemUTC()));

    assertThrows(
        DataAccessResourceFailureException.class,
        () -> failingService.installationScript(envId, issued.getCode()));

    // 事务回滚后：code 仍然有效保留
    assertThat(countInstallCodes(envId)).isEqualTo(1L);
    EnvironmentCardDTO card = environmentService.get(envId);
    assertThat(card.getVersion()).isEqualTo("1");

    // 使用正常 service 重试兑换成功
    String script = environmentService.installationScript(envId, issued.getCode());
    assertThat(script).isNotBlank();
    assertThat(countInstallCodes(envId)).isZero();
    assertThat(environmentService.get(envId).getVersion()).isEqualTo("2");
  }

  private EnvironmentId createTestEnvironment(String name) {
    EnvironmentCreateDTO create = new EnvironmentCreateDTO();
    create.setName(name);
    EnvironmentCardDTO card = environmentService.create(create);
    EnvironmentInstallConfigUpdateDTO update = new EnvironmentInstallConfigUpdateDTO();
    update.setExpectedVersion(card.getVersion());
    update.setInstallConfig(validConfig());
    EnvironmentCardDTO updated =
        environmentService.updateInstallConfig(EnvironmentId.parse(card.getId()), update);
    return EnvironmentId.parse(updated.getId());
  }

  private EnvironmentInstallConfigDTO validConfig() {
    DaemonConfiguration daemon = new DaemonConfiguration();
    daemon.setStudioUrl("https://studio.example.com");
    EnvironmentInstallConfigDTO config = new EnvironmentInstallConfigDTO();
    config.setOperatingSystem("linux");
    config.setDaemon(daemon);
    return config;
  }

  private long countInstallCodes(EnvironmentId id) {
    return jdbcClient
        .sql("select count(*) from environment_install_code where environment_id = :id")
        .param("id", id.value())
        .query(Long.class)
        .single();
  }

  private EnvironmentService transactional(EnvironmentServiceImpl bean) {
    ProxyFactory proxy = new ProxyFactory(bean);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
    return (EnvironmentService) proxy.getProxy();
  }
}
