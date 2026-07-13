package fun.fengwk.kkstudio.harness.tool;

import fun.fengwk.kkstudio.harness.tool.schema.ToolArgumentsValidator;

/** JSON 工具内容。 */
public record JsonToolContent(String json) implements ToolContent {

  public JsonToolContent {
    json = ToolArgumentsValidator.requireValidJson(json);
  }
}
