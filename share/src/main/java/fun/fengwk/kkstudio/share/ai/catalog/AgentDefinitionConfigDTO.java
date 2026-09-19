package fun.fengwk.kkstudio.share.ai.catalog;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;

import java.util.List;

/**
 * 持久化到 {@code agent_definition.config} JSONB 列的 Agent definition 执行配置。
 *
 * <p>{@code tools} 使用有序、唯一的模型可见 tool name；{@code skills} 使用明确的 {@link AgentSkillRefDTO}； {@code
 * subagents} 只接受短名；{@code inheritParentEnvironment} 控制本 Agent 被委派时的 Environment 继承。
 *
 * @author fengwk
 */
@Data
public class AgentDefinitionConfigDTO {

  /** 可选模型可见 tool name 列表；只允许选择离线 catalog 中的可选择工具，元素按声明顺序且不可重复。 */
  private List<String> tools;

  /** Agent 技能引用列表；必须精确引用选定 Environment 的持久可用 Skill。 */
  private List<AgentSkillRefDTO> skills;

  /** 当前 Agent 可通过 task 委派的 Agent 名称 allowlist；元素须为非空白短名、去重、≤64 字符。 */
  private List<String> subagents;

  /**
   * 本 Agent 被 {@code task} 委派时是否继承父 Model invocation 冻结的 Environment name；缺省为 true。
   *
   * <p>只在该 Agent 作为子 Agent 被调用时生效：Agent 作为普通根 Agent 使用时永远不继承任何父环境。显式 JSON null 不是缺省， 而是非法
   * shape，由持久化 config codec 在解码时拒绝。
   */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  @Setter(AccessLevel.NONE)
  private Boolean inheritParentEnvironment = Boolean.TRUE;

  /**
   * 保留 JSON token 的实际类型，避免 Jackson 把字符串或数字宽松转换为布尔值。
   *
   * <p>null 由持久化 config codec 统一拒绝；字段缺失时不会调用本方法，保留默认 true。
   */
  @JsonSetter
  public void setInheritParentEnvironment(Object value) {
    if (value != null && !(value instanceof Boolean)) {
      throw new IllegalArgumentException(
          "agent definition config inheritParentEnvironment must be a boolean");
    }
    inheritParentEnvironment = (Boolean) value;
  }

  /** 拒绝紧凑持久化配置契约之外的字段。 */
  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object ignoredValue) {
    throw new IllegalArgumentException("unknown agent definition config field: " + fieldName);
  }
}
