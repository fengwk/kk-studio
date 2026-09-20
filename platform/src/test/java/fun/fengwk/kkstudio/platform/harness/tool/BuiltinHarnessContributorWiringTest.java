package fun.fengwk.kkstudio.platform.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.harness.builtin.BuiltinHarnessContributor;
import fun.fengwk.kkstudio.harness.builtin.environment.ReadTool;
import fun.fengwk.kkstudio.harness.builtin.subagent.TaskTool;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;

import java.util.List;

/** 验证 BuiltinHarnessContributor 是 Spring bean，并可通过唯一的 HarnessCatalog 统一解析。 */
class BuiltinHarnessContributorWiringTest extends PostgresSpringTestSupport {

  @Autowired private List<HarnessContributor> contributors;
  @Autowired private BuiltinHarnessContributor builtinContributor;
  @Autowired private HarnessCatalog harnessCatalog;
  @Autowired private ReadTool readTool;
  @Autowired private TaskTool taskTool;

  @Test
  void registersBuiltinContributorAndExposesToolsThroughCatalog() {
    assertTrue(contributors.contains(builtinContributor));
    assertEquals(BuiltinHarnessContributor.ID, builtinContributor.descriptor().id());
    assertEquals(ReadTool.NAME, readTool.descriptor().name());
    assertEquals(TaskTool.NAME, taskTool.descriptor().name());

    assertTrue(harnessCatalog.findTool(ReadTool.NAME).isPresent());
    assertTrue(harnessCatalog.findTool(TaskTool.NAME).isPresent());
    assertTrue(harnessCatalog.findTool("create_goal").isPresent());

    assertEquals(
        ReadTool.NAME,
        harnessCatalog.findTool(ReadTool.NAME).orElseThrow().definition().descriptor().name());
    assertEquals(
        TaskTool.NAME,
        harnessCatalog.findTool(TaskTool.NAME).orElseThrow().definition().descriptor().name());
    assertTrue(harnessCatalog.findTool("test_missing_tool").isEmpty());
  }
}
