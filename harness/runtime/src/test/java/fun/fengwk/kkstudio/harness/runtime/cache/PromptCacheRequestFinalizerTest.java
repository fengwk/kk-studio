package fun.fengwk.kkstudio.harness.runtime.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 覆盖：构造期绑定 sessionId 与正整数校验；disabled/UNKNOWN/UNSUPPORTED/AUTOMATIC/NONE 全部归一为 none()； AFFINITY 即使无
 * system/tools 也派生 affinity key；BREAKPOINTS 求 capability 支持 breakpoints 与请求实际 内容交集；最终 finalizer
 * 始终覆盖伪造 control；返回 ProviderRequest 其它字段内容不变。
 */
class PromptCacheRequestFinalizerTest {

  private static final long SESSION_ID = 99L;

  @Test
  void bindsSessionIdAndRejectsInvalid() {
    assertThrows(IllegalArgumentException.class, () -> new PromptCacheRequestFinalizer(0L));
    assertThrows(IllegalArgumentException.class, () -> new PromptCacheRequestFinalizer(-1L));
    // 验证构造期 sessionId 绑定：两个不同 sessionId 的 finalizer 必须在同一 request 上产生不同 affinity key。
    ProviderRequest request =
        requestWith(affinityModel(), ProviderCacheControl.none(), List.of(), List.of());
    PromptCacheRequestFinalizer a = new PromptCacheRequestFinalizer(101L);
    PromptCacheRequestFinalizer b = new PromptCacheRequestFinalizer(202L);
    assertNotEquals(
        a.apply(request).cacheControl().affinityKey(),
        b.apply(request).cacheControl().affinityKey());
  }

  @Test
  void overridesForgedControlForDisabledCapability() {
    ProviderRequest forged =
        requestWith(
            baseModel(PromptCachePolicy.disabled()),
            ProviderCacheControl.breakpoints(
                PromptCacheRetention.SHORT, "pc1-forged", EnumSet.of(PromptCacheBreakpoint.SYSTEM)),
            List.of(systemText("S1")),
            List.of());
    ProviderCacheControl resolved =
        new PromptCacheRequestFinalizer(SESSION_ID).apply(forged).cacheControl();
    assertEquals(PromptCacheRetention.NONE, resolved.retention());
    assertTrue(resolved.breakpoints().isEmpty());
    assertEquals(null, resolved.affinityKey());
  }

  @Test
  void returnsNoneForUnsupportedCapability() {
    // PromptCachePolicy 拒绝 UNKNOWN/UNSUPPORTED + 非 NONE retention 的组合，所以直接检查 disabled
    // 这条路径（unsupported capability + NONE retention）：finalizer 必须输出 none()。
    ProviderRequest forged =
        requestWith(
            baseModel(PromptCachePolicy.disabled()),
            ProviderCacheControl.affinity(PromptCacheRetention.LONG, "pc1-forged-key"),
            List.of(systemText("S1")),
            List.of());
    ProviderCacheControl resolved =
        new PromptCacheRequestFinalizer(SESSION_ID).apply(forged).cacheControl();
    assertEquals(PromptCacheRetention.NONE, resolved.retention());
  }

  @Test
  void returnsNoneForAutomaticCapability() {
    // AUTOMATIC capability 只允许 NONE retention；构造合法 policy 并验证 finalizer 输出 none()。
    ProviderRequest forged =
        requestWith(
            baseModel(
                new PromptCachePolicy(
                    PromptCacheCapability.automatic(), PromptCacheRetention.NONE)),
            ProviderCacheControl.affinity(PromptCacheRetention.LONG, "pc1-forged"),
            List.of(systemText("S1")),
            List.of());
    ProviderCacheControl resolved =
        new PromptCacheRequestFinalizer(SESSION_ID).apply(forged).cacheControl();
    assertEquals(PromptCacheRetention.NONE, resolved.retention());
  }

  @Test
  void retentionNoneAlwaysYieldsNone() {
    ProviderRequest forged =
        requestWith(
            baseModel(PromptCachePolicy.disabled()),
            ProviderCacheControl.affinity(PromptCacheRetention.LONG, "pc1-forged"),
            List.of(systemText("S1")),
            List.of());
    ProviderCacheControl resolved =
        new PromptCacheRequestFinalizer(SESSION_ID).apply(forged).cacheControl();
    assertEquals(PromptCacheRetention.NONE, resolved.retention());
  }

  @Test
  void affinityAlwaysGeneratesKeyEvenWithoutSystemOrTools() {
    ProviderRequest empty =
        requestWith(affinityModel(), ProviderCacheControl.none(), List.of(), List.of());
    ProviderCacheControl resolved =
        new PromptCacheRequestFinalizer(SESSION_ID).apply(empty).cacheControl();
    assertEquals(PromptCacheRetention.SHORT, resolved.retention());
    assertTrue(resolved.affinityKey().startsWith("pc1-"));
  }

  @Test
  void affinityDifferentSessionYieldsDifferentKey() {
    PromptCacheRequestFinalizer a = new PromptCacheRequestFinalizer(101L);
    PromptCacheRequestFinalizer b = new PromptCacheRequestFinalizer(202L);
    ProviderRequest request =
        requestWith(
            affinityModel(),
            ProviderCacheControl.none(),
            List.of(systemText("S1")),
            List.of(tool("alpha")));
    String keyA = a.apply(request).cacheControl().affinityKey();
    String keyB = b.apply(request).cacheControl().affinityKey();
    assertNotEquals(keyA, keyB);
  }

  @Test
  void breakpointsIntersectCapabilityWithRequestContents() {
    // Capability 同时支持 SYSTEM 与 TOOLS；请求只有 system => 只有 SYSTEM 进入 breakpoints。
    ProviderRequest onlySystem =
        requestWith(
            breakpointsModel(EnumSet.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS)),
            ProviderCacheControl.none(),
            List.of(systemText("S1")),
            List.of());
    ProviderCacheControl onlySystemResolved =
        new PromptCacheRequestFinalizer(SESSION_ID).apply(onlySystem).cacheControl();
    assertEquals(PromptCacheRetention.SHORT, onlySystemResolved.retention());
    assertEquals(EnumSet.of(PromptCacheBreakpoint.SYSTEM), onlySystemResolved.breakpoints());

    // 请求只有 tool => 只有 TOOLS。
    ProviderRequest onlyTools =
        requestWith(
            breakpointsModel(EnumSet.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS)),
            ProviderCacheControl.none(),
            List.of(),
            List.of(tool("alpha")));
    ProviderCacheControl onlyToolsResolved =
        new PromptCacheRequestFinalizer(SESSION_ID).apply(onlyTools).cacheControl();
    assertEquals(EnumSet.of(PromptCacheBreakpoint.TOOLS), onlyToolsResolved.breakpoints());

    // 请求同时存在 system + tools => 两个都进入 breakpoints。
    ProviderRequest both =
        requestWith(
            breakpointsModel(EnumSet.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS)),
            ProviderCacheControl.none(),
            List.of(systemText("S1")),
            List.of(tool("alpha")));
    ProviderCacheControl bothResolved =
        new PromptCacheRequestFinalizer(SESSION_ID).apply(both).cacheControl();
    assertEquals(
        EnumSet.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS),
        bothResolved.breakpoints());

    // 请求既无 system 也无 tools => 无有效 breakpoint，降级 none()。
    ProviderRequest empty =
        requestWith(
            breakpointsModel(EnumSet.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS)),
            ProviderCacheControl.none(),
            List.of(),
            List.of());
    ProviderCacheControl emptyResolved =
        new PromptCacheRequestFinalizer(SESSION_ID).apply(empty).cacheControl();
    assertEquals(PromptCacheRetention.NONE, emptyResolved.retention());
  }

  @Test
  void breakpointsFilterByCapabilityWhenCapabilityLimitedToSystemOnly() {
    // Capability 只声明 SYSTEM 支持时，TOOLS 即使实际有 tools 也应被剔除。
    ProviderRequest request =
        requestWith(
            breakpointsModel(EnumSet.of(PromptCacheBreakpoint.SYSTEM)),
            ProviderCacheControl.none(),
            List.of(systemText("S1")),
            List.of(tool("alpha")));
    ProviderCacheControl resolved =
        new PromptCacheRequestFinalizer(SESSION_ID).apply(request).cacheControl();
    assertEquals(EnumSet.of(PromptCacheBreakpoint.SYSTEM), resolved.breakpoints());
  }

  @Test
  void preservedOtherProviderRequestFields() {
    ProviderRequest forged =
        requestWith(
            affinityModel(),
            ProviderCacheControl.breakpoints(
                PromptCacheRetention.LONG,
                "pc1-forged",
                EnumSet.of(PromptCacheBreakpoint.SYSTEM, PromptCacheBreakpoint.TOOLS)),
            List.of(systemText("S1")),
            List.of(tool("alpha")));
    ProviderRequest after = new PromptCacheRequestFinalizer(SESSION_ID).apply(forged);
    assertSame(forged.model(), after.model());
    assertSame(forged.variant(), after.variant());
    assertEquals(forged.messages(), after.messages());
    assertEquals(forged.tools(), after.tools());
  }

  private static ProviderMessage systemText(String text) {
    return new ProviderMessage(ProviderMessageRole.SYSTEM, List.of(new ProviderTextBlock(text)));
  }

  private static ProviderToolDefinition tool(String name) {
    return new ProviderToolDefinition(name, "desc-" + name, "{\"type\":\"object\"}");
  }

  private static ModelDescriptor baseModel(PromptCachePolicy policy) {
    return new ModelDescriptor(
        "provider", 0L, "m1", ProviderType.OPENAI, true, false, pricing(), policy);
  }

  private static ModelDescriptor affinityModel() {
    return baseModel(
        new PromptCachePolicy(
            PromptCacheCapability.affinity(EnumSet.of(PromptCacheRetention.SHORT)),
            PromptCacheRetention.SHORT));
  }

  private static ModelDescriptor breakpointsModel(Set<PromptCacheBreakpoint> supportedBreakpoints) {
    return baseModel(
        new PromptCachePolicy(
            PromptCacheCapability.breakpoints(
                EnumSet.of(PromptCacheRetention.SHORT), supportedBreakpoints),
            PromptCacheRetention.SHORT));
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
      ModelDescriptor model,
      ProviderCacheControl forged,
      List<ProviderMessage> messages,
      List<ProviderToolDefinition> tools) {
    ModelVariant variant =
        new ModelVariant("default", null, null, null, null, null, null, List.of(), null);
    return new ProviderRequest(model, variant, messages, tools, forged);
  }
}
