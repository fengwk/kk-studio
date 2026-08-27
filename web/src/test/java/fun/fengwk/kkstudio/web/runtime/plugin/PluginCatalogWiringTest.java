package fun.fengwk.kkstudio.web.runtime.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import fun.fengwk.kkstudio.harness.plugin.api.HarnessPlugin;
import fun.fengwk.kkstudio.harness.plugin.api.PluginCatalog;
import fun.fengwk.kkstudio.harness.plugin.api.PluginDescriptor;
import fun.fengwk.kkstudio.harness.plugin.api.PluginId;
import fun.fengwk.kkstudio.harness.plugin.api.PluginTool;
import fun.fengwk.kkstudio.harness.plugin.api.PluginToolContext;
import fun.fengwk.kkstudio.harness.plugin.api.PluginToolResult;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.platform.harness.plugin.HarnessPluginSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 验证 Spring startup wiring：收集 HarnessPlugin bean 并冻结 PluginCatalog，空注册表也允许。 */
class PluginCatalogWiringTest {

  private static final AgentToolId TOOL_ID = new AgentToolId("test.extra-tool");

  @Test
  void collectsPluginBeansIntoOneFrozenCatalog() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(PluginCatalogConfiguration.class);
      context.registerBean(
          "firstPlugin",
          HarnessPlugin.class,
          () ->
              HarnessPlugin.of(
                  new PluginDescriptor(new PluginId("first"), "first", "1", Set.of()),
                  registrar ->
                      registrar.registerTool(
                          "goal-tool",
                          TOOL_ID,
                          tool(descriptor("extra_tool", "1")),
                          ToolVisibility.SELECTABLE)));
      context.registerBean(
          "secondPlugin",
          HarnessPlugin.class,
          () ->
              HarnessPlugin.of(
                  new PluginDescriptor(new PluginId("second"), "second", "1", Set.of()),
                  registrar -> registrar.registerCustomEntryType("goal-type", "goal")));
      context.refresh();

      PluginCatalog catalog = context.getBean(PluginCatalog.class);
      assertEquals(2, catalog.descriptors().size());
      assertEquals("first", catalog.findTool("extra_tool").orElseThrow().id().pluginId().value());
      assertTrue(catalog.findCustomEntryType(new PluginId("second"), "goal").isPresent());
    }
  }

  @Test
  void allowsAnEmptyPluginSet() {
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

  @Test
  void mergesHarnessPluginSourceSnapshots() {
    HarnessPlugin sourcedPlugin =
        HarnessPlugin.of(
            new PluginDescriptor(new PluginId("sourced"), "sourced", "1", Set.of()),
            registrar -> registrar.registerCustomEntryType("state", "sourced.state"));
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(PluginCatalogConfiguration.class);
      context.registerBean(
          "pluginSource", HarnessPluginSource.class, () -> () -> List.of(sourcedPlugin));
      context.refresh();

      PluginCatalog catalog = context.getBean(PluginCatalog.class);
      assertTrue(catalog.findCustomEntryType(new PluginId("sourced"), "sourced.state").isPresent());
    }
  }

  private static ToolDescriptor descriptor(String name, String version) {
    return new ToolDescriptor(
        name,
        version,
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
