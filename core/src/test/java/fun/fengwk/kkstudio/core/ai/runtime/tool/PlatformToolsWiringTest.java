package fun.fengwk.kkstudio.core.ai.runtime.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.goal.CreateGoalTool;
import fun.fengwk.kkstudio.harness.runtime.goal.GetGoalTool;
import fun.fengwk.kkstudio.harness.runtime.goal.UpdateGoalTool;
import fun.fengwk.kkstudio.harness.runtime.skill.LoadSkillTool;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolFactories;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Verifies platform tools are Spring beans and resolvable through {@link ToolFactories}. */
class PlatformToolsWiringTest extends PostgresSpringTestSupport {

  @Autowired private List<Tool> tools;
  @Autowired private ToolFactories toolFactories;
  @Autowired private CreateGoalTool createGoalTool;
  @Autowired private GetGoalTool getGoalTool;
  @Autowired private UpdateGoalTool updateGoalTool;
  @Autowired private LoadSkillTool loadSkillTool;

  @Test
  void registersPlatformTools() {
    Set<String> beanNames =
        tools.stream().map(tool -> tool.descriptor().name()).collect(Collectors.toSet());
    assertTrue(
        beanNames.containsAll(Set.of("create_goal", "get_goal", "update_goal", "load_skill")));

    assertEquals("1", createGoalTool.descriptor().version());

    assertTrue(toolFactories.find("create_goal", "1").isPresent());
    assertTrue(toolFactories.find("get_goal", "1").isPresent());
    assertTrue(toolFactories.find("update_goal", "1").isPresent());
    assertTrue(toolFactories.find("load_skill", "1").isPresent());
  }
}
