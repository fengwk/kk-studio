package fun.fengwk.kkstudio.platform.project.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderer;
import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.util.List;
import java.util.Optional;

/**
 * {@link ProjectHistoryRenderers} 单元测试。
 *
 * <p>测试意图：
 *
 * <ul>
 *   <li>验证全部 3 个 ProjectRoleToolType 动作文本的精确渲染；
 *   <li>验证 issue_request_input 支持带 question 与默认 fallback 文本；
 *   <li>验证 issue_review 支持带 decision 渲染与 decision 缺失/空白时返回 Optional.empty()；
 *   <li>验证畸形 JSON、非对象 JSON 传入时安全解析，不抛出异常；
 *   <li>验证长正文（context、reason 等）与执行控制值绝不泄漏到渲染结果中；
 *   <li>验证 ProjectRoleTool.historyRenderer() 返回的渲染器与 ProjectHistoryRenderers 一致可用。
 * </ul>
 */
class ProjectHistoryRenderersTest {

  private static final List<String> LEAK_CHECK_KEYWORDS =
      List.of("sensitive-context-detail", "long-reason-text-should-not-leak", "cursor-value-999");

  private static final String NOISY_PAYLOAD_FIELDS =
      """
      "context": "sensitive-context-detail",
      "reason": "long-reason-text-should-not-leak",
      "activity_after_sequence": 999
      """;

  @Test
  void renderIssueReadReturnsExactAction() {
    // ISSUE_READ → 恒定 "read issue details"
    ToolHistoryRenderer renderer = ProjectHistoryRenderers.of(ProjectRoleToolType.ISSUE_READ);
    ToolCall call = new ToolCall("call-1", "issue_read", "{\"issue_id\":\"iss-101\"}");

    Optional<String> action = renderer.render(new ToolHistoryRenderRequest(call, null));

    assertTrue(action.isPresent());
    assertEquals("read issue details", action.get());
  }

  @Test
  void renderIssueRequestInputWithAndWithoutQuestion() {
    // ISSUE_REQUEST_INPUT → 带 question 时为 "ask for input: " + question；无 question 时为 "ask for
    // external input"
    ToolHistoryRenderer renderer =
        ProjectHistoryRenderers.of(ProjectRoleToolType.ISSUE_REQUEST_INPUT);

    // 1. 带 question
    ToolCall callWithQuestion =
        new ToolCall(
            "call-1", "issue_request_input", "{\"question\":\"What is the expected behavior?\"}");
    assertEquals(
        "ask for input: What is the expected behavior?",
        renderer.render(new ToolHistoryRenderRequest(callWithQuestion, null)).orElseThrow());

    // 2. 无参
    ToolCall callEmpty = new ToolCall("call-2", "issue_request_input", "{}");
    assertEquals(
        "ask for external input",
        renderer.render(new ToolHistoryRenderRequest(callEmpty, null)).orElseThrow());

    // 3. 空白 question
    ToolCall callBlank = new ToolCall("call-3", "issue_request_input", "{\"question\":\"   \"}");
    assertEquals(
        "ask for external input",
        renderer.render(new ToolHistoryRenderRequest(callBlank, null)).orElseThrow());
  }

  @Test
  void renderIssueReviewReturnsExactAction() {
    // ISSUE_REVIEW → "submit review decision: " + decision
    ToolHistoryRenderer renderer = ProjectHistoryRenderers.of(ProjectRoleToolType.ISSUE_REVIEW);

    ToolCall callApprove =
        new ToolCall(
            "call-1", "issue_review", "{\"decision\":\"APPROVE\",\"reason\":\"Looks good\"}");
    assertEquals(
        "submit review decision: APPROVE",
        renderer.render(new ToolHistoryRenderRequest(callApprove, null)).orElseThrow());

    ToolCall callRequestChanges =
        new ToolCall(
            "call-2",
            "issue_review",
            "{\"decision\":\"REQUEST_CHANGES\",\"reason\":\"Please fix the bug\"}");
    assertEquals(
        "submit review decision: REQUEST_CHANGES",
        renderer.render(new ToolHistoryRenderRequest(callRequestChanges, null)).orElseThrow());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"decision\":\"\"}",
        "{\"decision\":\"   \"}",
        "{\"decision\":null}",
        "{\"decision\":123}",
        "{\"decision\":true}"
      })
  void issueReviewReturnsEmptyOnMissingOrBlankOrNonTextDecision(String argumentsJson) {
    // 验证 ISSUE_REVIEW 在 decision 缺失或空白时返回 empty
    ToolCall call = new ToolCall("call-1", "issue_review", argumentsJson);
    assertTrue(
        ProjectHistoryRenderers.of(ProjectRoleToolType.ISSUE_REVIEW)
            .render(new ToolHistoryRenderRequest(call, null))
            .isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"invalid json syntax", "[1, 2, 3]", "\"scalar string\"", "12345", "null"})
  void malformedJsonSafelyHandledWithoutExceptions(String malformedJson) {
    // 验证畸形 JSON 或非对象输入时安全降级，依赖 decision 的 issue_review 返回 empty，其他工具返回安全文本，绝不上抛异常
    ToolCall mockCall = mock(ToolCall.class);
    when(mockCall.argumentsJson()).thenReturn(malformedJson);
    ToolHistoryRenderRequest request = new ToolHistoryRenderRequest(mockCall, null);

    // ISSUE_REVIEW 依赖 decision 参数，畸形输入返回 empty
    Optional<String> reviewRendered =
        ProjectHistoryRenderers.of(ProjectRoleToolType.ISSUE_REVIEW).render(request);
    assertTrue(reviewRendered.isEmpty());

    // ISSUE_READ 和 ISSUE_REQUEST_INPUT 返回安全默认文本
    assertEquals(
        "read issue details",
        ProjectHistoryRenderers.of(ProjectRoleToolType.ISSUE_READ).render(request).orElseThrow());
    assertEquals(
        "ask for external input",
        ProjectHistoryRenderers.of(ProjectRoleToolType.ISSUE_REQUEST_INPUT)
            .render(request)
            .orElseThrow());
  }

  @Test
  void noLongBodiesOrExecutionControlsLeakedInAnyOfTheThreeTools() {
    // 验证任何动作输出绝不包含 context、reason 等长正文或内部游标值
    for (ProjectRoleToolType type : ProjectRoleToolType.values()) {
      String primaryArgs =
          switch (type) {
            case ISSUE_READ -> "";
            case ISSUE_REQUEST_INPUT -> "\"question\": \"what is x\"";
            case ISSUE_REVIEW -> "\"decision\": \"APPROVE\"";
          };

      String json =
          primaryArgs.isEmpty()
              ? "{\n" + NOISY_PAYLOAD_FIELDS + "\n}"
              : "{\n" + primaryArgs + ",\n" + NOISY_PAYLOAD_FIELDS + "\n}";
      ToolCall call = new ToolCall("call-" + type.name(), type.modelName(), json);
      Optional<String> actionOpt =
          ProjectHistoryRenderers.of(type).render(new ToolHistoryRenderRequest(call, null));

      assertTrue(
          actionOpt.isPresent(),
          () -> "Expected successful rendering for " + type + " with noisy payload");
      String action = actionOpt.get();

      // 验证动作文本绝不泄漏任何长正文/控制值
      for (String keyword : LEAK_CHECK_KEYWORDS) {
        assertFalse(
            action.contains(keyword),
            () -> "Action for " + type + " leaked keyword '" + keyword + "': " + action);
      }
    }
  }

  @ParameterizedTest
  @EnumSource(ProjectRoleToolType.class)
  void projectRoleToolExposesHistoryRendererMatchingRenderers(ProjectRoleToolType type) {
    // 验证 ProjectRoleTool.historyRenderer() 返回非 empty 且其渲染结果与 ProjectHistoryRenderers 一致
    ProjectThreadOwnerResolver ownerResolver = mock(ProjectThreadOwnerResolver.class);
    ProjectRoleToolService toolService = mock(ProjectRoleToolService.class);
    ProjectRoleTool roleTool = new ProjectRoleTool(type, ownerResolver, toolService);

    Optional<ToolHistoryRenderer> toolRendererOpt = roleTool.historyRenderer();
    assertTrue(
        toolRendererOpt.isPresent(),
        () -> "ProjectRoleTool should have historyRenderer for " + type);

    ToolHistoryRenderer toolRenderer = toolRendererOpt.get();
    ToolHistoryRenderer directRenderer = ProjectHistoryRenderers.of(type);

    ToolCall call =
        new ToolCall(
            "call-1",
            type.modelName(),
            """
            {
              "question": "What is the status?",
              "decision": "APPROVE"
            }
            """);
    ToolHistoryRenderRequest request = new ToolHistoryRenderRequest(call, "test-env");

    assertEquals(directRenderer.render(request), toolRenderer.render(request));
  }
}
