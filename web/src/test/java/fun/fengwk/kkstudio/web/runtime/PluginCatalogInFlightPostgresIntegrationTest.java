package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.harness.common.network.HttpProxySelector;
import fun.fengwk.kkstudio.harness.contributor.api.BranchView;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTransport;
import fun.fengwk.kkstudio.harness.runtime.admission.ConcurrencyAdmission;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorStateAccess;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorStateAccessMode;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.permission.BashSurfaceAnalyzer;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionEvaluator;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionRule;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.platform.catalog.mcp.repo.McpServerRepository;
import fun.fengwk.kkstudio.platform.catalog.mcp.runtime.McpToolCatalog;
import fun.fengwk.kkstudio.platform.catalog.tool.PluginRuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.harness.tool.gateway.ToolExecutionGateway;
import fun.fengwk.kkstudio.platform.harness.tool.gateway.ToolResourceStager;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigurationState;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginAuthContextFingerprint;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogFingerprint;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogManifest;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRecord;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRuntime;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialReference;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginCatalogChangeNotifier;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.PostgresqlPluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.store.PluginConfigurationJdbcStore;
import fun.fengwk.kkstudio.platform.plugin.resource.PluginResourceGateway;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import javax.sql.DataSource;

import java.net.ProxySelector;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 真实 runtime-only Mavis JAR、隔离 PG、生产 Source 与 Gateway 的跨层生命周期回归。
 *
 * <p>只在传输、凭据和无关外部端口使用替身；planning 租用在执行前释放，旧实例只能由 Gateway 自身钉住。
 */
class PluginCatalogInFlightPostgresIntegrationTest {

  private static final String MAVIS = "minimax-mavis";
  private static final String DISABLED = "{\"enabled\":false}";

  @Test
  void cancellationPinsRealMavisUntilQuiescenceAndRestartRestoresFrozenBinding() throws Exception {
    DataSource dataSource = WebPostgresTestSupport.createIsolatedDatabase();
    PluginConfigurationRepository repository = repository(dataSource);
    ControlledTransport transport = new ControlledTransport();
    TrackingInstances firstInstances = new TrackingInstances();
    AtomicReference<ToolBinding> frozen = new AtomicReference<>();
    AtomicReference<PluginCredentialReference> expectedCredential = new AtomicReference<>();
    PluginCredentialStore credentials = mock(PluginCredentialStore.class);
    when(credentials.resolve(any()))
        .thenAnswer(
            invocation -> {
              PluginCredentialReference reference = invocation.getArgument(0);
              assertEquals(expectedCredential.get(), reference, "must use the frozen credential");
              return new PluginCredentialSnapshot(
                  reference.pluginId(),
                  reference.authGeneration(),
                  reference.contextFingerprint(),
                  "CN",
                  Instant.now().plusSeconds(3600),
                  0L,
                  new byte[0],
                  "{\"accessToken\":\"synthetic-inflight-token\","
                      + "\"clientUuid\":\"11111111-2222-3333-4444-555555555555\","
                      + "\"obtainedAt\":0}");
            });
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      runner(dataSource, repository, credentials, transport, firstInstances)
          .run(
              context -> {
                assertNull(context.getStartupFailure());
                PluginCatalogRuntime runtime = context.getBean(PluginCatalogRuntime.class);
                PluginRuntimeToolCatalogSource source = source(runtime, executor);
                TrackedInstance old = firstInstances.onlyRevision(1);
                ToolContribution planned;
                try (RuntimeToolCatalogSource.Lease planning = source.acquireCurrent()) {
                  assertEquals(1, planning.epoch());
                  assertEquals(15, planning.harnessCatalog().tools().size());
                  planned = planning.catalog().findTool("mavis_web_search").orElseThrow();
                  ToolBinding binding = binding(planning, planned);
                  assertNotNull(binding.pluginSource());
                  frozen.set(binding);
                  expectedCredential.set(
                      new PluginCredentialReference(
                          binding.pluginSource().pluginId(),
                          binding.pluginSource().authGeneration(),
                          binding.pluginSource().contextFingerprint()));
                  assertSame(planned, planning.require(binding));
                }
                verifyNoInteractions(credentials);
                assertEquals(0, transport.requests.get());

                ConcurrencyAdmission admission = new ConcurrencyAdmission(1);
                ToolGateway.Listener listener = mock(ToolGateway.Listener.class);
                ToolExecutionGateway gateway = gateway(source, executor, admission);
                ToolInvocationRequest request =
                    new ToolInvocationRequest(
                        new ToolCall("inflight", "mavis_web_search", "{\"query\":\"lifecycle\"}"),
                        frozen.get());
                assertInstanceOf(ToolGateway.Allow.class, gateway.preflight(request));
                ToolGateway.Handle handle =
                    assertInstanceOf(
                            ToolGateway.Started.class,
                            gateway.start(
                                new ToolGateway.Execution(
                                    UUID.randomUUID(),
                                    UUID.randomUUID(),
                                    UUID.randomUUID(),
                                    1,
                                    request),
                                listener))
                        .handle();
                try {
                  assertEquals(0, transport.requests.get(), "start must wait for activate");
                  handle.activate();
                  await(transport.bodyEntered, "real Mavis worker must enter web_search");
                  assertTrue(admission.tryAcquire().isEmpty(), "execution occupies the quota");
                  assertEquals(2, transport.requests.get(), "one catalog and one search request");

                  commitDisabled(context, repository, dataSource);
                  runtime.refresh();
                  assertEquals(new PluginCatalogRuntime.RuntimeState(2, 2, null), runtime.state());
                  assertCurrentDisabled(source);
                  assertEquals(0, old.closes.get(), "Gateway must pin the retired real instance");
                  assertEquals(1, old.closed.getCount());
                  assertEquals(1, transport.bodyExited.getCount(), "old execution is still alive");

                  handle.cancel();
                  await(transport.interrupted, "cancel must reach the Mavis worker");
                  try (ConcurrencyAdmission.Lease quota = admission.tryAcquire().orElseThrow()) {
                    assertNotNull(quota, "cancel releases quota independently of quiescence");
                  }
                  assertEquals(1, transport.bodyExited.getCount());
                  assertEquals(
                      0, old.closes.get(), "Future cancellation is not resource quiescence");
                  assertEquals(1, old.closed.getCount());
                  verifyNoInteractions(listener);

                  transport.release.countDown();
                  await(transport.bodyExited, "transport body must exit");
                  await(old.closed, "retired instance closes after real worker quiescence");
                  assertEquals(1, old.closes.get());
                  handle.cancel();
                  assertEquals(1, old.closes.get(), "repeated cancellation must not close twice");
                  assertEquals(2, transport.requests.get(), "no retry or replacement call");
                  assertCurrentDisabled(source);
                  verifyNoInteractions(listener);
                } finally {
                  transport.release.countDown();
                  handle.cancel();
                }
              });
    } finally {
      transport.release.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "gateway executor must stop");
      // Runner 已关闭 runtime；即使中途断言失败，也必须等插件实际工作线程退出。
      Thread worker = transport.worker.get();
      if (worker != null) {
        assertTrue(
            worker.join(Duration.ofSeconds(5)),
            "real Mavis worker must terminate after instance shutdown");
      }
    }
    assertEquals(1, firstInstances.onlyRevision(1).closes.get());
    firstInstances.instances.forEach(instance -> assertEquals(1, instance.closes.get()));
    assertPersistedEpochTwo(dataSource, repository);
    verify(credentials, times(2)).resolve(expectedCredential.get());

    // 全新组合根先恢复当前 disabled epoch，再从 PG 历史恢复完整旧 binding，绝不倒退 applied。
    TrackingInstances restartedInstances = new TrackingInstances();
    ExecutorService restartedExecutor = Executors.newSingleThreadExecutor();
    try {
      runner(dataSource, repository, credentials, transport, restartedInstances)
          .run(
              context -> {
                PluginCatalogRuntime runtime = context.getBean(PluginCatalogRuntime.class);
                PluginRuntimeToolCatalogSource source = source(runtime, restartedExecutor);
                assertCurrentDisabled(source);
                TrackedInstance current = restartedInstances.onlyRevision(2);
                TrackedInstance history;
                try (RuntimeToolCatalogSource.Lease lease =
                    source.acquire(frozen.get().catalogEpoch())) {
                  assertEquals(15, lease.harnessCatalog().tools().size());
                  ToolContribution restored = lease.require(frozen.get());
                  assertEquals(frozen.get(), binding(lease, restored), "all frozen facts match");
                  history = restartedInstances.onlyRevision(1);
                  assertEquals(0, history.closes.get());
                  assertEquals(0, current.closes.get());
                }
                await(history.closed, "only the restored historical instance must close");
                assertEquals(1, history.closes.get());
                assertEquals(0, current.closes.get());
                assertEquals(new PluginCatalogRuntime.RuntimeState(2, 2, null), runtime.state());
                assertCurrentDisabled(source);
                assertPersistedEpochTwo(dataSource, repository);
                assertEquals(2, transport.requests.get(), "restore must not send requests");
              });
    } finally {
      restartedExecutor.shutdownNow();
      assertTrue(restartedExecutor.awaitTermination(5, TimeUnit.SECONDS));
    }
    assertEquals(1, restartedInstances.onlyRevision(1).closes.get());
    assertEquals(1, restartedInstances.onlyRevision(2).closes.get());
    verify(credentials, times(2)).resolve(expectedCredential.get());
  }

  private static ToolBinding binding(
      RuntimeToolCatalogSource.Lease lease, ToolContribution contribution) {
    return new ToolBinding(
        lease.epoch(),
        contribution.definition(),
        new ContributorBinding(
            contribution.id().contributorId().value(),
            contribution.id().localName(),
            contribution.requirements().stateAccesses().stream()
                .map(
                    access ->
                        new ContributorStateAccess(
                            access.customType(),
                            ContributorStateAccessMode.valueOf(access.mode().name())))
                .toList()),
        contribution.requirements().environmentSupport(),
        null,
        null,
        lease.pluginSource(contribution.id()));
  }

  private static void assertCurrentDisabled(PluginRuntimeToolCatalogSource source) {
    try (RuntimeToolCatalogSource.Lease current = source.acquireCurrent()) {
      assertEquals(2, current.epoch());
      assertTrue(current.catalog().findTool("mavis_web_search").isEmpty());
      assertTrue(current.harnessCatalog().tools().isEmpty());
    }
  }

  private static void assertPersistedEpochTwo(
      DataSource dataSource, PluginConfigurationRepository repository) {
    assertEquals(2, repository.readCatalog().epoch());
    JdbcClient jdbc = JdbcClient.create(dataSource);
    assertEquals(
        2,
        jdbc.sql("select count(*) from plugin_configuration_revision")
            .query(Integer.class)
            .single());
    assertEquals(
        3,
        jdbc.sql("select count(*) from plugin_catalog_revision").query(Integer.class).single(),
        "catalog history contains only baseline epoch 0 and committed epochs 1, 2");
    assertEquals(DISABLED, repository.readConfiguration(MAVIS, 2).orElseThrow().valuesJson());
  }

  private static PluginRuntimeToolCatalogSource source(
      PluginCatalogRuntime runtime, ExecutorService executor) {
    McpServerRepository mcp = mock(McpServerRepository.class);
    when(mcp.listAllServers()).thenReturn(List.of());
    return new PluginRuntimeToolCatalogSource(runtime, new McpToolCatalog(mcp, executor));
  }

  private static ToolExecutionGateway gateway(
      RuntimeToolCatalogSource source, ExecutorService executor, ConcurrencyAdmission admission) {
    return new ToolExecutionGateway(
        source,
        (entryId, contributorId) -> mock(BranchView.class),
        mock(EnvironmentCapabilityTransport.class),
        new PermissionEvaluator(new BashSurfaceAnalyzer()),
        () ->
            new ToolSettings(Map.of("*", List.of(new PermissionRule("*", PermissionAction.ALLOW)))),
        mock(ResourceStore.class),
        mock(ToolResourceStager.class),
        16 * 1024 * 1024,
        executor,
        () -> Duration.ofSeconds(1),
        Clock.systemUTC(),
        admission);
  }

  private static ApplicationContextRunner runner(
      DataSource dataSource,
      PluginConfigurationRepository repository,
      PluginCredentialStore credentials,
      ControlledTransport transport,
      TrackingInstances instances) {
    return RuntimeMavisTestSupport.withMavis(
        new ApplicationContextRunner()
            .withUserConfiguration(PluginCatalogConfiguration.class, RegistryConfiguration.class)
            .withBean(TrackingInstances.class, () -> instances)
            // PluginCatalogRuntime 显式依赖 systemProxySelector：隔离组合根提供直连的合法 selector 依赖，
            // 不安装 JVM 全局代理，保持与其他用例的全局代理隔离。
            .withBean(
                "systemProxySelector", ProxySelector.class, () -> HttpProxySelector.fixed(null, ""))
            .withBean(PluginCredentialStore.class, () -> credentials)
            .withBean(PluginConfigurationRepository.class, () -> repository)
            .withBean(
                PlatformTransactionManager.class,
                () -> new DataSourceTransactionManager(dataSource)),
        transport::send);
  }

  private static PluginConfigurationRepository repository(DataSource dataSource) {
    return new PostgresqlPluginConfigurationRepository(
        new PluginConfigurationJdbcStore(JdbcClient.create(dataSource)),
        new PostgresqlPluginCatalogChangeNotifier(new JdbcTemplate(dataSource)));
  }

  /** 与 runtime PG 测试相同的真实完整候选、manifest/history CAS 提交，候选实例独立关闭。 */
  private static void commitDisabled(
      ConfigurableApplicationContext context,
      PluginConfigurationRepository repository,
      DataSource dataSource) {
    StudioPluginRegistry registry = context.getBean(StudioPluginRegistry.class);
    StudioPlugin plugin = registry.find(MAVIS).orElseThrow();
    PluginCatalogRecord authority = repository.readCatalog();
    assertEquals(1, authority.epoch());
    PluginCatalogManifest.Entry baseline = authority.manifest().plugins().get(MAVIS);
    String schema =
        PluginCatalogFingerprint.schema(plugin.configurationSchema(), plugin.authenticationSpec());
    String authContext =
        PluginAuthContextFingerprint.derive(
            MAVIS,
            plugin.descriptor().version(),
            schema,
            plugin.authenticationSpec(),
            JsonValues.readTree(plugin.configurationSchema().resolveValues(DISABLED)));
    try (PluginCatalogSnapshot candidate =
        PluginCatalogSnapshot.build(
            2,
            registry,
            Map.of(
                MAVIS,
                new PluginConfigurationState(
                    new PluginConfig(2, DISABLED),
                    new PluginCredentialReference(MAVIS, baseline.authGeneration(), authContext))),
            PluginCatalogConfiguration.pluginContexts(
                context.getBean(PluginCredentialStore.class),
                context.getBeanProvider(PluginResourceGateway.class)),
            List.copyOf(context.getBeansOfType(HarnessContributor.class).values()),
            List.copyOf(context.getBeansOfType(CanvasFunctionAdapter.class).values()))) {
      assertTrue(candidate.harnessCatalog().tools().isEmpty());
      String capability = candidate.capabilityFingerprints().get(MAVIS);
      PluginCatalogManifest manifest =
          new PluginCatalogManifest(
              Map.of(
                  MAVIS,
                  new PluginCatalogManifest.Entry(
                      2,
                      plugin.descriptor().version(),
                      schema,
                      capability,
                      baseline.authGeneration(),
                      authContext)),
              candidate.catalogFingerprint());
      PluginConfigurationRow changed =
          new PluginConfigurationRow(
              MAVIS,
              2,
              plugin.descriptor().version(),
              schema,
              capability,
              baseline.authGeneration(),
              authContext,
              List.of(),
              DISABLED);
      assertTrue(
          Boolean.TRUE.equals(
              new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                  .execute(status -> repository.commit(1, List.of(changed), manifest))));
    }
  }

  private static void await(CountDownLatch latch, String message) throws InterruptedException {
    assertTrue(latch.await(5, TimeUnit.SECONDS), message);
  }

  /** 只经宿主 SPI 包装生命周期；贡献及其真正 Mavis 工具原样委托。 */
  private static final class TrackedInstance implements PluginInstance {
    private final long revision;
    private final PluginInstance delegate;
    private final AtomicInteger closes = new AtomicInteger();
    private final CountDownLatch closed = new CountDownLatch(1);

    private TrackedInstance(long revision, PluginInstance delegate) {
      this.revision = revision;
      this.delegate = delegate;
    }

    @Override
    public List<HarnessContributor> harnessContributors() {
      return delegate.harnessContributors();
    }

    @Override
    public List<CanvasFunctionAdapter> canvasAdapters() {
      return delegate.canvasAdapters();
    }

    @Override
    public void close() {
      closes.incrementAndGet();
      delegate.close();
      closed.countDown();
    }
  }

  static final class TrackingInstances {
    private final List<TrackedInstance> instances = new CopyOnWriteArrayList<>();

    StudioPlugin track(StudioPlugin actual) {
      StudioPlugin tracked = spy(actual);
      doAnswer(
              invocation -> {
                PluginConfig config = invocation.getArgument(0);
                TrackedInstance instance =
                    new TrackedInstance(
                        config.revision(), actual.configure(config, invocation.getArgument(1)));
                instances.add(instance);
                return instance;
              })
          .when(tracked)
          .configure(any(), any());
      return tracked;
    }

    TrackedInstance onlyRevision(long revision) {
      List<TrackedInstance> matches =
          instances.stream().filter(instance -> instance.revision == revision).toList();
      assertEquals(1, matches.size(), "exactly one configured instance for revision " + revision);
      return matches.getFirst();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class RegistryConfiguration {
    @Bean
    StudioPluginRegistry studioPluginRegistry(
        ObjectProvider<StudioPlugin> plugins, TrackingInstances instances) {
      return new StudioPluginRegistry(plugins.orderedStream().map(instances::track).toList());
    }
  }

  /** 反射仅用于可选 JAR 的传输值；收到中断后仍等待 release，绝不把取消当作执行体退出。 */
  private static final class ControlledTransport {
    private final AtomicInteger requests = new AtomicInteger();
    private final CountDownLatch bodyEntered = new CountDownLatch(1);
    private final CountDownLatch interrupted = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch bodyExited = new CountDownLatch(1);
    private final AtomicReference<Thread> worker = new AtomicReference<>();

    Object send(Object request) throws Exception {
      requests.incrementAndGet();
      String url = (String) request.getClass().getMethod("url").invoke(request);
      if (url.endsWith("/mavis/api/v1/mcp/tools")) {
        return response("{\"tools\":[{\"endpoint\":\"web_search\"}]}");
      }
      assertTrue(url.endsWith("/web_search"), "unexpected transport endpoint");
      worker.set(Thread.currentThread());
      bodyEntered.countDown();
      boolean wasInterrupted = false;
      try {
        boolean released = false;
        while (!released) {
          try {
            released = release.await(10, TimeUnit.SECONDS);
            assertTrue(released, "test must release the non-interruptible body");
          } catch (InterruptedException cancelled) {
            wasInterrupted = true;
            interrupted.countDown();
          }
        }
        return response("{\"base_resp\":{\"status_code\":0},\"result\":\"ok\"}");
      } finally {
        if (wasInterrupted) {
          Thread.currentThread().interrupt();
        }
        bodyExited.countDown();
      }
    }

    private static Object response(String body) throws ReflectiveOperationException {
      String prefix = "fun.fengwk.kkstudio.plugin.";
      return Class.forName(prefix + "minimaxmavis.MavisHttpResponse")
          .getConstructor(int.class, String.class)
          .newInstance(200, body);
    }
  }
}
