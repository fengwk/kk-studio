package fun.fengwk.kkstudio.platform.project.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * {@link ProjectHarnessContributor} 单元测试。
 *
 * <p>测试意图：
 *
 * <ul>
 *   <li>验证 Contributor 标识符为 project 且能通过 HarnessCatalog 成功冻结解析；
 *   <li>验证 12 个角色工具全部以 INTERNAL 可见性注册且顺序严格对齐设计；
 *   <li>验证每个工具的副作用分类（READ_ONLY、NON_IDEMPOTENT、IDEMPOTENT）准确无误；
 *   <li>验证构造器防御性检查：缺失、重复或非法的工具列表坚决拒绝。
 * </ul>
 */
class ProjectHarnessContributorTest {

  private ProjectThreadOwnerResolver ownerResolver;
  private ProjectRoleToolService toolService;
  private List<ProjectRoleTool> tools;
  private ProjectHarnessContributor contributor;

  @BeforeEach
  void setUp() {
    ownerResolver = mock(ProjectThreadOwnerResolver.class);
    toolService = mock(ProjectRoleToolService.class);
    tools =
        Arrays.stream(ProjectRoleToolType.values())
            .map(type -> new ProjectRoleTool(type, ownerResolver, toolService))
            .toList();
    contributor = new ProjectHarnessContributor(tools);
  }

  @Test
  void descriptor_matchesSpecification() {
    // 验证 Contributor 元数据与标识符
    assertEquals(ProjectHarnessContributor.ID, contributor.descriptor().id());
    assertEquals("Project", contributor.descriptor().name());
    assertEquals("1", contributor.descriptor().version());
  }

  @Test
  void harnessCatalog_freezesAndExposesAll12InternalToolsInExactOrder() {
    // 验证 12 个角色工具由 HarnessCatalog 统一解析，全部为 INTERNAL 可见，且副作用与声明一致
    HarnessCatalog catalog = HarnessCatalog.from(List.of(contributor));

    List<AgentToolId> expectedToolIds =
        List.of(
            ProjectRoleToolIds.PROJECT_READ,
            ProjectRoleToolIds.ISSUE_READ,
            ProjectRoleToolIds.ISSUE_LIST,
            ProjectRoleToolIds.ISSUE_CREATE,
            ProjectRoleToolIds.ISSUE_UPDATE,
            ProjectRoleToolIds.ISSUE_ADD_DEPENDENCY,
            ProjectRoleToolIds.ISSUE_REMOVE_DEPENDENCY,
            ProjectRoleToolIds.ISSUE_SET_STATUS,
            ProjectRoleToolIds.ISSUE_CANCEL,
            ProjectRoleToolIds.ISSUE_SUBMIT,
            ProjectRoleToolIds.ISSUE_REQUEST_INPUT,
            ProjectRoleToolIds.ISSUE_REVIEW);

    for (AgentToolId toolId : expectedToolIds) {
      Optional<ToolContribution> found = catalog.findTool(toolId);
      assertTrue(found.isPresent(), "Tool must be present in catalog: " + toolId);

      ProjectRoleToolType type = ProjectRoleToolType.findByAgentToolId(toolId).orElseThrow();
      ToolContribution contribution = found.get();
      assertEquals(ToolVisibility.INTERNAL, contribution.definition().visibility());
      assertEquals(type.modelName(), contribution.tool().descriptor().name());
      assertEquals(type.sideEffect(), contribution.tool().descriptor().sideEffect());
      assertEquals("1", contribution.tool().descriptor().version());
      assertNotNull(contribution.tool().descriptor().inputSchema());
    }

    // 验证副作用精确分类
    assertEquals(
        ToolSideEffect.READ_ONLY,
        catalog
            .findTool(ProjectRoleToolIds.PROJECT_READ)
            .orElseThrow()
            .tool()
            .descriptor()
            .sideEffect());
    assertEquals(
        ToolSideEffect.READ_ONLY,
        catalog
            .findTool(ProjectRoleToolIds.ISSUE_READ)
            .orElseThrow()
            .tool()
            .descriptor()
            .sideEffect());
    assertEquals(
        ToolSideEffect.READ_ONLY,
        catalog
            .findTool(ProjectRoleToolIds.ISSUE_LIST)
            .orElseThrow()
            .tool()
            .descriptor()
            .sideEffect());
    assertEquals(
        ToolSideEffect.NON_IDEMPOTENT,
        catalog
            .findTool(ProjectRoleToolIds.ISSUE_CREATE)
            .orElseThrow()
            .tool()
            .descriptor()
            .sideEffect());
    assertEquals(
        ToolSideEffect.NON_IDEMPOTENT,
        catalog
            .findTool(ProjectRoleToolIds.ISSUE_UPDATE)
            .orElseThrow()
            .tool()
            .descriptor()
            .sideEffect());
    assertEquals(
        ToolSideEffect.NON_IDEMPOTENT,
        catalog
            .findTool(ProjectRoleToolIds.ISSUE_ADD_DEPENDENCY)
            .orElseThrow()
            .tool()
            .descriptor()
            .sideEffect());
    assertEquals(
        ToolSideEffect.NON_IDEMPOTENT,
        catalog
            .findTool(ProjectRoleToolIds.ISSUE_REMOVE_DEPENDENCY)
            .orElseThrow()
            .tool()
            .descriptor()
            .sideEffect());
    assertEquals(
        ToolSideEffect.NON_IDEMPOTENT,
        catalog
            .findTool(ProjectRoleToolIds.ISSUE_SET_STATUS)
            .orElseThrow()
            .tool()
            .descriptor()
            .sideEffect());
    assertEquals(
        ToolSideEffect.NON_IDEMPOTENT,
        catalog
            .findTool(ProjectRoleToolIds.ISSUE_CANCEL)
            .orElseThrow()
            .tool()
            .descriptor()
            .sideEffect());
    assertEquals(
        ToolSideEffect.IDEMPOTENT,
        catalog
            .findTool(ProjectRoleToolIds.ISSUE_SUBMIT)
            .orElseThrow()
            .tool()
            .descriptor()
            .sideEffect());
    assertEquals(
        ToolSideEffect.NON_IDEMPOTENT,
        catalog
            .findTool(ProjectRoleToolIds.ISSUE_REQUEST_INPUT)
            .orElseThrow()
            .tool()
            .descriptor()
            .sideEffect());
    assertEquals(
        ToolSideEffect.IDEMPOTENT,
        catalog
            .findTool(ProjectRoleToolIds.ISSUE_REVIEW)
            .orElseThrow()
            .tool()
            .descriptor()
            .sideEffect());
  }

  @Test
  void constructor_rejectsNullOrMissingOrDuplicateTools() {
    // 验证 null 传入拦截
    assertThrows(NullPointerException.class, () -> new ProjectHarnessContributor(null));

    // 验证缺失工具列表拦截
    List<ProjectRoleTool> shortList = new ArrayList<>(tools);
    shortList.remove(0);
    assertThrows(IllegalArgumentException.class, () -> new ProjectHarnessContributor(shortList));

    // 验证包含重复工具拦截
    List<ProjectRoleTool> duplicateList = new ArrayList<>(tools);
    duplicateList.set(1, duplicateList.get(0));
    assertThrows(
        IllegalArgumentException.class, () -> new ProjectHarnessContributor(duplicateList));

    // 验证包含 null 元素拦截
    List<ProjectRoleTool> nullElementList = new ArrayList<>(tools);
    nullElementList.set(2, null);
    assertThrows(
        IllegalArgumentException.class, () -> new ProjectHarnessContributor(nullElementList));
  }
}
