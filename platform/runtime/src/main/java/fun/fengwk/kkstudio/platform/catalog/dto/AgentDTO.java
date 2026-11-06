package fun.fengwk.kkstudio.platform.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.platform.catalog.definition.AgentType;

import java.time.Instant;

/** 全局 Agent 的公开表示。 */
@Data
public class AgentDTO {

  /** Agent 唯一名（资源路由身份）：非空白、不得包含 {@code '/'}、≤64 字符。 */
  private String name;

  /** 系统持有的所有权类型；用户创建的 Agent 为 {@link AgentType#USER}，内置 Agent 为 BUILTIN。 */
  private AgentType type;

  /** 可空描述（text，无长度上限）。 */
  private @Nullable String description;

  /** 可空系统提示词。 */
  private @Nullable String systemPrompt;

  /** 当前执行选择；未配置的内置 Agent 显式序列化为 {@code null}。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable HarnessExecutionSelectionDTO execution;

  /** 结构化执行配置（tools/skills/subagents），来自持久化的 config JSONB 列。 */
  private AgentConfigDTO config;

  /** 非负版本号；客户端每次更新时必须回传。 */
  private Long version;

  /** 创建时间（UTC Instant）。 */
  private Instant createdAt;

  /** 更新时间（UTC Instant）。 */
  private Instant updatedAt;
}
