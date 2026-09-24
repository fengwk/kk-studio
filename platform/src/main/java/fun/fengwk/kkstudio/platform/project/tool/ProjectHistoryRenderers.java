package fun.fengwk.kkstudio.platform.project.tool;

import com.fasterxml.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.common.json.ToolArguments;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderer;

import java.util.Optional;

/** Issue 角色工具的历史动作渲染器：只保留动作与关键参数。 */
final class ProjectHistoryRenderers {

  private ProjectHistoryRenderers() {}

  static ToolHistoryRenderer of(ProjectRoleToolType type) {
    return request -> render(type, request);
  }

  private static Optional<String> render(
      ProjectRoleToolType type, ToolHistoryRenderRequest request) {
    JsonNode args = ToolArguments.parse(request.call().argumentsJson());
    return switch (type) {
      case ISSUE_READ -> Optional.of("read issue details");
      case ISSUE_REQUEST_INPUT -> {
        String question = ToolArguments.text(args, "question");
        yield question != null && !question.isBlank()
            ? Optional.of("ask for input: " + question)
            : Optional.of("ask for external input");
      }
      case ISSUE_REVIEW -> {
        String decision = ToolArguments.text(args, "decision");
        yield decision != null
            ? Optional.of("submit review decision: " + decision)
            : Optional.empty();
      }
    };
  }
}
