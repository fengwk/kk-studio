package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.spi.ToolGateway;
import fun.fengwk.kkstudio.harness.spi.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.platform.harness.tool.permission.PermissionAction;
import fun.fengwk.kkstudio.platform.harness.tool.permission.PermissionEvaluator;

import java.util.List;

/**
 * 只读命名空间写入的准入门禁。
 *
 * <p>验证 {@code vfs:/skills} 与 {@code vfs:/resources} 下的 {@code write}/{@code edit} 在权限评估与审批之前、以及
 * YOLO 直接进入 admit 的路径上都被确定性拒绝，且声明未执行。
 */
class ToolExecutionGatewayVfsReadOnlyTest {

  private static final String ROOT_SESSION = "11111111-2222-3333-4444-555555555555";
  private static final String SKILL_PATH = "vfs:/skills/dev-tools/review/SKILL.md";
  private static final String RESOURCE_PATH = "vfs:/resources/0f8fad5b-d9cb-469f-a165-70867728950e";

  @Test
  void writeToSkillsIsDeniedBeforePermissionEvaluation() {
    PermissionEvaluator evaluator = mock(PermissionEvaluator.class);
    ToolGatewayTestSupport.CountingToolSettingsProvider settingsProvider =
        new ToolGatewayTestSupport.CountingToolSettingsProvider(
            ToolGatewayTestSupport.settings(PermissionAction.ASK));
    HarnessCatalog catalog = ToolGatewayTestSupport.defaultCatalog();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gatewayWith(catalog, evaluator, settingsProvider);

    ToolGateway.PreflightResult result =
        preflight(
            gateway,
            request(catalog, "write", "{\"path\":\"" + SKILL_PATH + "\",\"content\":\"x\"}"));

    ToolGateway.Deny deny = assertInstanceOf(ToolGateway.Deny.class, result);
    assertEquals(ToolErrorClassifier.VFS_READ_ONLY_KIND, deny.error().kind());
    assertTrue(
        deny.error().message().contains("Cannot write " + SKILL_PATH), deny.error().message());
    assertTrue(deny.error().message().contains("vfs:/skills is read-only"), deny.error().message());
    assertTrue(
        deny.error().message().contains("The tool was not executed."), deny.error().message());
    // preflight 与 YOLO 准入使用同一份执行根事实，直接给出真实可写根而非另一套泛化文案。
    assertTrue(
        deny.error()
            .message()
            .contains(
                "Write under vfs:/sessions/"
                    + ToolGatewayTestSupport.scope(ToolGatewayTestSupport.EMPTY_BRANCH_VIEW)
                        .rootSessionId()
                    + "/ instead."),
        deny.error().message());
    // 只读拒绝先于权限：既不读权限策略，也不调用 evaluator，更不会弹审批。
    verifyNoInteractions(evaluator);
    assertEquals(0, settingsProvider.readCount());
  }

  @Test
  void editToResourcesIsDeniedBeforePermissionEvaluation() {
    PermissionEvaluator evaluator = mock(PermissionEvaluator.class);
    HarnessCatalog catalog = ToolGatewayTestSupport.defaultCatalog();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gatewayWith(
            catalog,
            evaluator,
            new ToolGatewayTestSupport.FixedToolSettingsProvider(
                ToolGatewayTestSupport.settings(PermissionAction.ALLOW)));

    ToolGateway.Deny deny =
        assertInstanceOf(
            ToolGateway.Deny.class,
            preflight(
                gateway,
                request(
                    catalog,
                    "edit",
                    "{\"path\":\""
                        + RESOURCE_PATH
                        + "\",\"old_string\":\"a\",\"new_string\":\"b\"}")));
    assertEquals(ToolErrorClassifier.VFS_READ_ONLY_KIND, deny.error().kind());
    assertTrue(
        deny.error().message().contains("vfs:/resources is read-only"), deny.error().message());
    verifyNoInteractions(evaluator);
  }

  /** YOLO 跳过 preflight 直接从 admit 进入执行：admit 同样在装配执行之前拒绝。 */
  @Test
  void admitRejectsReadOnlyWritesOnTheYoloPath() {
    PermissionEvaluator evaluator = mock(PermissionEvaluator.class);
    HarnessCatalog catalog = ToolGatewayTestSupport.defaultCatalog();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gatewayWith(
            catalog,
            evaluator,
            new ToolGatewayTestSupport.FixedToolSettingsProvider(
                ToolGatewayTestSupport.settings(PermissionAction.ALLOW)));

    ToolGateway.Admission admission =
        gateway.admit(
            ToolGatewayTestSupport.execution(
                request(catalog, "write", "{\"path\":\"" + SKILL_PATH + "\",\"content\":\"x\"}")));

    ToolGateway.Rejected rejected = assertInstanceOf(ToolGateway.Rejected.class, admission);
    assertEquals(ToolErrorClassifier.VFS_READ_ONLY_KIND, rejected.error().kind());
    assertTrue(
        rejected.error().message().contains("Write under vfs:/sessions/"),
        rejected.error().message());
    assertTrue(rejected.error().message().endsWith(" instead."), rejected.error().message());
    verifyNoInteractions(evaluator);
  }

  /** 会话目录内的写入不是只读拒绝：请求继续按普通权限流程处理。 */
  @Test
  void sessionWritesAreNotRejectedByTheReadOnlyGuard() {
    HarnessCatalog catalog = ToolGatewayTestSupport.defaultCatalog();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            catalog,
            new ToolGatewayTestSupport.FakeTransport(),
            new ToolGatewayTestSupport.FakeResourceStager());
    // 目录租用与定义校验通过后由权限策略判定；这里只证明不是 VFS_READ_ONLY 拒绝。
    ToolGateway.PreflightResult result =
        preflight(
            gateway,
            request(
                catalog,
                "write",
                "{\"path\":\"vfs:/sessions/" + ROOT_SESSION + "/notes.txt\",\"content\":\"x\"}"));
    assertInstanceOf(ToolGateway.Allow.class, result);
  }

  private static ToolInvocationRequest request(
      HarnessCatalog catalog, String toolName, String argumentsJson) {
    ToolContribution contribution = catalog.findTool(toolName).orElseThrow();
    ToolBinding binding = binding(contribution);
    return new ToolInvocationRequest(
        new ToolCall("call-" + toolName, toolName, argumentsJson), binding);
  }

  private static ToolBinding binding(ToolContribution contribution) {
    return new ToolBinding(
        new AgentToolDefinition(
            contribution.definition().descriptor(), contribution.definition().visibility()),
        new ContributorBinding(
            contribution.id().contributorId().value(), contribution.id().localName(), List.of()),
        EnvironmentSupport.ROUTED,
        null,
        null);
  }

  /** preflight 需要冻结的执行根事实；本测试固定使用空分支视图。 */
  private static ToolGateway.PreflightResult preflight(
      ToolExecutionGateway gateway, ToolInvocationRequest request) {
    return gateway.preflight(
        request, ToolGatewayTestSupport.scope(ToolGatewayTestSupport.EMPTY_BRANCH_VIEW));
  }
}
