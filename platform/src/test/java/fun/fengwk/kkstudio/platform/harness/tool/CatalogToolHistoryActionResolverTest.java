package fun.fengwk.kkstudio.platform.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.ContributionId;
import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolHistoryRenderer;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link CatalogToolHistoryActionResolver} 单元测试。
 *
 * <p>测试意图：
 *
 * <ul>
 *   <li>验证构造器与 resolve 方法的入参非空契约（NPE 防护）；
 *   <li>验证贡献身份与完整冻结定义匹配且 renderer 存在时正确返回其渲染文本；
 *   <li>验证 renderer 返回 empty 时 resolver 原样返回 empty；
 *   <li>验证贡献身份不匹配（contributorId 不同或 localName 不同）时返回 empty 且绝不调用 renderer；
 *   <li>验证工具不在目录中时返回 empty；
 *   <li>验证 findTool 抛出 RuntimeException 时安全捕获并返回 empty（不上抛异常）；
 *   <li>验证目录返回的贡献未提供 renderer（Tool.historyRenderer() 返回 empty）时返回 empty；
 *   <li>验证传递给 ToolHistoryRenderRequest 的 call 与 binding 冻结的 environmentName（含 null 与非 null）完全保真。
 * </ul>
 */
class CatalogToolHistoryActionResolverTest {

  private static final String CONTRIBUTOR_ID = "builtin.environment";
  private static final String LOCAL_NAME = "read";
  private static final String TOOL_NAME = "read";
  private static final EnvironmentId ENV_ID = new EnvironmentId(UUID.randomUUID());

  @Test
  void constructorRequiresNonNullStaticToolCatalog() {
    // 验证构造器非空约束
    assertThrows(NullPointerException.class, () -> new CatalogToolHistoryActionResolver(null));
  }

  @Test
  void resolveRequiresNonNullBindingAndCall() {
    // 验证 resolve 方法参数非空约束
    HarnessToolCatalogAdapter toolCatalog = mock(HarnessToolCatalogAdapter.class);
    CatalogToolHistoryActionResolver resolver = new CatalogToolHistoryActionResolver(toolCatalog);

    ToolDescriptor descriptor = testDescriptor(TOOL_NAME);
    ToolBinding binding = createBinding(CONTRIBUTOR_ID, LOCAL_NAME, descriptor, false, null, null);
    ToolCall call = new ToolCall("call-1", TOOL_NAME, "{}");

    assertThrows(NullPointerException.class, () -> resolver.resolve(null, call));
    assertThrows(NullPointerException.class, () -> resolver.resolve(binding, null));
  }

  @Test
  void matchingContributionWithRendererReturnsRenderedText() {
    // 验证贡献身份完全匹配且 renderer 返回有效动作时，resolver 原样返回
    HarnessToolCatalogAdapter toolCatalog = mock(HarnessToolCatalogAdapter.class);
    CatalogToolHistoryActionResolver resolver = new CatalogToolHistoryActionResolver(toolCatalog);

    ToolDescriptor descriptor = testDescriptor(TOOL_NAME);
    ToolRequirements requirements = ToolRequirements.none();
    ToolHistoryRenderer renderer = mock(ToolHistoryRenderer.class);
    when(renderer.render(any())).thenReturn(Optional.of("read the project board"));

    Tool tool = mock(Tool.class);
    when(tool.descriptor()).thenReturn(descriptor);
    when(tool.requirements()).thenReturn(requirements);
    when(tool.historyRenderer()).thenReturn(Optional.of(renderer));

    ToolContribution contribution =
        createContribution(CONTRIBUTOR_ID, LOCAL_NAME, tool, descriptor, requirements);
    when(toolCatalog.findTool(any(ContributionId.class))).thenReturn(Optional.of(contribution));

    ToolBinding binding = createBinding(CONTRIBUTOR_ID, LOCAL_NAME, descriptor, false, null, null);
    ToolCall call = new ToolCall("call-1", TOOL_NAME, "{}");

    Optional<String> action = resolver.resolve(binding, call);

    assertTrue(action.isPresent());
    assertEquals("read the project board", action.get());
  }

  @Test
  void matchingContributionWhenRendererReturnsEmptyReturnsEmpty() {
    // 验证贡献身份匹配但 renderer 返回 empty 时，resolver 返回 empty
    HarnessToolCatalogAdapter toolCatalog = mock(HarnessToolCatalogAdapter.class);
    CatalogToolHistoryActionResolver resolver = new CatalogToolHistoryActionResolver(toolCatalog);

    ToolDescriptor descriptor = testDescriptor(TOOL_NAME);
    ToolRequirements requirements = ToolRequirements.none();
    ToolHistoryRenderer renderer = mock(ToolHistoryRenderer.class);
    when(renderer.render(any())).thenReturn(Optional.empty());

    Tool tool = mock(Tool.class);
    when(tool.descriptor()).thenReturn(descriptor);
    when(tool.requirements()).thenReturn(requirements);
    when(tool.historyRenderer()).thenReturn(Optional.of(renderer));

    ToolContribution contribution =
        createContribution(CONTRIBUTOR_ID, LOCAL_NAME, tool, descriptor, requirements);
    when(toolCatalog.findTool(any(ContributionId.class))).thenReturn(Optional.of(contribution));

    ToolBinding binding = createBinding(CONTRIBUTOR_ID, LOCAL_NAME, descriptor, false, null, null);
    ToolCall call = new ToolCall("call-1", TOOL_NAME, "{}");

    Optional<String> action = resolver.resolve(binding, call);

    assertTrue(action.isEmpty());
  }

  /** Contributor renderer 违反纯函数契约抛异常时，Platform 边界安全回退。 */
  @Test
  void rendererFailureReturnsEmpty() {
    HarnessToolCatalogAdapter toolCatalog = mock(HarnessToolCatalogAdapter.class);
    CatalogToolHistoryActionResolver resolver = new CatalogToolHistoryActionResolver(toolCatalog);

    ToolDescriptor descriptor = testDescriptor(TOOL_NAME);
    ToolRequirements requirements = ToolRequirements.none();
    ToolHistoryRenderer renderer =
        request -> {
          throw new IllegalStateException("renderer failed");
        };
    Tool tool = mock(Tool.class);
    when(tool.descriptor()).thenReturn(descriptor);
    when(tool.requirements()).thenReturn(requirements);
    when(tool.historyRenderer()).thenReturn(Optional.of(renderer));
    ToolContribution contribution =
        createContribution(CONTRIBUTOR_ID, LOCAL_NAME, tool, descriptor, requirements);
    when(toolCatalog.findTool(any(ContributionId.class))).thenReturn(Optional.of(contribution));

    ToolBinding binding = createBinding(CONTRIBUTOR_ID, LOCAL_NAME, descriptor, false, null, null);
    Optional<String> action = resolver.resolve(binding, new ToolCall("call-1", TOOL_NAME, "{}"));

    assertTrue(action.isEmpty());
  }

  @Test
  void mismatchedContributorIdReturnsEmptyAndNeverCallsRenderer() {
    // 验证 contributorId 不一致时，返回 empty 且绝不触发 renderer 调用
    HarnessToolCatalogAdapter toolCatalog = mock(HarnessToolCatalogAdapter.class);
    CatalogToolHistoryActionResolver resolver = new CatalogToolHistoryActionResolver(toolCatalog);

    ToolDescriptor descriptor = testDescriptor(TOOL_NAME);
    ToolRequirements requirements = ToolRequirements.none();
    ToolHistoryRenderer renderer = mock(ToolHistoryRenderer.class);

    Tool tool = mock(Tool.class);
    when(tool.descriptor()).thenReturn(descriptor);
    when(tool.requirements()).thenReturn(requirements);
    when(tool.historyRenderer()).thenReturn(Optional.of(renderer));

    // Catalog 中来自 custom.contributor
    ToolContribution contribution =
        createContribution("custom.contributor", LOCAL_NAME, tool, descriptor, requirements);
    when(toolCatalog.findTool(any(ContributionId.class))).thenReturn(Optional.of(contribution));

    // Binding 中冻结的是 builtin.environment
    ToolBinding binding = createBinding(CONTRIBUTOR_ID, LOCAL_NAME, descriptor, false, null, null);
    ToolCall call = new ToolCall("call-1", TOOL_NAME, "{}");

    Optional<String> action = resolver.resolve(binding, call);

    assertTrue(action.isEmpty());
    verify(renderer, never()).render(any());
  }

  @Test
  void mismatchedLocalNameReturnsEmptyAndNeverCallsRenderer() {
    // 验证 localName 不一致时，返回 empty 且绝不触发 renderer 调用
    HarnessToolCatalogAdapter toolCatalog = mock(HarnessToolCatalogAdapter.class);
    CatalogToolHistoryActionResolver resolver = new CatalogToolHistoryActionResolver(toolCatalog);

    ToolDescriptor descriptor = testDescriptor(TOOL_NAME);
    ToolRequirements requirements = ToolRequirements.none();
    ToolHistoryRenderer renderer = mock(ToolHistoryRenderer.class);

    Tool tool = mock(Tool.class);
    when(tool.descriptor()).thenReturn(descriptor);
    when(tool.requirements()).thenReturn(requirements);
    when(tool.historyRenderer()).thenReturn(Optional.of(renderer));

    // Catalog 中 localName 为 read.v2
    ToolContribution contribution =
        createContribution(CONTRIBUTOR_ID, "read.v2", tool, descriptor, requirements);
    when(toolCatalog.findTool(any(ContributionId.class))).thenReturn(Optional.of(contribution));

    // Binding 中 localName 为 read
    ToolBinding binding = createBinding(CONTRIBUTOR_ID, LOCAL_NAME, descriptor, false, null, null);
    ToolCall call = new ToolCall("call-1", TOOL_NAME, "{}");

    Optional<String> action = resolver.resolve(binding, call);

    assertTrue(action.isEmpty());
    verify(renderer, never()).render(any());
  }

  /** 冻结定义与目录定义不一致时不得用新渲染器重新解释旧参数。 */
  @Test
  void mismatchedDefinitionReturnsEmptyAndNeverCallsRenderer() {
    HarnessToolCatalogAdapter toolCatalog = mock(HarnessToolCatalogAdapter.class);
    CatalogToolHistoryActionResolver resolver = new CatalogToolHistoryActionResolver(toolCatalog);

    ToolDescriptor frozenDescriptor = testDescriptor(TOOL_NAME);
    ToolDescriptor currentDescriptor =
        new ToolDescriptor(
            TOOL_NAME,
            "changed description",
            TOOL_NAME,
            new InputSchema("{}", Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(30));
    ToolHistoryRenderer renderer = mock(ToolHistoryRenderer.class);
    ToolRequirements requirements = ToolRequirements.none();
    Tool tool = mock(Tool.class);
    when(tool.descriptor()).thenReturn(currentDescriptor);
    when(tool.requirements()).thenReturn(requirements);
    when(tool.historyRenderer()).thenReturn(Optional.of(renderer));
    ToolContribution contribution =
        createContribution(CONTRIBUTOR_ID, LOCAL_NAME, tool, currentDescriptor, requirements);
    when(toolCatalog.findTool(any(ContributionId.class))).thenReturn(Optional.of(contribution));

    ToolBinding binding =
        createBinding(CONTRIBUTOR_ID, LOCAL_NAME, frozenDescriptor, false, null, null);
    Optional<String> action = resolver.resolve(binding, new ToolCall("call-1", TOOL_NAME, "{}"));

    assertTrue(action.isEmpty());
    verify(renderer, never()).render(any());
  }

  @Test
  void toolNotFoundInCatalogReturnsEmpty() {
    // 验证工具在 catalog 中不存在时返回 empty
    HarnessToolCatalogAdapter toolCatalog = mock(HarnessToolCatalogAdapter.class);
    CatalogToolHistoryActionResolver resolver = new CatalogToolHistoryActionResolver(toolCatalog);

    when(toolCatalog.findTool(any(ContributionId.class))).thenReturn(Optional.empty());

    ToolDescriptor descriptor = testDescriptor(TOOL_NAME);
    ToolBinding binding = createBinding(CONTRIBUTOR_ID, LOCAL_NAME, descriptor, false, null, null);
    ToolCall call = new ToolCall("call-1", TOOL_NAME, "{}");

    Optional<String> action = resolver.resolve(binding, call);

    assertTrue(action.isEmpty());
  }

  @Test
  void findToolThrowingRuntimeExceptionReturnsEmptyWithoutRethrowing() {
    // 验证 findTool 抛出 RuntimeException 时安全降级返回 empty，绝不上抛
    HarnessToolCatalogAdapter toolCatalog = mock(HarnessToolCatalogAdapter.class);
    CatalogToolHistoryActionResolver resolver = new CatalogToolHistoryActionResolver(toolCatalog);

    when(toolCatalog.findTool(any(ContributionId.class)))
        .thenThrow(new RuntimeException("Catalog lookup failed"));

    ToolDescriptor descriptor = testDescriptor(TOOL_NAME);
    ToolBinding binding = createBinding(CONTRIBUTOR_ID, LOCAL_NAME, descriptor, false, null, null);
    ToolCall call = new ToolCall("call-1", TOOL_NAME, "{}");

    Optional<String> action = resolver.resolve(binding, call);

    assertTrue(action.isEmpty());
  }

  @Test
  void toolWithoutHistoryRendererReturnsEmpty() {
    // 验证贡献存在且身份匹配，但 Tool 未提供 historyRenderer（返回 empty）时，resolver 返回 empty
    HarnessToolCatalogAdapter toolCatalog = mock(HarnessToolCatalogAdapter.class);
    CatalogToolHistoryActionResolver resolver = new CatalogToolHistoryActionResolver(toolCatalog);

    ToolDescriptor descriptor = testDescriptor(TOOL_NAME);
    ToolRequirements requirements = ToolRequirements.none();

    Tool tool = mock(Tool.class);
    when(tool.descriptor()).thenReturn(descriptor);
    when(tool.requirements()).thenReturn(requirements);
    when(tool.historyRenderer()).thenReturn(Optional.empty());

    ToolContribution contribution =
        createContribution(CONTRIBUTOR_ID, LOCAL_NAME, tool, descriptor, requirements);
    when(toolCatalog.findTool(any(ContributionId.class))).thenReturn(Optional.of(contribution));

    ToolBinding binding = createBinding(CONTRIBUTOR_ID, LOCAL_NAME, descriptor, false, null, null);
    ToolCall call = new ToolCall("call-1", TOOL_NAME, "{}");

    Optional<String> action = resolver.resolve(binding, call);

    assertTrue(action.isEmpty());
  }

  @Test
  void renderRequestReceivesExactCallAndFrozenEnvironmentName() {
    // 验证传递给 ToolHistoryRenderRequest 的 call 与 binding 冻结的 environmentName 完全保真
    HarnessToolCatalogAdapter toolCatalog = mock(HarnessToolCatalogAdapter.class);
    CatalogToolHistoryActionResolver resolver = new CatalogToolHistoryActionResolver(toolCatalog);

    ToolDescriptor descriptor = testDescriptor(TOOL_NAME);
    ToolRequirements requirements = ToolRequirements.none();
    ToolHistoryRenderer renderer = mock(ToolHistoryRenderer.class);
    when(renderer.render(any())).thenReturn(Optional.of("read file test.txt"));

    Tool tool = mock(Tool.class);
    when(tool.descriptor()).thenReturn(descriptor);
    when(tool.requirements()).thenReturn(requirements);
    when(tool.historyRenderer()).thenReturn(Optional.of(renderer));

    ToolContribution contribution =
        createContribution(CONTRIBUTOR_ID, LOCAL_NAME, tool, descriptor, requirements);
    when(toolCatalog.findTool(any(ContributionId.class))).thenReturn(Optional.of(contribution));

    String envName = "production-cluster";
    ToolBinding binding =
        createBinding(CONTRIBUTOR_ID, LOCAL_NAME, descriptor, true, ENV_ID, envName);
    ToolCall call = new ToolCall("call-1", TOOL_NAME, "{\"path\":\"/workspace/test.txt\"}");

    Optional<String> action = resolver.resolve(binding, call);

    assertTrue(action.isPresent());
    assertEquals("read file test.txt", action.get());

    ArgumentCaptor<ToolHistoryRenderRequest> captor =
        ArgumentCaptor.forClass(ToolHistoryRenderRequest.class);
    verify(renderer).render(captor.capture());

    ToolHistoryRenderRequest request = captor.getValue();
    assertEquals(call, request.call());
    assertEquals(envName, request.environmentName());
  }

  @Test
  void renderRequestReceivesNullEnvironmentNameWhenBindingHasNoEnvironment() {
    // 验证当 binding 未绑定环境时，传递给 ToolHistoryRenderRequest 的 environmentName 为 null
    HarnessToolCatalogAdapter toolCatalog = mock(HarnessToolCatalogAdapter.class);
    CatalogToolHistoryActionResolver resolver = new CatalogToolHistoryActionResolver(toolCatalog);

    ToolDescriptor descriptor = testDescriptor(TOOL_NAME);
    ToolRequirements requirements = ToolRequirements.none();
    ToolHistoryRenderer renderer = mock(ToolHistoryRenderer.class);
    when(renderer.render(any())).thenReturn(Optional.of("read file test.txt"));

    Tool tool = mock(Tool.class);
    when(tool.descriptor()).thenReturn(descriptor);
    when(tool.requirements()).thenReturn(requirements);
    when(tool.historyRenderer()).thenReturn(Optional.of(renderer));

    ToolContribution contribution =
        createContribution(CONTRIBUTOR_ID, LOCAL_NAME, tool, descriptor, requirements);
    when(toolCatalog.findTool(any(ContributionId.class))).thenReturn(Optional.of(contribution));

    ToolBinding binding = createBinding(CONTRIBUTOR_ID, LOCAL_NAME, descriptor, false, null, null);
    ToolCall call = new ToolCall("call-1", TOOL_NAME, "{}");

    Optional<String> action = resolver.resolve(binding, call);

    assertTrue(action.isPresent());

    ArgumentCaptor<ToolHistoryRenderRequest> captor =
        ArgumentCaptor.forClass(ToolHistoryRenderRequest.class);
    verify(renderer).render(captor.capture());

    ToolHistoryRenderRequest request = captor.getValue();
    assertEquals(call, request.call());
    assertNull(request.environmentName());
  }

  private ToolDescriptor testDescriptor(String name) {
    return new ToolDescriptor(
        name,
        "description",
        name,
        new InputSchema("{}", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(30));
  }

  private ToolContribution createContribution(
      String contributorId,
      String localName,
      Tool tool,
      ToolDescriptor descriptor,
      ToolRequirements requirements) {
    ContributionId id = new ContributionId(new ContributorId(contributorId), localName);
    AgentToolDefinition definition = new AgentToolDefinition(descriptor, ToolVisibility.SELECTABLE);
    return new ToolContribution(id, definition, tool, requirements, 0);
  }

  private ToolBinding createBinding(
      String contributorId,
      String localName,
      ToolDescriptor descriptor,
      boolean environmentRequired,
      EnvironmentId environmentId,
      String environmentName) {
    AgentToolDefinition definition = new AgentToolDefinition(descriptor, ToolVisibility.SELECTABLE);
    ContributorBinding contributor = new ContributorBinding(contributorId, localName, List.of());
    return new ToolBinding(
        definition, contributor, environmentRequired, environmentId, environmentName);
  }
}
