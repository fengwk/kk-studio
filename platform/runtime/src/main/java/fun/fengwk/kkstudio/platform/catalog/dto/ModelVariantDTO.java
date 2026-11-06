package fun.fengwk.kkstudio.platform.catalog.dto;

import lombok.Data;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;

/**
 * 单个模型变体，表达 reasoning effort、提示缓存留存档位与厂商原生协议请求选项。
 *
 * <p>{@code reasoningEffort} 可空：由厂商自定义（如 {@code high} / {@code medium} / {@code low} / {@code max}
 * / {@code xhigh} 等）；{@code off} 表示显式关闭推理，{@code null} 表示不下发该字段、由协议默认决定。两者语义不同，绝不互相静默映射。 非 null 的
 * reasoningEffort 只去除首尾空白并保留原始大小写，禁止空白且长度不能超过 64 个字符。
 *
 * <p>{@code protocolOptionsJson} 可空：JSON object 文本形态的厂商原生请求选项，{@code null} 与 {@code {}} 等价。本 DTO
 * 只保证 wire token 是 JSON string；严格 object 语法、64 KiB UTF-8 上限与 canonical 化由 harness 的 {@code
 * ProviderProtocolOptions} 在平台解析配置时统一执行。数字文本绝不经由前端浮点解析。选项只允许官方协议的非传输字段， Provider 凭据、endpoint、传输模式与
 * runtime 冻结事实不允许被覆盖，具体合并规则由各协议编码器负责。
 */
@Data
public class ModelVariantDTO {

  /** 必填变体 id：非空白、无环绕空白，且在 {@code config.variants} 内唯一。 */
  private String id;

  /** 必填提示缓存留存档位；必须被所属 Provider 类型的 {@code promptCacheRetentions} 支持。 */
  private PromptCacheRetention promptCacheRetention;

  /** 可空 reasoning effort：由厂商自定义，{@code off} 显式关闭，null 表示协议默认；最大长度 64。 */
  private @Nullable String reasoningEffort;

  /** 厂商原生协议选项的 JSON object 文本；传输与持久化均不解码其中的数值。 */
  private @Nullable String protocolOptionsJson;
}
