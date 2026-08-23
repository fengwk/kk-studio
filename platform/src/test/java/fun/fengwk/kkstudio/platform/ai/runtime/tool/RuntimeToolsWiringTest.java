package fun.fengwk.kkstudio.platform.ai.runtime.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.harness.plugin.api.PluginCatalog;
import fun.fengwk.kkstudio.harness.runtime.skill.LoadSkillTool;
import fun.fengwk.kkstudio.harness.runtime.subagent.TaskTool;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactories;
import fun.fengwk.kkstudio.harness.tool.ToolCatalog;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 验证 runtime 工具是 Spring bean，并可通过共享 catalog 解析。 */
class RuntimeToolsWiringTest extends PostgresSpringTestSupport {

  @Autowired private List<Tool> tools;
  @Autowired private ToolFactories toolFactories;
  @Autowired private PluginCatalog pluginCatalog;
  @Autowired private ToolCatalog toolCatalog;
  @Autowired private LoadSkillTool loadSkillTool;
  @Autowired private TaskTool taskTool;

  @Test
  void registersPlatformTools() {
    Set<String> beanNames =
        tools.stream().map(tool -> tool.descriptor().name()).collect(Collectors.toSet());
    assertEquals(Set.of("load_skill", "task"), beanNames);
    assertEquals("1", loadSkillTool.descriptor().version());
    assertEquals("1", taskTool.descriptor().version());
    assertTrue(toolFactories.find("load_skill", "1").isPresent());
    assertTrue(toolFactories.find("task", "1").isPresent());
    assertTrue(toolFactories.find("create_goal", "2").isEmpty());
    assertTrue(pluginCatalog.tools().isEmpty());
    assertTrue(toolCatalog.findSelectable("create_goal").isEmpty());
  }
}
