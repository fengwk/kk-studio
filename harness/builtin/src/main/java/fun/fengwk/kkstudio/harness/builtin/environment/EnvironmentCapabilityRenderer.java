package fun.fengwk.kkstudio.harness.builtin.environment;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.common.json.ToolArguments;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderer;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;

import java.util.Optional;

/**
 * Environment capability 的历史动作渲染器：按 capability 语义生成 “动词 + 目标” 短语。
 *
 * <p>保留决定目标作用域的 {@code workdir}、{@code grep.include} 以及会改变解释的 {@code edit.replace_all} / {@code
 * grep} 匹配标志；省略 {@code timeout_seconds}、{@code limit}、{@code offset}、{@code column_offset}
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
      // replace_all 改变“替换一处”与“替换全部”的语义差别，必须保留。
      return ToolArguments.flag(args, "replace_all")
          ? "replace every occurrence in " + path
          : "edit " + path;
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
      // 大小写/字面量/多行直接改变匹配语义，属于语义标志而非执行控制。
      StringBuilder action = new StringBuilder("search for ").append(pattern);
      if (ToolArguments.flag(args, "literal")) {
        action.append(" as literal text");
      }
      if (ToolArguments.flag(args, "ignore_case")) {
        action.append(" ignoring case");
      }
      if (ToolArguments.flag(args, "multiline")) {
        action.append(" across lines");
      }
      if (path != null) {
        action.append(" in ").append(path);
      }
      String include = ToolArguments.text(args, "include");
      if (include != null) {
        action.append(" within files matching ").append(include);
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

  /** workdir 决定相对路径、命令与 LSP workspace 的实际作用域，属于动作语义而非执行控制。 */
  private static String withWorkdir(String action, JsonNode args) {
    String workdir = ToolArguments.text(args, "workdir");
    return workdir == null ? action : action + " from " + workdir;
  }

  /** 环境名是 branch 选择的用户可见事实；动作没有它就无法与其它环境区分。 */
  private static String withEnvironment(String action, String environmentName) {
    return environmentName == null ? action : action + " in environment " + environmentName;
  }
}
