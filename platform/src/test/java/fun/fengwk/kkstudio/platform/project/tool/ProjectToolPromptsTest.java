package fun.fengwk.kkstudio.platform.project.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;

/**
 * {@link ProjectToolPrompts} 单元测试。
 *
 * <p>测试意图：
 *
 * <ul>
 *   <li>验证所有 12 个工具的 Prompt Markdown 资源均能正常读取且非空；
 *   <li>验证所有 12 个工具的 InputSchema 资源均能正常解析；
 *   <li>验证不存在的资源抛出异常。
 * </ul>
 */
class ProjectToolPromptsTest {

  @Test
  void loadsAll12ToolPromptsAndSchemas() {
    // 验证所有 12 个工具的静态资源可读性
    for (ProjectRoleToolType type : ProjectRoleToolType.values()) {
      String prompt = ProjectToolPrompts.prompt(type.modelName());
      assertNotNull(prompt);
      assertFalse(prompt.isBlank());

      InputSchema schema = ProjectToolPrompts.schema(type.modelName());
      assertNotNull(schema);
    }
  }

  @Test
  void nonExistentResourceThrowsException() {
    // 验证读取不存在的 Prompt 或 Schema 资源时抛出异常
    assertThrows(
        IllegalStateException.class, () -> ProjectToolPrompts.prompt("non_existent_tool_name"));
    assertThrows(
        IllegalStateException.class, () -> ProjectToolPrompts.schema("non_existent_tool_name"));
  }
}
