package fun.fengwk.kkstudio.platform.vfs;

import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.common.json.ToolArguments;

import java.util.Optional;
import java.util.UUID;

/**
 * 只读命名空间的写入拒绝：{@code vfs:/skills} 与 {@code vfs:/resources} 下的 {@code write}/{@code edit} 一律失败。
 *
 * <p>同一份判定与文案同时用于两处：工具网关在权限评估与 YOLO 之前做准入门禁（未执行、不进入审批流程），VFS 执行入口再做一次权威检查。 文案遵循 PLAN 3.1
 * 的示例，并带有「未执行」这一确定的执行事实。
 */
public final class VfsWriteGuard {

  private static final String SKILLS_ROOT = "vfs:/skills";
  private static final String RESOURCES_ROOT = "vfs:/resources";
  private static final String WRITE_TOOL = "write";
  private static final String EDIT_TOOL = "edit";

  private VfsWriteGuard() {}

  /**
   * 判断本次调用是否写只读命名空间。
   *
   * @param toolName 模型可见工具名；只有 {@code write} 与 {@code edit} 会写文件
   * @param argumentsJson 调用参数原文
   * @param rootSessionId 执行根会话；准入门禁阶段未知时为 null，文案退化为「当前对话目录」
   * @return 确定性的拒绝文案；非只读写入返回 empty
   */
  public static Optional<String> rejection(
      String toolName, String argumentsJson, UUID rootSessionId) {
    if (!WRITE_TOOL.equals(toolName) && !EDIT_TOOL.equals(toolName)) {
      return Optional.empty();
    }
    String path;
    try {
      JsonNode arguments = ToolArguments.parse(argumentsJson);
      path = ToolArguments.text(arguments, "path");
    } catch (RuntimeException unparsable) {
      // 参数非法由执行层的参数校验负责；这里只识别明确的只读写入。
      return Optional.empty();
    }
    if (path == null) {
      return Optional.empty();
    }
    if (isUnder(path, SKILLS_ROOT)) {
      return Optional.of(message(toolName, path, SKILLS_ROOT, rootSessionId));
    }
    if (isUnder(path, RESOURCES_ROOT)) {
      return Optional.of(message(toolName, path, RESOURCES_ROOT, rootSessionId));
    }
    return Optional.empty();
  }

  /** 只读写入的固定文案：拒绝原因、未执行事实与可写位置。 */
  public static String message(
      String operation, String path, String readOnlyRoot, UUID rootSessionId) {
    String writableRoot =
        rootSessionId == null
            ? "the current conversation's vfs:/sessions/ directory"
            : "vfs:/sessions/" + rootSessionId + "/";
    return "Cannot "
        + operation
        + " "
        + path
        + ": "
        + readOnlyRoot
        + " is read-only. The tool was not executed. Write under "
        + writableRoot
        + " instead.";
  }

  private static boolean isUnder(String path, String root) {
    return path.equals(root) || path.startsWith(root + "/");
  }
}
