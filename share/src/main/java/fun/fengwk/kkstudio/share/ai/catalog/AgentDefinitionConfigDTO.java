package fun.fengwk.kkstudio.share.ai.catalog;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.util.List;

/**
 * 持久化到 {@code agent_definition.config} JSONB 列的 Agent definition 执行配置。
 *
 * <p>{@code tools}、{@code skills} 与 {@code subagents} 只接受短名（不允许 namespace 或路径字符串）。
 *
 * @author fengwk
 */
@Data
public class AgentDefinitionConfigDTO {

  /** 可选工具短名列表；只允许选择离线 catalog 中的可选择工具（禁止内部平台工具），元素须为非空白短名、去重、≤128 字符。 */
  private List<String> tools;

  /** Agent 技能短名列表；运行时须由选中的 live Environment 精确提供，元素须为非空白短名、去重、≤128 字符。 */
  private List<String> skills;

  /** 当前 Agent 可通过 task 委派的 Agent 名称 allowlist；元素须为非空白短名、去重、≤64 字符。 */
  private List<String> subagents;

  /** 拒绝紧凑持久化配置契约之外的字段。 */
  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown agent definition config field: " + fieldName);
  }
}
