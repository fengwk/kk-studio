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
 *   <li>验证全部 12 个 ProjectRoleToolType 动作文本的精确渲染；
 *   <li>验证 issue_list 支持 status 过滤、include_archived 标志与顺序组合（先 status 再 archived）；
 *   <li>验证核心字段缺失、非文本（如数字/布尔）、空白字符串或 null 时安全返回 Optional.empty()；
 *   <li>验证畸形 JSON、非对象 JSON 传入时安全解析，不抛出异常；
 *   <li>验证并发游标（expected_version 等）、长正文（description 等）与执行控制值绝不泄漏到渲染结果中；
 *   <li>验证 ProjectRoleTool.historyRenderer() 返回的渲染器与 ProjectHistoryRenderers 一致可用。
 * </ul>
 */
class ProjectHistoryRenderersTest {

  private static final List<String> LEAK_CHECK_KEYWORDS =
      List.of(
          "42",
          "spec-rev-99",
          "108",
          "long-long-description-text",
          "summary-sensitive-text",
          "what-is-the-status",
          "some-deep-context",
          "verification-passed",
          "agent-smith",
          "agent-neo");

  private static final String NOISY_CURSOR_FIELDS =
      """
      "expected_version": 42,
      "observed_spec_revision": "spec-rev-99",
      "observed_input_sequence": 108,
      "description": "long-long-description-text",
      "summary": "summary-sensitive-text",
      "question": "what-is-the-status",
      "context": "some-deep-context",
      "verification": "verification-passed",
      "assignee_agent_name": "agent-smith",
      "reviewer_agent_name": "agent-neo"
      """;

  @Test
  void renderProjectReadReturnsExactAction() {
    // PROJECT_READ（无参数）→ "read the project board"
    ToolHistoryRenderer renderer = ProjectHistoryRenderers.of(ProjectRoleToolType.PROJECT_READ);
    ToolCall call = new ToolCall("call-1", "project_read", "{}");

    Optional<String> action = renderer.render(new ToolHistoryRenderRequest(call, null));

    assertTrue(action.isPresent());
    assertEquals("read the project board", action.get());
  }

  @Test
  void renderIssueReadReturnsExactAction() {
    // ISSUE_READ → "read issue " + issue_id
    ToolHistoryRenderer renderer = ProjectHistoryRenderers.of(ProjectRoleToolType.ISSUE_READ);
    ToolCall call = new ToolCall("call-1", "issue_read", "{\"issue_id\":\"iss-101\"}");

    Optional<String> action = renderer.render(new ToolHistoryRenderRequest(call, null));

    assertTrue(action.isPresent());
    assertEquals("read issue iss-101", action.get());
  }

  @Test
  void renderIssueListWithVariousCombinations() {
    // ISSUE_LIST → "list issues"；status 存在 → " in status " + status；include_archived=true → "
    // including archived"
    ToolHistoryRenderer renderer = ProjectHistoryRenderers.of(ProjectRoleToolType.ISSUE_LIST);

    // 1. 无参
    ToolCall callEmpty = new ToolCall("call-1", "issue_list", "{}");
    assertEquals(
        "list issues",
        renderer.render(new ToolHistoryRenderRequest(callEmpty, null)).orElseThrow());

    // 2. 仅 status
    ToolCall callStatus = new ToolCall("call-2", "issue_list", "{\"status\":\"OPEN\"}");
    assertEquals(
        "list issues in status OPEN",
        renderer.render(new ToolHistoryRenderRequest(callStatus, null)).orElseThrow());

    // 3. 仅 include_archived=true
    ToolCall callArchived = new ToolCall("call-3", "issue_list", "{\"include_archived\":true}");
    assertEquals(
        "list issues including archived",
        renderer.render(new ToolHistoryRenderRequest(callArchived, null)).orElseThrow());

    // 4. include_archived=false 不追加
    ToolCall callArchivedFalse =
        new ToolCall("call-4", "issue_list", "{\"include_archived\":false}");
    assertEquals(
        "list issues",
        renderer.render(new ToolHistoryRenderRequest(callArchivedFalse, null)).orElseThrow());

    // 5. status 与 include_archived=true 组合（顺序：先 status 再 archived）
    ToolCall callBoth =
        new ToolCall("call-5", "issue_list", "{\"status\":\"CLOSED\",\"include_archived\":true}");
    assertEquals(
        "list issues in status CLOSED including archived",
        renderer.render(new ToolHistoryRenderRequest(callBoth, null)).orElseThrow());
  }

  @Test
  void renderIssueCreateReturnsExactAction() {
    // ISSUE_CREATE → "create issue: " + title
    ToolHistoryRenderer renderer = ProjectHistoryRenderers.of(ProjectRoleToolType.ISSUE_CREATE);
    ToolCall call = new ToolCall("call-1", "issue_create", "{\"title\":\"Add semantic renderer\"}");

    Optional<String> action = renderer.render(new ToolHistoryRenderRequest(call, null));

    assertTrue(action.isPresent());
    assertEquals("create issue: Add semantic renderer", action.get());
  }

  @Test
  void renderIssueUpdateReturnsExactAction() {
    // ISSUE_UPDATE → "update issue " + issue_id
    ToolHistoryRenderer renderer = ProjectHistoryRenderers.of(ProjectRoleToolType.ISSUE_UPDATE);
    ToolCall call =
        new ToolCall("call-1", "issue_update", "{\"issue_id\":\"iss-202\",\"expected_version\":1}");

    Optional<String> action = renderer.render(new ToolHistoryRenderRequest(call, null));

    assertTrue(action.isPresent());
    assertEquals("update issue iss-202", action.get());
  }

  @Test
  void renderIssueAddDependencyReturnsExactAction() {
    // ISSUE_ADD_DEPENDENCY → "add dependency " + depends_on_issue_id + " to issue " + issue_id
    ToolHistoryRenderer renderer =
        ProjectHistoryRenderers.of(ProjectRoleToolType.ISSUE_ADD_DEPENDENCY);
    ToolCall call =
        new ToolCall(
            "call-1",
            "issue_add_dependency",
            "{\"issue_id\":\"iss-child\",\"depends_on_issue_id\":\"iss-parent\",\"expected_version\":3}");

    Optional<String> action = renderer.render(new ToolHistoryRenderRequest(call, null));

    assertTrue(action.isPresent());
    assertEquals("add dependency iss-parent to issue iss-child", action.get());
  }

  @Test
  void renderIssueRemoveDependencyReturnsExactAction() {
    // ISSUE_REMOVE_DEPENDENCY → "remove dependency " + depends_on_issue_id + " from issue " +
    // issue_id
    ToolHistoryRenderer renderer =
        ProjectHistoryRenderers.of(ProjectRoleToolType.ISSUE_REMOVE_DEPENDENCY);
    ToolCall call =
        new ToolCall(
            "call-1",
            "issue_remove_dependency",
            "{\"issue_id\":\"iss-child\",\"depends_on_issue_id\":\"iss-parent\",\"expected_version\":4}");

    Optional<String> action = renderer.render(new ToolHistoryRenderRequest(call, null));

    assertTrue(action.isPresent());
    assertEquals("remove dependency iss-parent from issue iss-child", action.get());
  }

  @Test
  void renderIssueSetStatusReturnsExactAction() {
    // ISSUE_SET_STATUS → "move issue " + issue_id + " to " + status
    ToolHistoryRenderer renderer = ProjectHistoryRenderers.of(ProjectRoleToolType.ISSUE_SET_STATUS);
    ToolCall call =
        new ToolCall(
            "call-1",
            "issue_set_status",
            "{\"issue_id\":\"iss-303\",\"status\":\"IN_PROGRESS\",\"expected_version\":5}");

    Optional<String> action = renderer.render(new ToolHistoryRenderRequest(call, null));

    assertTrue(action.isPresent());
    assertEquals("move issue iss-303 to IN_PROGRESS", action.get());
  }

  @Test
  void renderIssueCancelReturnsExactAction() {
    // ISSUE_CANCEL → "cancel issue " + issue_id
    ToolHistoryRenderer renderer = ProjectHistoryRenderers.of(ProjectRoleToolType.ISSUE_CANCEL);
    ToolCall call = new ToolCall("call-1", "issue_cancel", "{\"issue_id\":\"iss-404\"}");

    Optional<String> action = renderer.render(new ToolHistoryRenderRequest(call, null));

    assertTrue(action.isPresent());
    assertEquals("cancel issue iss-404", action.get());
  }

  @Test
  void renderIssueSubmitReturnsExactAction() {
    // ISSUE_SUBMIT（schema 无 issue_id）→ 恒定 "submit the completed work for review"
    ToolHistoryRenderer renderer = ProjectHistoryRenderers.of(ProjectRoleToolType.ISSUE_SUBMIT);
    ToolCall call = new ToolCall("call-1", "issue_submit", "{}");

    Optional<String> action = renderer.render(new ToolHistoryRenderRequest(call, null));

    assertTrue(action.isPresent());
    assertEquals("submit the completed work for review", action.get());
  }

  @Test
  void renderIssueRequestInputReturnsExactAction() {
    // ISSUE_REQUEST_INPUT（schema 无 issue_id）→ 恒定 "ask for external input"
    ToolHistoryRenderer renderer =
        ProjectHistoryRenderers.of(ProjectRoleToolType.ISSUE_REQUEST_INPUT);
    ToolCall call = new ToolCall("call-1", "issue_request_input", "{}");

    Optional<String> action = renderer.render(new ToolHistoryRenderRequest(call, null));

    assertTrue(action.isPresent());
    assertEquals("ask for external input", action.get());
  }

  @Test
  void renderIssueReviewReturnsExactAction() {
    // ISSUE_REVIEW → "review the current run: " + decision
    ToolHistoryRenderer renderer = ProjectHistoryRenderers.of(ProjectRoleToolType.ISSUE_REVIEW);
    ToolCall call = new ToolCall("call-1", "issue_review", "{\"decision\":\"APPROVE\"}");

    Optional<String> action = renderer.render(new ToolHistoryRenderRequest(call, null));

    assertTrue(action.isPresent());
    assertEquals("review the current run: APPROVE", action.get());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"issue_id\":\"\"}",
        "{\"issue_id\":\"   \"}",
        "{\"issue_id\":null}",
        "{\"issue_id\":123}",
        "{\"issue_id\":true}"
      })
  void issueActionReturnsEmptyOnMissingOrBlankOrNonTextIssueId(String argumentsJson) {
    // 验证 ISSUE_READ、ISSUE_UPDATE、ISSUE_CANCEL 在缺失或空白 issue_id 时均返回 empty
    ToolCall readCall = new ToolCall("call-1", "issue_read", argumentsJson);
    ToolCall updateCall = new ToolCall("call-2", "issue_update", argumentsJson);
    ToolCall cancelCall = new ToolCall("call-3", "issue_cancel", argumentsJson);

    assertTrue(
        ProjectHistoryRenderers.of(ProjectRoleToolType.ISSUE_READ)
            .render(new ToolHistoryRenderRequest(readCall, null))
            .isEmpty());
    assertTrue(
        ProjectHistoryRenderers.of(ProjectRoleToolType.ISSUE_UPDATE)
            .render(new ToolHistoryRenderRequest(updateCall, null))
            .isEmpty());
    assertTrue(
        ProjectHistoryRenderers.of(ProjectRoleToolType.ISSUE_CANCEL)
            .render(new ToolHistoryRenderRequest(cancelCall, null))
            .isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"title\":\"\"}",
        "{\"title\":\"   \"}",
        "{\"title\":null}",
        "{\"title\":123}",
        "{\"title\":false}"
      })
  void issueCreateReturnsEmptyOnMissingOrBlankOrNonTextTitle(String argumentsJson) {
    // 验证 ISSUE_CREATE 在缺失或空白 title 时返回 empty
    ToolCall call = new ToolCall("call-1", "issue_create", argumentsJson);
    assertTrue(
        ProjectHistoryRenderers.of(ProjectRoleToolType.ISSUE_CREATE)
            .render(new ToolHistoryRenderRequest(call, null))
            .isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"issue_id\":\"iss-1\"}",
        "{\"depends_on_issue_id\":\"iss-2\"}",
        "{\"issue_id\":\"\",\"depends_on_issue_id\":\"iss-2\"}",
        "{\"issue_id\":\"iss-1\",\"depends_on_issue_id\":\"\"}",
        "{\"issue_id\":\"   \",\"depends_on_issue_id\":\"iss-2\"}",
        "{\"issue_id\":\"iss-1\",\"depends_on_issue_id\":\"   \"}",
        "{\"issue_id\":null,\"depends_on_issue_id\":\"iss-2\"}",
        "{\"issue_id\":\"iss-1\",\"depends_on_issue_id\":null}",
        "{\"issue_id\":123,\"depends_on_issue_id\":\"iss-2\"}",
        "{\"issue_id\":\"iss-1\",\"depends_on_issue_id\":123}"
      })
  void issueDependencyToolsReturnEmptyOnMissingOrBlankOrNonTextFields(String argumentsJson) {
    // 验证 ISSUE_ADD_DEPENDENCY 与 ISSUE_REMOVE_DEPENDENCY 在任一字段缺失或无效时返回 empty
    ToolCall addCall = new ToolCall("call-1", "issue_add_dependency", argumentsJson);
    ToolCall removeCall = new ToolCall("call-2", "issue_remove_dependency", argumentsJson);

    assertTrue(
        ProjectHistoryRenderers.of(ProjectRoleToolType.ISSUE_ADD_DEPENDENCY)
            .render(new ToolHistoryRenderRequest(addCall, null))
            .isEmpty());
    assertTrue(
        ProjectHistoryRenderers.of(ProjectRoleToolType.ISSUE_REMOVE_DEPENDENCY)
            .render(new ToolHistoryRenderRequest(removeCall, null))
            .isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"issue_id\":\"iss-1\"}",
        "{\"status\":\"OPEN\"}",
        "{\"issue_id\":\"\",\"status\":\"OPEN\"}",
        "{\"issue_id\":\"iss-1\",\"status\":\"\"}",
        "{\"issue_id\":\"   \",\"status\":\"OPEN\"}",
        "{\"issue_id\":\"iss-1\",\"status\":\"   \"}",
        "{\"issue_id\":null,\"status\":\"OPEN\"}",
        "{\"issue_id\":\"iss-1\",\"status\":null}",
        "{\"issue_id\":123,\"status\":\"OPEN\"}",
        "{\"issue_id\":\"iss-1\",\"status\":123}"
      })
  void issueSetStatusReturnsEmptyOnMissingOrBlankOrNonTextFields(String argumentsJson) {
    // 验证 ISSUE_SET_STATUS 在任一字段缺失或无效时返回 empty
    ToolCall call = new ToolCall("call-1", "issue_set_status", argumentsJson);
    assertTrue(
        ProjectHistoryRenderers.of(ProjectRoleToolType.ISSUE_SET_STATUS)
            .render(new ToolHistoryRenderRequest(call, null))
            .isEmpty());
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
    // 验证畸形 JSON 或非对象输入时安全降级，需要参数的工具返回 empty，固定描述的工具返回中性动作，绝不上抛异常
    ToolCall mockCall = mock(ToolCall.class);
    when(mockCall.argumentsJson()).thenReturn(malformedJson);
    ToolHistoryRenderRequest request = new ToolHistoryRenderRequest(mockCall, null);

    // 8 个依赖核心参数的工具应返回 empty
    List<ProjectRoleToolType> parameterizedTypes =
        List.of(
            ProjectRoleToolType.ISSUE_READ,
            ProjectRoleToolType.ISSUE_CREATE,
            ProjectRoleToolType.ISSUE_UPDATE,
            ProjectRoleToolType.ISSUE_ADD_DEPENDENCY,
            ProjectRoleToolType.ISSUE_REMOVE_DEPENDENCY,
            ProjectRoleToolType.ISSUE_SET_STATUS,
            ProjectRoleToolType.ISSUE_CANCEL,
            ProjectRoleToolType.ISSUE_REVIEW);

    for (ProjectRoleToolType type : parameterizedTypes) {
      Optional<String> rendered = ProjectHistoryRenderers.of(type).render(request);
      assertTrue(
          rendered.isEmpty(),
          () -> "Expected empty for " + type + " on malformed input: " + malformedJson);
    }

    // 4 个不需要参数或带默认值的工具返回安全文本且不抛出
    assertEquals(
        "read the project board",
        ProjectHistoryRenderers.of(ProjectRoleToolType.PROJECT_READ).render(request).orElseThrow());
    assertEquals(
        "list issues",
        ProjectHistoryRenderers.of(ProjectRoleToolType.ISSUE_LIST).render(request).orElseThrow());
    assertEquals(
        "submit the completed work for review",
        ProjectHistoryRenderers.of(ProjectRoleToolType.ISSUE_SUBMIT).render(request).orElseThrow());
    assertEquals(
        "ask for external input",
        ProjectHistoryRenderers.of(ProjectRoleToolType.ISSUE_REQUEST_INPUT)
            .render(request)
            .orElseThrow());
  }

  @Test
  void noCursorsOrLongBodiesOrExecutionControlsLeakedInAnyOfTheTwelveTools() {
    // 验证任何动作输出绝不包含 expected_version、observed_spec_revision、observed_input_sequence、
    // description、summary、question、context、verification、assignee_agent_name、reviewer_agent_name
    // 等并发游标/长正文/执行控制值。
    for (ProjectRoleToolType type : ProjectRoleToolType.values()) {
      String primaryArgs =
          switch (type) {
            case PROJECT_READ, ISSUE_SUBMIT, ISSUE_REQUEST_INPUT -> "";
            case ISSUE_READ, ISSUE_UPDATE, ISSUE_CANCEL -> "\"issue_id\": \"iss-target\"";
            case ISSUE_LIST -> "\"status\": \"TODO\", \"include_archived\": true";
            case ISSUE_CREATE -> "\"title\": \"New feature title\"";
            case ISSUE_ADD_DEPENDENCY,
                ISSUE_REMOVE_DEPENDENCY -> "\"issue_id\": \"iss-child\", \"depends_on_issue_id\": \"iss-parent\"";
            case ISSUE_SET_STATUS -> "\"issue_id\": \"iss-target\", \"status\": \"DONE\"";
            case ISSUE_REVIEW -> "\"decision\": \"REJECT\"";
          };

      String json =
          primaryArgs.isEmpty()
              ? "{\n" + NOISY_CURSOR_FIELDS + "\n}"
              : "{\n" + primaryArgs + ",\n" + NOISY_CURSOR_FIELDS + "\n}";
      ToolCall call = new ToolCall("call-" + type.name(), type.modelName(), json);
      Optional<String> actionOpt =
          ProjectHistoryRenderers.of(type).render(new ToolHistoryRenderRequest(call, null));

      assertTrue(
          actionOpt.isPresent(),
          () -> "Expected successful rendering for " + type + " with noisy payload");
      String action = actionOpt.get();

      // 验证动作文本绝不泄漏任何游标/长正文/控制值
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
              "issue_id": "iss-001",
              "depends_on_issue_id": "iss-002",
              "title": "Title A",
              "status": "OPEN",
              "decision": "APPROVE"
            }
            """);
    ToolHistoryRenderRequest request = new ToolHistoryRenderRequest(call, "test-env");

    assertEquals(directRenderer.render(request), toolRenderer.render(request));
  }
}
