package fun.fengwk.kkstudio.platform.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.harness.plugin.api.PluginCatalog;
import fun.fengwk.kkstudio.harness.runtime.skill.LoadSkillTool;
import fun.fengwk.kkstudio.harness.runtime.subagent.TaskTool;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 验证 runtime 工具是 Spring bean，并可通过共享 registry 解析。 */
class RuntimeToolsWiringTest extends PostgresSpringTestSupport {

  @Autowired private List<Tool> tools;
  @Autowired private AgentToolRegistry toolRegistry;
  @Autowired private PluginCatalog pluginCatalog;
  @Autowired private LoadSkillTool loadSkillTool;
  @Autowired private TaskTool taskTool;

  @Test
  void registersPlatformTools() {
    Set<String> beanNames =
        tools.stream().map(tool -> tool.descriptor().name()).collect(Collectors.toSet());
    assertEquals(Set.of("load_skill", "task"), beanNames);
    assertEquals("1", loadSkillTool.descriptor().version());
    assertEquals("1", taskTool.descriptor().version());
    assertTrue(toolRegistry.find("load_skill", "1").isPresent());
    assertTrue(toolRegistry.find("task", "1").isPresent());
    assertEquals(
        LoadSkillTool.AGENT_TOOL_ID, toolRegistry.find("load_skill", "1").orElseThrow().id());
    assertEquals(TaskTool.AGENT_TOOL_ID, toolRegistry.find("task", "1").orElseThrow().id());
    assertTrue(toolRegistry.find("create_goal", "2").isEmpty());
    assertTrue(pluginCatalog.tools().isEmpty());
    assertTrue(toolRegistry.findSelectable("create_goal").isEmpty());
  }
}
