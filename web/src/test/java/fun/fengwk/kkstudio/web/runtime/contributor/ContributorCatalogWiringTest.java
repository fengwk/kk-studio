package fun.fengwk.kkstudio.web.runtime.contributor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import fun.fengwk.kkstudio.harness.contributor.api.ContributorDescriptor;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;

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
}
