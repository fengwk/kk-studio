package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;

import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** 验证单快照两域装配、内置贡献聚合与发行物中真实 Mavis 的唯一注册路径。 */
class PluginCatalogWiringTest {

  @Test
  void collectsContributorBeansIntoOneFrozenCatalog() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      registerHost(context);
      context.registerBean(
          "firstContributor",
          HarnessContributor.class,
          () ->
              HarnessContributor.of(
                  new ContributorDescriptor(new ContributorId("first"), "first", "1", Set.of()),
                  registrar -> registrar.registerCustomEntryType("custom-one", "custom.one")));
      context.registerBean(
          "secondContributor",
          HarnessContributor.class,
          () ->
              HarnessContributor.of(
                  new ContributorDescriptor(new ContributorId("second"), "second", "1", Set.of()),
                  registrar -> registrar.registerCustomEntryType("custom-two", "custom.two")));
      context.refresh();

      HarnessCatalog catalog = context.getBean(HarnessCatalog.class);
      assertEquals(2, catalog.descriptors().size());
      assertTrue(catalog.findCustomEntryType(new ContributorId("first"), "custom.one").isPresent());
      assertTrue(
          catalog.findCustomEntryType(new ContributorId("second"), "custom.two").isPresent());
    }
  }

  @Test
  void allowsAnEmptyContributorSet() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      registerHost(context);
      context.refresh();

      HarnessCatalog catalog = context.getBean(HarnessCatalog.class);
      assertTrue(catalog.descriptors().isEmpty());
      assertTrue(catalog.tools().isEmpty());
      assertTrue(catalog.selectableTools().isEmpty());
      assertTrue(catalog.customEntryTypes().isEmpty());
      assertTrue(catalog.contextProjectors().isEmpty());
    }
  }

  @Test
  void rejectsDuplicateContributorId() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      registerHost(context);
      context.registerBean(
          "firstContributor",
          HarnessContributor.class,
          () ->
              HarnessContributor.of(
                  new ContributorDescriptor(
                      new ContributorId("duplicate"), "duplicate1", "1", Set.of()),
                  registrar -> {}));
      context.registerBean(
          "secondContributor",
          HarnessContributor.class,
          () ->
              HarnessContributor.of(
                  new ContributorDescriptor(
                      new ContributorId("duplicate"), "duplicate2", "1", Set.of()),
                  registrar -> {}));

      assertThrows(RuntimeException.class, context::refresh);
    }
  }

  /** 可选 JAR 只通过运行时 classpath 接入；真实 configure 产出工具，启动不解析凭据或发送请求。 */
  @Test
  void realMavisContributesOnceThroughTheSharedSnapshot() throws ClassNotFoundException {
    String prefix = "fun.fengwk.kkstudio.plugin.";
    Class<?> autoConfiguration =
        Class.forName(prefix + "minimaxmavis.MiniMaxMavisAutoConfiguration");
    Class<?> transportType = Class.forName(prefix + "minimaxmavis.MavisHttpTransport");
    AtomicInteger requests = new AtomicInteger();
    Object transport =
        Proxy.newProxyInstance(
            transportType.getClassLoader(),
            new Class<?>[] {transportType},
            (proxy, method, args) -> {
              return switch (method.getName()) {
                case "send" -> {
                  requests.incrementAndGet();
                  throw new AssertionError("plugin startup must not issue remote requests");
                }
                case "toString" -> "offline Mavis transport";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
              };
            });
    PluginCredentialStore credentials = mock(PluginCredentialStore.class);
    new ApplicationContextRunner()
        .withUserConfiguration(PluginCatalogConfiguration.class, RegistryConfiguration.class)
        .withBean(PluginCredentialStore.class, () -> credentials)
        .withInitializer(
            context -> context.getBeanFactory().registerSingleton("mavisHttpTransport", transport))
        .withConfiguration(AutoConfigurations.of(autoConfiguration))
        .run(
            context -> {
              assertTrue(context.getStartupFailure() == null);
              PluginCatalogSnapshot snapshot = context.getBean(PluginCatalogSnapshot.class);
              HarnessCatalog harness = context.getBean(HarnessCatalog.class);
              CanvasFunctionCatalog canvas = context.getBean(CanvasFunctionCatalog.class);
              assertSame(snapshot.harnessCatalog(), harness);
              assertSame(snapshot.canvasFunctionCatalog(), canvas);
              assertEquals(1, snapshot.configuredPlugins().size());
              assertEquals(15, harness.tools().size());
              assertTrue(harness.findDescriptor(new ContributorId("minimax-mavis")).isPresent());
              assertTrue(context.getBeansOfType(HarnessContributor.class).isEmpty());
              assertTrue(canvas.list().isEmpty());
              assertEquals(0, requests.get());
              verifyNoInteractions(credentials);
            });
  }

  private static void registerHost(AnnotationConfigApplicationContext context) {
    context.register(PluginCatalogConfiguration.class, RegistryConfiguration.class);
    context.registerBean(PluginCredentialStore.class, () -> mock(PluginCredentialStore.class));
  }

  @Configuration(proxyBeanMethods = false)
  static class RegistryConfiguration {

    @Bean
    StudioPluginRegistry studioPluginRegistry(ObjectProvider<StudioPlugin> plugins) {
      return new StudioPluginRegistry(plugins.orderedStream().toList());
    }
  }
}
