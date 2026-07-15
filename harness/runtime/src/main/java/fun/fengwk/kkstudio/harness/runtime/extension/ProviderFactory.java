package fun.fengwk.kkstudio.harness.runtime.extension;

import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.adapter.ProviderAdapter;

/** 按 Provider 类型从凭据和配置创建标准适配器。 */
public interface ProviderFactory {

  ProviderType providerType();

  ProviderAdapter create(String credential, String configJson);
}
