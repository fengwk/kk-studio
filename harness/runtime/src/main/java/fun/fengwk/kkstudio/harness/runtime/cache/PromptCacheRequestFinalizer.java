package fun.fengwk.kkstudio.harness.runtime.cache;

import fun.fengwk.kkstudio.harness.agent.extension.BeforeProviderRequestInterceptor;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheBreakpoint;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheMode;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Provider request 链上唯一的 cache control 派生点，固定在所有 Extension Host hooks 之后。
 *
 * <p>行为契约：
 *
 * <ul>
 *   <li>构造时绑定 sessionId，永远覆盖请求中已有的 {@link ProviderCacheControl}；不信任任何 extension 伪造的 control。
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
public final class PromptCacheRequestFinalizer implements BeforeProviderRequestInterceptor {

  private final long sessionId;
  private final PromptCacheAffinityKeyFactory keyFactory;

  public PromptCacheRequestFinalizer(long sessionId, PromptCacheAffinityKeyFactory keyFactory) {
    if (sessionId <= 0) {
      throw new IllegalArgumentException("sessionId must be positive");
    }
    this.sessionId = sessionId;
    this.keyFactory = Objects.requireNonNull(keyFactory, "keyFactory");
  }

  public PromptCacheRequestFinalizer(long sessionId) {
    this(sessionId, new PromptCacheAffinityKeyFactory());
  }

  public long sessionId() {
    return sessionId;
  }

  @Override
  public ProviderRequest intercept(ProviderRequest request) {
    Objects.requireNonNull(request, "request");
    ModelDescriptor model = request.model();
    if (model == null) {
      throw new IllegalArgumentException("request.model() must not be null");
    }
    PromptCacheCapability capability = model.promptCachePolicy().capability();
    PromptCacheRetention retention = model.promptCachePolicy().retention();
    ProviderCacheControl resolved = resolve(request, capability, retention);
    return new ProviderRequest(
        model, request.variant(), request.messages(), request.tools(), resolved);
  }

  private ProviderCacheControl resolve(
      ProviderRequest request, PromptCacheCapability capability, PromptCacheRetention retention) {
    if (retention == PromptCacheRetention.NONE) {
      return ProviderCacheControl.none();
    }
    switch (capability.mode()) {
      case UNKNOWN:
      case UNSUPPORTED:
      case AUTOMATIC:
        return ProviderCacheControl.none();
      case AFFINITY:
        return affinityControl(request);
      case BREAKPOINTS:
        return breakpointControl(request, capability);
      default:
        return ProviderCacheControl.none();
    }
  }

  private ProviderCacheControl affinityControl(ProviderRequest request) {
    PromptCacheRetention retention = request.model().promptCachePolicy().retention();
    String key = keyFactory.create(sessionId, request);
    return ProviderCacheControl.affinity(retention, key);
  }

  private ProviderCacheControl breakpointControl(
      ProviderRequest request, PromptCacheCapability capability) {
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
    String key = keyFactory.create(sessionId, request);
    return ProviderCacheControl.breakpoints(
        request.model().promptCachePolicy().retention(), key, resolved);
  }

  private static boolean hasLeadingSystem(ProviderRequest request) {
    for (ProviderMessage message : request.messages()) {
      if (message.role() != ProviderMessageRole.SYSTEM) {
        return false;
      }
      return true;
    }
    return false;
  }
}
