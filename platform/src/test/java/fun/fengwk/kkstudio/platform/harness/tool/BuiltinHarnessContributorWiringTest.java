package fun.fengwk.kkstudio.platform.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.harness.builtin.BuiltinHarnessContributor;
import fun.fengwk.kkstudio.harness.builtin.BuiltinToolIds;
import fun.fengwk.kkstudio.harness.builtin.skill.LoadSkillTool;
import fun.fengwk.kkstudio.harness.builtin.subagent.TaskTool;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.platform.cloudfs.tool.CloudHarnessContributor;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import java.util.List;

/**
 * 验证 BuiltinHarnessContributor 与 CloudHarnessContributor 是 Spring bean，并可通过唯一的 HarnessCatalog 统一解析。
 */
class BuiltinHarnessContributorWiringTest extends PostgresSpringTestSupport {

  @Autowired private List<HarnessContributor> contributors;
  @Autowired private BuiltinHarnessContributor builtinContributor;
  @Autowired private CloudHarnessContributor cloudContributor;
  @Autowired private HarnessCatalog harnessCatalog;
  @Autowired private LoadSkillTool loadSkillTool;
  @Autowired private TaskTool taskTool;

  @Test
  void registersBuiltinContributorAndExposesToolsThroughCatalog() {
    assertTrue(contributors.contains(builtinContributor));
    assertTrue(contributors.contains(cloudContributor));
    assertEquals(BuiltinHarnessContributor.ID, builtinContributor.descriptor().id());
    assertEquals(CloudHarnessContributor.ID, cloudContributor.descriptor().id());
    assertEquals("1", loadSkillTool.descriptor().version());
    assertEquals("1", taskTool.descriptor().version());

    assertTrue(harnessCatalog.findTool(BuiltinToolIds.LOAD_SKILL).isPresent());
    assertTrue(harnessCatalog.findTool(BuiltinToolIds.TASK).isPresent());
    assertTrue(harnessCatalog.findTool(BuiltinToolIds.READ).isPresent());
    assertTrue(harnessCatalog.findTool(BuiltinToolIds.GOAL_CREATE).isPresent());

    // 验证 Cloud 工具同时由 HarnessCatalog 统一解析
    assertTrue(harnessCatalog.findTool(CloudHarnessContributor.TOOL_ID_READ).isPresent());
    assertTrue(harnessCatalog.findTool(CloudHarnessContributor.TOOL_ID_WRITE).isPresent());
    assertTrue(harnessCatalog.findTool(CloudHarnessContributor.TOOL_ID_EDIT).isPresent());
    assertTrue(harnessCatalog.findTool(CloudHarnessContributor.TOOL_ID_FIND).isPresent());
    assertTrue(harnessCatalog.findTool(CloudHarnessContributor.TOOL_ID_GREP).isPresent());

    assertEquals(
        BuiltinToolIds.LOAD_SKILL,
        harnessCatalog.findTool(BuiltinToolIds.LOAD_SKILL).orElseThrow().definition().id());
    assertEquals(
        BuiltinToolIds.TASK,
        harnessCatalog.findTool(BuiltinToolIds.TASK).orElseThrow().definition().id());
    assertEquals(
        CloudHarnessContributor.TOOL_ID_READ,
        harnessCatalog
            .findTool(CloudHarnessContributor.TOOL_ID_READ)
            .orElseThrow()
            .definition()
            .id());
    assertTrue(harnessCatalog.findTool(new AgentToolId("test.missing-tool")).isEmpty());
  }
}
