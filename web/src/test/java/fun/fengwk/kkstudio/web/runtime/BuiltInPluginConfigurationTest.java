package fun.fengwk.kkstudio.web.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import fun.fengwk.kkstudio.core.ai.runtime.plugin.PluginCatalogConfiguration;
import fun.fengwk.kkstudio.harness.plugin.api.PluginCatalog;
import fun.fengwk.kkstudio.harness.plugin.api.PluginId;

import java.util.List;

/** Web 组合根必须把内置 Goal 插件加入启动期冻结 catalog。 */
class BuiltInPluginConfigurationTest {

  @Test
  void registersGoalPluginBeforeCatalogFreeze() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.register(BuiltInPluginConfiguration.class, PluginCatalogConfiguration.class);
      context.refresh();

      PluginCatalog catalog = context.getBean(PluginCatalog.class);
      assertEquals(
          List.of("goal"),
          catalog.descriptors().stream().map(descriptor -> descriptor.id().value()).toList());
      assertTrue(catalog.findTool("create_goal").isPresent());
      assertTrue(catalog.findCustomEntryType(new PluginId("goal"), "state").isPresent());
    }
  }
}
