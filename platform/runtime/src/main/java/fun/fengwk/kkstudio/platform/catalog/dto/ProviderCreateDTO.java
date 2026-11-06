package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.harness.model.provider.ProviderType;

import java.util.List;

/**
 * 创建 Provider 的请求体。
 *
 * <p>{@code name} 与 {@code providerType} 只在创建时设置，之后都不可修改；换协议需要新建 Provider，再让模型改指向它。
 */
@Data
public class ProviderCreateDTO {

  /** 必填 Provider 唯一名（资源路由身份）：trim 后非空白、不得包含 {@code '/'}、≤64 字符。 */
  private String name;

  /** 必填协议类型，创建后不可修改。 */
  private ProviderType providerType;

  /** 可空描述（text，无长度上限）；null/空白视为无描述。 */
  private @Nullable String description;

  /** 可空模型 API base URL（≤512 字符）。 */
  private @Nullable String baseUrl;

  /** 可空凭据（API key 等）：null 表示无凭据；永不通过公开 DTO 回读。 */
  private @Nullable String credential;

  /** 可空模型调用总超时（毫秒，正数）；null 表示使用产品默认值。 */
  private @Nullable Long modelCallTimeoutMillis;

  /** 可空模型调用空闲超时（毫秒，正数）；null 表示使用产品默认值。 */
  private @Nullable Long modelCallIdleTimeoutMillis;

  /**
   * HTTP 重试白名单覆盖：null 表示继承系统名单，空数组表示不自动重试任何 HTTP 错误。
   *
   * <p>只允许 400–599 的整型状态，拒绝 null 元素、重复与非法值。
   */
  private @Nullable List<Integer> modelHttpRetryStatusCodes;
}
