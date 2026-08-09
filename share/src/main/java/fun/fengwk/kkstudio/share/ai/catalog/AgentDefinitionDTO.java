package fun.fengwk.kkstudio.share.ai.catalog;

import lombok.Data;

import java.time.Instant;

/** 全局 Agent definition 的公开表示。 */
@Data
public class AgentDefinitionDTO {

  /** Agent 唯一名（资源路由身份）：非空白、不得包含 {@code '/'}、≤64 字符。 */
  private String name;

  /** 可空描述（≤512 字符）。 */
  private String description;

  /** 可空系统提示词。 */
  private String systemPrompt;

  /** 当前绑定的模型引用，序列化形式为 {@code providerName/modelName}。 */
  private String model;

  /** 当前生效的模型变体 id；null 表示未显式指定（运行时解析 model config 的 defaultVariant）。 */
  private String variant;

  /** 结构化执行配置（tools/skills/subagents），来自持久化的 config JSONB 列。 */
  private AgentDefinitionConfigDTO config;

  /** 非负十进制字符串版本号；客户端每次更新时必须回传。 */
  private String version;

  /** 创建时间（UTC Instant）。 */
  private Instant createTime;

  /** 更新时间（UTC Instant）。 */
  private Instant updateTime;
}
