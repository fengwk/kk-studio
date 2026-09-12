package fun.fengwk.kkstudio.harness.runtime.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 覆盖：构造期绑定 sessionId 与 provider 连接代际；policy 由调用方显式传入（null 拒绝），
 * disabled/UNKNOWN/UNSUPPORTED/AUTOMATIC/NONE 全部归一为 none()；AFFINITY 即使无 system/tools 也派生 affinity
 * key；BREAKPOINTS 求 capability 支持 breakpoints 与请求实际内容交集；最终 finalizer 始终覆盖伪造 control；返回
 * ProviderRequest 其它字段内容不变。
 */
class PromptCacheRequestFinalizerTest {

  private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000063");
  private static final UUID PROVIDER_CONNECTION_GENERATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000163");

  @Test
  void bindsSessionIdAndRejectsInvalid() {
    assertThrows(
        NullPointerException.class,
        () -> new PromptCacheRequestFinalizer(null, PROVIDER_CONNECTION_GENERATION_ID));
    assertThrows(
        NullPointerException.class, () -> new PromptCacheRequestFinalizer(SESSION_ID, null));
    // 验证构造期 sessionId 绑定：两个不同 sessionId 的 finalizer 必须在同一 request 上产生不同 affinity key。
    ProviderRequest request = requestWith(ProviderCacheControl.none(), List.of(), List.of());
    PromptCacheRequestFinalizer a =
        finalizer(UUID.fromString("00000000-0000-0000-0000-000000000065"));
    PromptCacheRequestFinalizer b =
        finalizer(UUID.fromString("00000000-0000-0000-0000-0000000000ca"));
    assertNotEquals(
        a.apply(request, affinityPolicy()).cacheControl().affinityKey(),
        b.apply(request, affinityPolicy()).cacheControl().affinityKey());
  }

  @Test
  void rejectsNullPolicy() {
    ProviderRequest request = requestWith(ProviderCacheControl.none(), List.of(), List.of());
    assertThrows(NullPointerException.class, () -> finalizer(SESSION_ID).apply(request, null));
  }

  @Test
  void overridesForgedControlForDisabledCapability() {
    ProviderRequest forged =
        requestWith(
            ProviderCacheControl.breakpoints(
                PromptCacheRetention.SHORT, "pc1-forged", EnumSet.of(PromptCacheBreakpoint.SYSTEM)),
            List.of(systemText("S1")),
            List.of());
    ProviderCacheControl resolved =
        finalizer(SESSION_ID).apply(forged, disabledPolicy()).cacheControl();
    assertEquals(PromptCacheRetention.NONE, resolved.retention());
    assertTrue(resolved.breakpoints().isEmpty());
    assertNull(resolved.affinityKey());
  }

  @Test
  void returnsNoneForUnsupportedCapability() {
    // PromptCachePolicy 拒绝 UNKNOWN/UNSUPPORTED + 非 NONE retention 的组合，所以直接检查 disabled
    // 这条路径（unsupported capability + NONE retention）：finalizer 必须输出 none()。
    ProviderRequest forged =
        requestWith(
            ProviderCacheControl.affinity(PromptCacheRetention.LONG, "pc1-forged-key"),
            List.of(systemText("S1")),
            List.of());
    ProviderCacheControl resolved =
        finalizer(SESSION_ID).apply(forged, disabledPolicy()).cacheControl();
    assertEquals(PromptCacheRetention.NONE, resolved.retention());
  }

  @Test
  void returnsNoneForAutomaticCapability() {
    // AUTOMATIC capability 只允许 NONE retention；构造合法 policy 并验证 finalizer 输出 none()。
    ProviderRequest forged =
        requestWith(
            ProviderCacheControl.affinity(PromptCacheRetention.LONG, "pc1-forged"),
            List.of(systemText("S1")),
            List.of());
    ProviderCacheControl resolved =
        finalizer(SESSION_ID).apply(forged, automaticPolicy()).cacheControl();
    assertEquals(PromptCacheRetention.NONE, resolved.retention());
  }

  @Test
  void retentionNoneAlwaysYieldsNone() {
    ProviderRequest forged =
        requestWith(
            ProviderCacheControl.affinity(PromptCacheRetention.LONG, "pc1-forged"),
            List.of(systemText("S1")),
            List.of());
    ProviderCacheControl resolved =
        finalizer(SESSION_ID).apply(forged, disabledPolicy()).cacheControl();
    assertEquals(PromptCacheRetention.NONE, resolved.retention());
  }

  @Test
  void affinityAlwaysGeneratesKeyEvenWithoutSystemOrTools() {
    ProviderRequest empty = requestWith(ProviderCacheControl.none(), List.of(), List.of());
    ProviderCacheControl resolved =
        finalizer(SESSION_ID).apply(empty, affinityPolicy()).cacheControl();
    assertEquals(PromptCacheRetention.SHORT, resolved.retention());
    assertTrue(resolved.affinityKey().startsWith("pc2-"));
  }

  @Test
  void affinityDifferentSessionYieldsDifferentKey() {
    PromptCacheRequestFinalizer a =
        finalizer(UUID.fromString("00000000-0000-0000-0000-000000000065"));
    PromptCacheRequestFinalizer b =
        finalizer(UUID.fromString("00000000-0000-0000-0000-0000000000ca"));
    ProviderRequest request =
        requestWith(ProviderCacheControl.none(), List.of(systemText("S1")), List.of(tool("alpha")));
    String keyA = a.apply(request, affinityPolicy()).cacheControl().affinityKey();
    String keyB = b.apply(request, affinityPolicy()).cacheControl().affinityKey();
    assertNotEquals(keyA, keyB);
  }

  @Test
  void affinityDifferentProviderConnectionGenerationYieldsDifferentKey() {
    PromptCacheRequestFinalizer a =
        new PromptCacheRequestFinalizer(
            SESSION_ID, UUID.fromString("00000000-0000-0000-0000-000000000001"));
    PromptCacheRequestFinalizer b =
        new PromptCacheRequestFinalizer(
            SESSION_ID, UUID.fromString("00000000-0000-0000-0000-000000000002"));
    ProviderRequest request =
        requestWith(ProviderCacheControl.none(), List.of(systemText("S1")), List.of(tool("alpha")));
    String keyA = a.apply(request, affinityPolicy()).cacheControl().affinityKey();
    String keyB = b.apply(request, affinityPolicy()).cacheControl().affinityKey();
    assertNotEquals(keyA, keyB);
  }

  /** 同一 request 下 policy 由调用方显式决定：不同 policy 必须产出不同 control，模型本身不再携带 policy。 */
  @Test
  void explicitPolicyDrivesResolution() {
    ProviderRequest request =
        requestWith(ProviderCacheControl.none(), List.of(systemText("S1")), List.of(tool("alpha")));
    PromptCacheRequestFinalizer finalizer = finalizer(SESSION_ID);
    ProviderCacheControl disabled = finalizer.apply(request, disabledPolicy()).cacheControl();
    ProviderCacheControl affinity = finalizer.apply(request, affinityPolicy()).cacheControl();
    ProviderCacheControl breakpoints =
        finalizer
            .apply(
                request,
                breakpointsPolicy(
                    EnumSet.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS)))
            .cacheControl();
    assertEquals(PromptCacheRetention.NONE, disabled.retention());
    assertEquals(PromptCacheRetention.SHORT, affinity.retention());
    assertEquals(
        EnumSet.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS),
        breakpoints.breakpoints());
  }

  @Test
  void breakpointsIntersectCapabilityWithRequestContents() {
    // Capability 同时支持 SYSTEM 与 TOOLS；请求只有 system => 只有 SYSTEM 进入 breakpoints。
    ProviderRequest onlySystem =
        requestWith(ProviderCacheControl.none(), List.of(systemText("S1")), List.of());
    ProviderCacheControl onlySystemResolved =
        finalizer(SESSION_ID)
            .apply(
                onlySystem,
                breakpointsPolicy(
                    EnumSet.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS)))
            .cacheControl();
    assertEquals(PromptCacheRetention.SHORT, onlySystemResolved.retention());
    assertEquals(EnumSet.of(PromptCacheBreakpoint.SYSTEM), onlySystemResolved.breakpoints());

    // 请求只有 tool => 只有 TOOLS。
    ProviderRequest onlyTools =
        requestWith(ProviderCacheControl.none(), List.of(), List.of(tool("alpha")));
    ProviderCacheControl onlyToolsResolved =
        finalizer(SESSION_ID)
            .apply(
                onlyTools,
                breakpointsPolicy(
                    EnumSet.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS)))
            .cacheControl();
    assertEquals(EnumSet.of(PromptCacheBreakpoint.TOOLS), onlyToolsResolved.breakpoints());

    // 请求同时存在 system + tools => 两个都进入 breakpoints。
    ProviderRequest both =
        requestWith(ProviderCacheControl.none(), List.of(systemText("S1")), List.of(tool("alpha")));
    ProviderCacheControl bothResolved =
        finalizer(SESSION_ID)
            .apply(
                both,
                breakpointsPolicy(
                    EnumSet.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS)))
            .cacheControl();
    assertEquals(
        EnumSet.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS),
        bothResolved.breakpoints());

    // 请求既无 system 也无 tools => 无有效 breakpoint，降级 none()。
    ProviderRequest empty = requestWith(ProviderCacheControl.none(), List.of(), List.of());
    ProviderCacheControl emptyResolved =
        finalizer(SESSION_ID)
            .apply(
                empty,
                breakpointsPolicy(
                    EnumSet.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS)))
            .cacheControl();
    assertEquals(PromptCacheRetention.NONE, emptyResolved.retention());
  }

  @Test
  void breakpointsFilterByCapabilityWhenCapabilityLimitedToSystemOnly() {
    // Capability 只声明 SYSTEM 支持时，TOOLS 即使实际有 tools 也应被剔除。
    ProviderRequest request =
        requestWith(ProviderCacheControl.none(), List.of(systemText("S1")), List.of(tool("alpha")));
    ProviderCacheControl resolved =
        finalizer(SESSION_ID)
            .apply(request, breakpointsPolicy(EnumSet.of(PromptCacheBreakpoint.SYSTEM)))
            .cacheControl();
    assertEquals(EnumSet.of(PromptCacheBreakpoint.SYSTEM), resolved.breakpoints());
  }

  @Test
  void preservedOtherProviderRequestFields() {
    ProviderRequest forged =
        requestWith(
            ProviderCacheControl.breakpoints(
                PromptCacheRetention.LONG,
                "pc1-forged",
                EnumSet.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS)),
            List.of(systemText("S1")),
            List.of(tool("alpha")));
    ProviderRequest after = finalizer(SESSION_ID).apply(forged, affinityPolicy());
    assertSame(forged.model(), after.model());
    assertSame(forged.variant(), after.variant());
    assertEquals(forged.messages(), after.messages());
    assertEquals(forged.tools(), after.tools());
  }

  @Test
  void breakpointsSupportsConversationBreakpointWhenPresent() {
    ProviderMessage userMsg =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("hello")));
    ProviderRequest conversationOnly =
        requestWith(ProviderCacheControl.none(), List.of(userMsg), List.of());
    ProviderCacheControl resolved =
        finalizer(SESSION_ID)
            .apply(
                conversationOnly,
                breakpointsPolicy(
                    EnumSet.of(
                        PromptCacheBreakpoint.SYSTEM,
                        PromptCacheBreakpoint.TOOLS,
                        PromptCacheBreakpoint.CONVERSATION)))
            .cacheControl();
    assertEquals(EnumSet.of(PromptCacheBreakpoint.CONVERSATION), resolved.breakpoints());

    // 请求同时包含 system + tools + conversation
    ProviderRequest allThree =
        requestWith(
            ProviderCacheControl.none(),
            List.of(systemText("S1"), userMsg),
            List.of(tool("alpha")));
    ProviderCacheControl allResolved =
        finalizer(SESSION_ID)
            .apply(
                allThree,
                breakpointsPolicy(
                    EnumSet.of(
                        PromptCacheBreakpoint.SYSTEM,
                        PromptCacheBreakpoint.TOOLS,
                        PromptCacheBreakpoint.CONVERSATION)))
            .cacheControl();
    assertEquals(
        EnumSet.of(
            PromptCacheBreakpoint.SYSTEM,
            PromptCacheBreakpoint.TOOLS,
            PromptCacheBreakpoint.CONVERSATION),
        allResolved.breakpoints());
  }

  private static ProviderMessage systemText(String text) {
    return new ProviderMessage(ProviderMessageRole.SYSTEM, List.of(new ProviderTextBlock(text)));
  }

  private static ProviderToolDefinition tool(String name) {
    return new ProviderToolDefinition(name, "desc-" + name, "{\"type\":\"object\"}");
  }

  private static PromptCacheRequestFinalizer finalizer(UUID sessionId) {
    return new PromptCacheRequestFinalizer(sessionId, PROVIDER_CONNECTION_GENERATION_ID);
  }

  private static PromptCachePolicy disabledPolicy() {
    return PromptCachePolicy.disabled();
  }

  private static PromptCachePolicy automaticPolicy() {
    return new PromptCachePolicy(PromptCacheCapability.automatic(), PromptCacheRetention.NONE);
  }

  private static PromptCachePolicy affinityPolicy() {
    return new PromptCachePolicy(
        PromptCacheCapability.affinity(EnumSet.of(PromptCacheRetention.SHORT)),
        PromptCacheRetention.SHORT);
  }

  private static PromptCachePolicy breakpointsPolicy(
      Set<PromptCacheBreakpoint> supportedBreakpoints) {
    return new PromptCachePolicy(
        PromptCacheCapability.breakpoints(
            EnumSet.of(PromptCacheRetention.SHORT), supportedBreakpoints),
        PromptCacheRetention.SHORT);
  }

  private static ModelPricing pricing() {
    return new ModelPricing(
        "USD",
        "tier-1",
        "default",
        BigDecimal.ONE,
        "v1",
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO);
  }

  private static ProviderRequest requestWith(
      ProviderCacheControl forged,
      List<ProviderMessage> messages,
      List<ProviderToolDefinition> tools) {
    ModelDescriptor model =
        new ModelDescriptor(
            "provider", "m1", "m1", Set.of(ModelInputModality.TEXT), true, false, pricing());
    ModelVariant variant = new ModelVariant("default");
    return new ProviderRequest(model, variant, 1024, messages, tools, forged);
  }
}
