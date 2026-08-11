package fun.fengwk.kkstudio.harness.runtime.cache;

import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheMode;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * ModelInvocationPlanner 在冻结 {@link ProviderRequest} 时的唯一 cache control 派生点。
 *
 * <p>行为契约：
 *
 * <ul>
 *   <li>构造时绑定 sessionId，永远覆盖请求中已有的 {@link ProviderCacheControl}；不存在执行时 hook 重写路径。
 *   <li>{@link PromptCachePolicy} 由调用方显式传入且不随 {@link ProviderRequest} 或 ModelDescriptor 持久化；具体解析与
 *       调用时点由调用方（Core）决定。
 *   <li>{@link PromptCacheRetention#NONE} 一律输出 {@link ProviderCacheControl#none()}。
 *   <li>policy capability 为 {@link PromptCacheMode#UNKNOWN} / {@link PromptCacheMode#UNSUPPORTED} /
 *       {@link PromptCacheMode#AUTOMATIC} 一律输出 {@code none()}；harness 不向 Provider 传递 cache hint。
 *   <li>{@link PromptCacheMode#AFFINITY} 即使没有 system/tools 也派生 affinity key，输出 {@link
 *       ProviderCacheControl#affinity}。
 *   <li>{@link PromptCacheMode#BREAKPOINTS} 求 capability 支持 breakpoints 与请求实际内容的交集： leading SYSTEM
 *       存在才允许 SYSTEM，tools 非空才允许 TOOLS；如无有效 breakpoint 则输出 {@code none()}， 否则输出 {@link
 *       ProviderCacheControl#breakpoints}。
 * </ul>
 */
public final class PromptCacheRequestFinalizer {

  private final UUID sessionId;
  private final PromptCacheAffinityKeyFactory keyFactory;

  public PromptCacheRequestFinalizer(UUID sessionId, PromptCacheAffinityKeyFactory keyFactory) {
    this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
    this.keyFactory = Objects.requireNonNull(keyFactory, "keyFactory");
  }

  public PromptCacheRequestFinalizer(UUID sessionId) {
    this(sessionId, new PromptCacheAffinityKeyFactory());
  }

  public ProviderRequest apply(ProviderRequest request, PromptCachePolicy policy) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(request.model(), "request.model()");
    Objects.requireNonNull(policy, "policy");
    ProviderCacheControl resolved = resolve(request, policy);
    return new ProviderRequest(
        request.model(), request.variant(), request.messages(), request.tools(), resolved);
  }

  private ProviderCacheControl resolve(ProviderRequest request, PromptCachePolicy policy) {
    PromptCacheCapability capability = policy.capability();
    PromptCacheRetention retention = policy.retention();
    // PromptCachePolicy 构造校验保证：非 NONE retention 只可能搭配 AFFINITY / BREAKPOINTS capability，
    // UNKNOWN/UNSUPPORTED/AUTOMATIC 一律走 NONE 分支输出 none()。
    if (retention == PromptCacheRetention.NONE) {
      return ProviderCacheControl.none();
    }
    if (capability.mode() == PromptCacheMode.AFFINITY) {
      return ProviderCacheControl.affinity(retention, keyFactory.create(sessionId, request));
    }
    return breakpointControl(request, capability, retention);
  }

  private ProviderCacheControl breakpointControl(
      ProviderRequest request, PromptCacheCapability capability, PromptCacheRetention retention) {
    Set<PromptCacheBreakpoint> supported = capability.supportedBreakpoints();
    EnumSet<PromptCacheBreakpoint> resolved = EnumSet.noneOf(PromptCacheBreakpoint.class);
    if (supported.contains(PromptCacheBreakpoint.SYSTEM) && hasLeadingSystem(request)) {
      resolved.add(PromptCacheBreakpoint.SYSTEM);
    }
    if (supported.contains(PromptCacheBreakpoint.TOOLS) && !request.tools().isEmpty()) {
      resolved.add(PromptCacheBreakpoint.TOOLS);
    }
    if (resolved.isEmpty()) {
      return ProviderCacheControl.none();
    }
    return ProviderCacheControl.breakpoints(
        retention, keyFactory.create(sessionId, request), resolved);
  }

  private static boolean hasLeadingSystem(ProviderRequest request) {
    if (request.messages().isEmpty()) {
      return false;
    }
    ProviderMessage first = request.messages().get(0);
    return first.role() == ProviderMessageRole.SYSTEM;
  }
}
