package fun.fengwk.kkstudio.share.catalog;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import fun.fengwk.kkstudio.share.skill.SkillRefDTO;

import java.util.List;

/**
 * 持久化到 {@code agent_definition.config} JSONB 列的 Agent definition 执行配置。
 *
 * <p>{@code tools} 使用有序、唯一的模型可见 tool name；{@code skills} 使用有序、唯一的 {@link
 * fun.fengwk.kkstudio.share.skill.SkillRefDTO}（{@code (packageName, name)}，每个引用都必须命中对应 Package 当前
 * manifest）；{@code subagents} 只接受短名。
 *
 * @author fengwk
 */
@Data
public class AgentDefinitionConfigDTO {

  /** 可选模型可见 tool name 列表；只允许选择离线 catalog 中的可选择工具，元素按声明顺序且不可重复。 */
  private List<String> tools;

  /** 选中的 Skill 引用列表（{@code (packageName, name)}）；按声明顺序且不可重复。 */
  private List<SkillRefDTO> skills;

  /** 当前 Agent 可通过 task 委派的 Agent 名称 allowlist；元素须为非空白短名、去重、≤64 字符。 */
  private List<String> subagents;

  /** 拒绝紧凑持久化配置契约之外的字段。 */
  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown agent definition config field: " + fieldName);
  }
}
