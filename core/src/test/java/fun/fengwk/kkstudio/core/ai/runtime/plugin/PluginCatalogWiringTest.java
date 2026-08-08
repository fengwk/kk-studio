package fun.fengwk.kkstudio.core.ai.runtime.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import fun.fengwk.kkstudio.harness.plugin.HarnessPlugin;
import fun.fengwk.kkstudio.harness.plugin.PluginCatalog;
import fun.fengwk.kkstudio.harness.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.harness.plugin.PluginId;
import fun.fengwk.kkstudio.harness.plugin.PluginTool;
import fun.fengwk.kkstudio.harness.plugin.PluginToolContext;
import fun.fengwk.kkstudio.harness.plugin.PluginToolResult;
import fun.fengwk.kkstudio.harness.plugin.ToolVisibility;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.time.Duration;
import java.util.List;
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
                          tool(descriptor("extra_tool", "1")),
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
      assertEquals(3, catalog.descriptors().size());
      assertEquals("first", catalog.findTool("extra_tool").orElseThrow().id().pluginId().value());
      assertEquals("goal", catalog.findTool("create_goal").orElseThrow().id().pluginId().value());
      assertTrue(catalog.findCustomEntryType(new PluginId("second"), "goal").isPresent());
      assertTrue(catalog.findCustomEntryType(new PluginId("goal"), "state").isPresent());
    }
  }

  @Test
  void registersTheBuiltInGoalPluginWithoutExtensions() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(PluginCatalogConfiguration.class);
      context.refresh();

      PluginCatalog catalog = context.getBean(PluginCatalog.class);
      assertEquals(
          List.of("goal"), catalog.descriptors().stream().map(d -> d.id().value()).toList());
      assertEquals(3, catalog.tools().size());
      assertEquals(1, catalog.customEntryTypes().size());
      assertEquals(1, catalog.contextProjectors().size());
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

  private static PluginTool tool(ToolDescriptor descriptor) {
    return new PluginTool() {
      @Override
      public ToolDescriptor descriptor() {
        return descriptor;
      }

      @Override
      public PluginToolResult execute(PluginToolContext context, ToolCall call) {
        throw new UnsupportedOperationException();
      }
    };
  }
}
