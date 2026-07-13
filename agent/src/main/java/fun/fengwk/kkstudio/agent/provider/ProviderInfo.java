package fun.fengwk.kkstudio.agent.provider;

import lombok.Builder;
import lombok.Data;

import java.time.Duration;

/**
 * ProviderInfo 表示一个 provider 的连接与超时配置。
 *
 * <p>语义说明： - 这是 provider 层的稳定输入配置。 - 它不承载模型参数，也不承载 Variant 请求参数。 - 新增 provider
 * 时，优先确认当前字段是否已足够描述连接信息；只有确实不足时再扩展。
 *
 * @author fengwk
 */
@Builder
@Data
public class ProviderInfo {

  /** provider 类型。 */
  private final ProviderType providerType;

  /** provider 服务地址。 */
  private final String baseUrl;

  /** provider 访问凭据。 */
  private final String apiKey;

  /** provider 总体请求超时。 */
  private final Duration timeout;
}
