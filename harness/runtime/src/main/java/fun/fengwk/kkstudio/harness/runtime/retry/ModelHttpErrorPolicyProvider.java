package fun.fengwk.kkstudio.harness.runtime.retry;

/**
 * 向 Model processor 提供每次 retry 判定现读的 {@link ModelHttpErrorPolicy}。
 *
 * <p>{@code providerName} 是当前 Invocation 冻结的 Provider 身份：生产解析据此先读 Provider 覆盖、否则回退系统名单；Provider
 * 覆盖与系统名单都是现读快照，不重置 attempt、不重写已派发请求。
 */
@FunctionalInterface
public interface ModelHttpErrorPolicyProvider {

  /** 部署级默认策略：忽略 Provider 身份，等价于系统设置默认白名单。 */
  ModelHttpErrorPolicyProvider DEFAULT = providerName -> ModelHttpErrorPolicy.DEFAULT;

  /** 返回指定 Provider 当前生效的模型 HTTP 状态策略；每次 retry 判定点调用。 */
  ModelHttpErrorPolicy policy(String providerName);
}
