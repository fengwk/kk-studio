package fun.fengwk.kkstudio.harness.runtime.model.worker;

import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;

/**
 * 由 frozen ProviderRequest 解析一次执行资源的外层 adapter 端口。
 *
 * <p>解析发生在 durable claim 之前，只允许获得配置、credential reference 对应的执行 adapter 等本地资源；不得执行 Provider 请求。claim
 * 仍是唯一使 Invocation 进入 RUNNING 的 durable transition。
 */
@FunctionalInterface
public interface ModelExecutionResolver {

  ModelExecutionResource resolve(ProviderRequest request);
}
