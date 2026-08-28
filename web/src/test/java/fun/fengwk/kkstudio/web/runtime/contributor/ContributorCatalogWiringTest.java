package fun.fengwk.kkstudio.web.runtime.contributor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** 验证 Spring startup wiring：收集 HarnessContributor bean 并冻结 HarnessCatalog，空注册表也允许。 */
class ContributorCatalogWiringTest {

  @Test
  void collectsContributorBeansIntoOneFrozenCatalog() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(ContributorCatalogConfiguration.class);
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
      context.register(ContributorCatalogConfiguration.class);
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
  void combinesSpringContributorsAndTrustedJarLoaderSnapshot(@TempDir Path tempDirectory)
      throws Exception {
    Path contributorDirectory = Files.createDirectory(tempDirectory.resolve("contributors"));
    TrustedJarContributorLoaderTest.writeContributorJar(
        contributorDirectory.resolve("loader.jar"),
        "dynamic.LoaderContributor",
        "loader",
        "loader-marker");
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "wiring-test",
                  Map.of(
                      TrustedJarContributorLoader.DIRECTORY_PROPERTY,
                      contributorDirectory.toString())));
      context.register(ContributorCatalogConfiguration.class);
      context.registerBean(
          "springContributor",
          HarnessContributor.class,
          () ->
              HarnessContributor.of(
                  new ContributorDescriptor(new ContributorId("spring"), "spring", "1", Set.of()),
                  registrar -> registrar.registerCustomEntryType("spring-entry", "spring.type")));
      context.refresh();

      HarnessCatalog catalog = context.getBean(HarnessCatalog.class);
      assertEquals(2, catalog.descriptors().size());
      assertTrue(
          catalog.findCustomEntryType(new ContributorId("spring"), "spring.type").isPresent());
      assertTrue(
          catalog.findCustomEntryType(new ContributorId("loader"), "dynamic.type").isPresent());
    }
  }

  @Test
  void rejectsDuplicateContributorId() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(ContributorCatalogConfiguration.class);
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

  /** 显式负向用例：验证旧属性 kk-studio.harness.plugins.directory 不被读取，loader 不会因此配置非空目录。 */
  @Test
  void ignoresOldPluginDirectoryProperty() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "old-plugin-property-test",
                  Map.of("kk-studio.harness.plugins.directory", "/nonexistent-plugin-directory")));
      context.register(ContributorCatalogConfiguration.class);
      context.refresh();

      TrustedJarContributorLoader loader = context.getBean(TrustedJarContributorLoader.class);
      assertTrue(loader.contributors().isEmpty());
      assertTrue(loader.classLoader().isEmpty());
    }
  }
}
