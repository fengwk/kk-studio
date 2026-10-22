package fun.fengwk.kkstudio.share.ai.plugin;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * 权威 catalog 当前 desired 配置的安全投影：普通值与 schema 定义，以及每个已声明秘密字段的录入状态。
 *
 * <p>{@code revision} 是权威 catalog 中该 Plugin 当前条目的配置修订；本节点实际应用的修订由 {@link
 * PluginDTO#getAppliedRevision()} 单独表达。因此「刚保存但本节点尚未应用」时这里仍是本次保存的值，不会回退成旧值。
 *
 * <p>{@code valuesSchema} 由唯一 {@code InputSchema} 声明经既有 {@code SchemaJsonCodec} 派生；{@code
 * valuesJson} 与 {@code defaultsJson} 保持原有数字精度与 64 KiB 预算。{@code secrets} 只有说明与布尔，绝不携带任何秘密值。
 */
@Data
public class PluginConfigurationDTO {

  /** 该投影对应的配置修订（canonical 非负十进制字符串）；尚无历史时为 null。 */
  private @Nullable String revision;

  /** 该修订的普通值 canonical JSON 文本。 */
  private String valuesJson;

  /** 由配置 schema 派生的 canonical values schema JSON 文本。 */
  private String valuesSchema;

  /** 由配置 schema 派生的唯一默认值 canonical JSON 文本。 */
  private String defaultsJson;

  /** 每个已声明秘密字段的说明与录入状态；没有声明秘密字段时为空映射。 */
  private Map<String, PluginSecretProjectionDTO> secrets;
}
