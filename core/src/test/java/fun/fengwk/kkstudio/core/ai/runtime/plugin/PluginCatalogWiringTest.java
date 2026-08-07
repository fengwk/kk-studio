package fun.fengwk.kkstudio.core.ai.runtime.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import fun.fengwk.kkstudio.harness.plugin.HarnessPlugin;
import fun.fengwk.kkstudio.harness.plugin.PluginCatalog;
import fun.fengwk.kkstudio.harness.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.harness.plugin.PluginId;
import fun.fengwk.kkstudio.harness.plugin.ToolVisibility;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactory;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** 验证 Spring startup wiring：收集 HarnessPlugin bean 并冻结 PluginCatalog，空注册表也允许。 */
class PluginCatalogWiringTest {

  @Test
  void collectsPluginBeansIntoOneFrozenCatalog() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(PluginCatalogConfiguration.class);
      context.registerBean(
          "firstPlugin",
          HarnessPlugin.class,
          () ->
              HarnessPlugin.of(
                  new PluginDescriptor(new PluginId("first"), "first", "1"),
                  registrar ->
                      registrar.registerTool(
                          "goal-tool",
                          ToolFactory.singleton(tool(descriptor("goal", "1"))),
                          ToolVisibility.SELECTABLE)));
      context.registerBean(
          "secondPlugin",
          HarnessPlugin.class,
          () ->
              HarnessPlugin.of(
                  new PluginDescriptor(new PluginId("second"), "second", "1"),
                  registrar -> registrar.registerCustomEntryType("goal-type", "goal")));
      context.refresh();

      PluginCatalog catalog = context.getBean(PluginCatalog.class);
      assertEquals(2, catalog.descriptors().size());
      assertEquals(1, catalog.tools().size());
      assertEquals("first", catalog.tools().get(0).id().pluginId().value());
      assertEquals(1, catalog.customEntryTypes().size());
      assertEquals("second", catalog.customEntryTypes().get(0).id().pluginId().value());
    }
  }

  @Test
  void permitsEmptyPluginRegistry() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(PluginCatalogConfiguration.class);
      context.refresh();

      PluginCatalog catalog = context.getBean(PluginCatalog.class);
      assertTrue(catalog.descriptors().isEmpty());
      assertTrue(catalog.tools().isEmpty());
      assertTrue(catalog.customEntryTypes().isEmpty());
      assertTrue(catalog.contextProjectors().isEmpty());
    }
  }

  private static ToolDescriptor descriptor(String name, String version) {
    return new ToolDescriptor(
        name,
        version,
        ToolType.PLATFORM,
        name + " tool",
        name,
        new ToolParamsSchema("", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(5));
  }

  private static Tool tool(ToolDescriptor descriptor) {
    return new Tool() {
      @Override
      public ToolDescriptor descriptor() {
        return descriptor;
      }

      @Override
      public ToolExecutionHandle execute(
          ToolExecutionRequest request, ToolExecutionListener listener) {
        throw new UnsupportedOperationException();
      }
    };
  }
}
