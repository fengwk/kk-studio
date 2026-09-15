package fun.fengwk.kkstudio.harness.runtime.model.provider;

import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;

import java.util.Set;

/**
 * 协议与连接配置允许的内联媒体模态；用户内容与工具结果分别声明。
 *
 * <p>物化仍须与模型输入模态取交集；此能力不替代编码器的 MIME、数据格式与请求大小校验。
 */
public record ProviderMediaCapabilities(
    Set<ModelInputModality> userModalities, Set<ModelInputModality> toolResultModalities) {

  public static final ProviderMediaCapabilities NONE =
      new ProviderMediaCapabilities(Set.of(), Set.of());

  public ProviderMediaCapabilities {
    userModalities = Set.copyOf(userModalities);
    toolResultModalities = Set.copyOf(toolResultModalities);
  }

  public boolean supports(ModelInputModality modality, boolean toolResult) {
    return (toolResult ? toolResultModalities : userModalities).contains(modality);
  }
}
