package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.platform.secret.SecretUpdate;

import java.util.List;

/**
 * {@code PUT /api/platform/providers/{name}} 的请求体：整体替换全部可编辑属性。
 *
 * <p>{@code name} 与 {@code providerType} 只在创建时设置，不出现在更新请求中；描述、地址与重试覆盖都按“存什么就是什么”写入。
 */
@Data
public class ProviderUpdateDTO {

  /** 可空描述（text，无长度上限）；null/空白视为无描述。 */
  private @Nullable String description;

  /** 可空模型 API base URL（≤512 字符）。 */
  private @Nullable String baseUrl;

  /** 必填凭据更新：KEEP 保留、SET 覆盖、CLEAR 清除。 */
  private SecretUpdate credential;

  /** 必填模型调用总超时（毫秒，正数）。 */
  private Long modelCallTimeoutMillis;

  /** 必填模型调用空闲超时（毫秒，正数）。 */
  private Long modelCallIdleTimeoutMillis;

  /** HTTP 重试白名单覆盖：null 表示继承系统名单，空数组表示不自动重试任何 HTTP 错误。 */
  private @Nullable List<Integer> modelHttpRetryStatusCodes;

  /** 必填非负十进制字符串；必须与当前 Provider 版本一致。 */
  private String expectedVersion;
}
