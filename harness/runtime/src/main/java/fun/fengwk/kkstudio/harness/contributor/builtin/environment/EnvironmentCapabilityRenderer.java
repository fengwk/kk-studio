package fun.fengwk.kkstudio.harness.contributor.builtin.environment;

import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.common.json.ToolArguments;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderer;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;

import java.util.Optional;

/**
 * Environment capability 的历史动作渲染器：按 capability 语义生成 “动词 + 目标” 短语。
 *
 * <p>保留决定目标作用域的 {@code process.exec workdir}、{@code grep.glob} 以及会改变解释的 {@code edit.replace_all} /
 * {@code grep} 匹配标志；省略 {@code timeout_seconds}、{@code limit}、{@code offset}、{@code column_offset}
 * 等执行预算或结果窗口参数，具体结果仍由 ToolResult 表达。无法形成有意义动作时返回 empty，由 Runtime 中性回退（回退逐字保留全部 arguments）。
 */
final class EnvironmentCapabilityRenderer implements ToolHistoryRenderer {

  private final EnvironmentCapabilityId capabilityId;

  private EnvironmentCapabilityRenderer(EnvironmentCapabilityId capabilityId) {
    this.capabilityId = capabilityId;
  }

  static EnvironmentCapabilityRenderer of(EnvironmentCapabilityId capabilityId) {
    return new EnvironmentCapabilityRenderer(capabilityId);
  }

  @Override
  public Optional<String> render(ToolHistoryRenderRequest request) {
    JsonNode args = ToolArguments.parse(request.call().argumentsJson());
    if (args == null) {
      return Optional.empty();
    }
    String action = actionOf(args);
    if (action == null) {
      return Optional.empty();
    }
    return Optional.of(withEnvironment(withWorkdir(action, args), request.environmentName()));
  }

  private String actionOf(JsonNode args) {
    String path = ToolArguments.text(args, "path");
    if (capabilityId.equals(EnvironmentCapabilityIds.FS_READ)) {
      return path == null ? null : "read " + path;
    }
    if (capabilityId.equals(EnvironmentCapabilityIds.FS_WRITE)) {
      return path == null ? null : "write " + path;
    }
    if (capabilityId.equals(EnvironmentCapabilityIds.FS_EDIT)) {
      if (path == null) {
        return null;
      }
      return editAction(path, args);
    }
    if (capabilityId.equals(EnvironmentCapabilityIds.PROCESS_EXEC)) {
      String command = ToolArguments.text(args, "command");
      return command == null ? null : "run command " + command;
    }
    if (capabilityId.equals(EnvironmentCapabilityIds.FS_GREP)) {
      String pattern = ToolArguments.text(args, "pattern");
      if (pattern == null) {
        return null;
      }
      // 大小写/字面量直接改变匹配语义，属于语义标志而非执行控制。
      StringBuilder action = new StringBuilder("search for ").append(pattern);
      if (ToolArguments.flag(args, "literal")) {
        action.append(" as literal text");
      }
      if (ToolArguments.flag(args, "ignore_case")) {
        action.append(" ignoring case");
      }
      if (path != null) {
        action.append(" in ").append(path);
      }
      String glob = ToolArguments.text(args, "glob");
      if (glob != null) {
        action.append(" within files matching ").append(glob);
      }
      return action.toString();
    }
    if (capabilityId.equals(EnvironmentCapabilityIds.FS_FIND)) {
      String pattern = ToolArguments.text(args, "pattern");
      if (pattern == null) {
        return null;
      }
      return path == null ? "find files matching " + pattern : "find " + pattern + " under " + path;
    }
    if (capabilityId.equals(EnvironmentCapabilityIds.LSP_GOTO_DEFINITION)) {
      return path == null ? null : "go to the definition at " + path;
    }
    if (capabilityId.equals(EnvironmentCapabilityIds.LSP_WORKSPACE_SYMBOLS)) {
      String query = ToolArguments.text(args, "query");
      return query == null ? null : "search workspace symbols for " + query;
    }
    if (capabilityId.equals(EnvironmentCapabilityIds.LSP_JAVA_DECOMPILE)) {
      String target = ToolArguments.text(args, "target");
      return target == null ? null : "decompile " + target;
    }
    return null;
  }

  /** edit 的条目数与 replace_all 改变动作语义，必须保留；old_string/new_string 是文件内容，绝不渲染。 */
  private static String editAction(String path, JsonNode args) {
    JsonNode edits = args.get("edits");
    if (edits == null || !edits.isArray() || edits.isEmpty()) {
      return null;
    }
    boolean replaceAll = false;
    for (JsonNode edit : edits) {
      if (ToolArguments.flag(edit, "replace_all")) {
        replaceAll = true;
        break;
      }
    }
    if (edits.size() == 1) {
      return replaceAll ? "replace every occurrence in " + path : "edit " + path;
    }
    String changes = edits.size() + " changes";
    return replaceAll
        ? "edit " + path + " (" + changes + ", some replacing every occurrence)"
        : "edit " + path + " (" + changes + ")";
  }

  /** workdir 只决定 {@code process.exec} 的实际执行目录，属于动作语义；文件工具不接受 workdir。 */
  private String withWorkdir(String action, JsonNode args) {
    if (!capabilityId.equals(EnvironmentCapabilityIds.PROCESS_EXEC)) {
      return action;
    }
    String workdir = ToolArguments.text(args, "workdir");
    return workdir == null ? action : action + " from " + workdir;
  }

  /** 环境名是 branch 选择的用户可见事实；动作没有它就无法与其它环境区分。 */
  private static String withEnvironment(String action, String environmentName) {
    return environmentName == null ? action : action + " in environment " + environmentName;
  }
}
