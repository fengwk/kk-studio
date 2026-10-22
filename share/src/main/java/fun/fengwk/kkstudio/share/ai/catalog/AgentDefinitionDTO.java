package fun.fengwk.kkstudio.share.ai.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/** 全局 Agent definition 的公开表示。 */
@Data
public class AgentDefinitionDTO {

  /** Agent 唯一名（资源路由身份）：非空白、不得包含 {@code '/'}、≤64 字符。 */
  private String name;

  /** 系统持有的所有权类型；用户创建的 Agent 为 {@link AgentDefinitionType#USER}，内置 Agent 为 BUILTIN。 */
  private AgentDefinitionType type;

  /** 可空描述（text，无长度上限）。 */
  private @Nullable String description;

  /** 可空系统提示词。 */
  private @Nullable String systemPrompt;

  /** 当前绑定的全局逻辑模型名（MODEL 或 GROUP）；未配置模型的内置 Agent 显式序列化为 {@code null}，与字段缺失区分。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String model;

  /** 当前生效的单模型 Variant id；null 表示未显式指定（运行时解析 model config 的 defaultVariant）；选择 GROUP 时必须为 null。 */
  private @Nullable String variant;

  /** 结构化执行配置（tools/skills/subagents/inheritParentEnvironment），来自持久化的 config JSONB 列。 */
  private AgentDefinitionConfigDTO config;

  /** 非负十进制字符串版本号；客户端每次更新时必须回传。 */
  private String version;

  /** 创建时间（UTC Instant）。 */
  private Instant createTime;

  /** 更新时间（UTC Instant）。 */
  private Instant updateTime;
}
