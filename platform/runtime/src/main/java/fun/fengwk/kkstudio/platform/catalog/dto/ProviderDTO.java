package fun.fengwk.kkstudio.platform.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.platform.secret.SecretConfiguredDTO;

import java.time.Instant;
import java.util.List;

/** 不含凭据的 Provider 公开表示。 */
@Data
public class ProviderDTO {

  /** Provider 唯一名（资源路由身份）：非空白、不得包含 {@code '/'}、≤64 字符。 */
  private String name;

  /** 可空描述（text，无长度上限）。 */
  private @Nullable String description;

  /** 协议类型，创建后不可修改。 */
  private ProviderType providerType;

  /** 可空模型 API base URL（≤512 字符）。 */
  private @Nullable String baseUrl;

  /** 凭据配置事实；只返回是否已配置，绝不回显值。 */
  private SecretConfiguredDTO credential;

  /** 模型调用总超时（毫秒，正数）；未配置时由产品默认值填充。 */
  private Long modelCallTimeoutMillis;

  /** 模型调用空闲超时（毫秒，正数）；未配置时由产品默认值填充。 */
  private Long modelCallIdleTimeoutMillis;

  /** HTTP 重试白名单覆盖：null 表示继承系统名单，空数组表示不自动重试任何 HTTP 错误。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable List<Integer> modelHttpRetryStatusCodes;

  /** 只读支持的提示缓存留存档位（由 providerType 派生）。 */
  private List<PromptCacheRetention> promptCacheRetentions;

  /** 非负十进制字符串版本号；客户端每次更新时必须回传。 */
  private String version;

  /** 创建时间。 */
  private Instant createdAt;

  /** 更新时间。 */
  private Instant updatedAt;
}
