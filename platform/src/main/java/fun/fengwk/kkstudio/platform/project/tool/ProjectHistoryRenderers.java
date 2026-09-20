package fun.fengwk.kkstudio.platform.project.tool;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.common.json.ToolArguments;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderer;

import java.util.Optional;

/**
 * Project/Issue 角色工具的历史动作渲染器：只保留动作与相关 issue / status / dependency 身份。
 *
 * <p>刻意省略 {@code expected_version}、{@code observed_spec_revision}、{@code observed_input_sequence}
 * 等并发控制游标——它们 不改变动作语义；同时不复制 {@code description} / {@code summary} 等长篇正文，因为它们已由结果表达。无法形成有意义动作时返回
 * empty， 由 Runtime 中性回退。
 */
final class ProjectHistoryRenderers {

  private ProjectHistoryRenderers() {}

  static ToolHistoryRenderer of(ProjectRoleToolType type) {
    return request -> render(type, request);
  }

  private static Optional<String> render(
      ProjectRoleToolType type, ToolHistoryRenderRequest request) {
    JsonNode args = ToolArguments.parse(request.call().argumentsJson());
    return switch (type) {
      case PROJECT_READ -> Optional.of("read the project board");
      case ISSUE_READ -> issueAction(args, "read issue ");
      case ISSUE_LIST -> Optional.of(issueListAction(args));
      case ISSUE_CREATE -> titleAction(args, "create issue: ");
      case ISSUE_UPDATE -> issueAction(args, "update issue ");
      case ISSUE_ADD_DEPENDENCY -> dependencyAction(args, "add dependency ", " to issue ");
      case ISSUE_REMOVE_DEPENDENCY -> dependencyAction(args, "remove dependency ", " from issue ");
      case ISSUE_SET_STATUS -> {
        String issueId = ToolArguments.text(args, "issue_id");
        String status = ToolArguments.text(args, "status");
        yield issueId == null || status == null
            ? Optional.empty()
            : Optional.of("move issue " + issueId + " to " + status);
      }
      case ISSUE_CANCEL -> issueAction(args, "cancel issue ");
        // submit / request_input / review 作用于当前 run，schema 不含 issue_id：动作由 run 类型确定，正文由结果表达。
      case ISSUE_SUBMIT -> Optional.of("submit the completed work for review");
      case ISSUE_REQUEST_INPUT -> Optional.of("ask for external input");
      case ISSUE_REVIEW -> {
        String decision = ToolArguments.text(args, "decision");
        yield decision == null
            ? Optional.empty()
            : Optional.of("review the current run: " + decision);
      }
    };
  }

  private static Optional<String> issueAction(JsonNode args, String prefix) {
    String issueId = ToolArguments.text(args, "issue_id");
    return issueId == null ? Optional.empty() : Optional.of(prefix + issueId);
  }

  private static Optional<String> titleAction(JsonNode args, String prefix) {
    String title = ToolArguments.text(args, "title");
    return title == null ? Optional.empty() : Optional.of(prefix + title);
  }

  private static Optional<String> dependencyAction(JsonNode args, String prefix, String suffix) {
    String issueId = ToolArguments.text(args, "issue_id");
    String dependencyId = ToolArguments.text(args, "depends_on_issue_id");
    return issueId == null || dependencyId == null
        ? Optional.empty()
        : Optional.of(prefix + dependencyId + suffix + issueId);
  }

  /** 状态过滤改变列出范围，属于语义；include_archived 同样改变结果范围。 */
  private static String issueListAction(JsonNode args) {
    String status = ToolArguments.text(args, "status");
    StringBuilder action = new StringBuilder("list issues");
    if (status != null) {
      action.append(" in status ").append(status);
    }
    if (ToolArguments.flag(args, "include_archived")) {
      action.append(" including archived");
    }
    return action.toString();
  }
}
