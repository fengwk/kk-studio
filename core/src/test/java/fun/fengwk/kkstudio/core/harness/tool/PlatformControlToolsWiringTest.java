package fun.fengwk.kkstudio.core.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import fun.fengwk.kkstudio.harness.runtime.extension.HarnessExtensionHost;
import fun.fengwk.kkstudio.harness.runtime.goal.CreateGoalTool;
import fun.fengwk.kkstudio.harness.runtime.goal.GetGoalTool;
import fun.fengwk.kkstudio.harness.runtime.goal.UpdateGoalTool;
import fun.fengwk.kkstudio.harness.runtime.skill.LoadSkillTool;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Verifies platform CONTROL tools are Spring beans and registered on the Host. */
@SpringBootTest
class PlatformControlToolsWiringTest {

  @Autowired private List<Tool> tools;
  @Autowired private HarnessExtensionHost host;
  @Autowired private CreateGoalTool createGoalTool;
  @Autowired private GetGoalTool getGoalTool;
  @Autowired private UpdateGoalTool updateGoalTool;
  @Autowired private LoadSkillTool loadSkillTool;

  @Test
  void registersControlToolsOnHost() {
    Set<String> beanNames =
        tools.stream().map(tool -> tool.descriptor().name()).collect(Collectors.toSet());
    assertTrue(
        beanNames.containsAll(Set.of("create_goal", "get_goal", "update_goal", "load_skill")));

    assertEquals("1", createGoalTool.descriptor().version());
    assertEquals(ToolExecutionMode.CONTROL, createGoalTool.descriptor().executionMode());
    assertEquals(ToolExecutionMode.CONTROL, getGoalTool.descriptor().executionMode());
    assertEquals(ToolExecutionMode.CONTROL, updateGoalTool.descriptor().executionMode());
    assertEquals(ToolExecutionMode.CONTROL, loadSkillTool.descriptor().executionMode());

    assertTrue(host.createTool("create_goal", "1").isPresent());
    assertTrue(host.createTool("get_goal", "1").isPresent());
    assertTrue(host.createTool("update_goal", "1").isPresent());
    assertTrue(host.createTool("load_skill", "1").isPresent());
  }
}
