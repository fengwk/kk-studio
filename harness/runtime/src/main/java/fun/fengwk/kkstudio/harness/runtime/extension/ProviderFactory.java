package fun.fengwk.kkstudio.harness.runtime.extension;

import fun.fengwk.kkstudio.harness.model.cache.PromptCacheCapability;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.adapter.ProviderAdapter;

/** 按 Provider 类型从凭据和配置创建标准适配器。 */
public interface ProviderFactory {

  ProviderType providerType();

  /** Provider 暴露给资源解析器的可信提示缓存能力快照，用于构造模型请求策略。 */
  PromptCacheCapability promptCacheCapability();

  ProviderAdapter create(String credential, String configJson);
}
