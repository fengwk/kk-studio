package fun.fengwk.kkstudio.agent;

import java.util.List;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * AgentInfo 表示一个 agent 的展开配置。
 *
 * @author fengwk
 */
@Builder
@Data
public class AgentInfo {

  /** agent 名称。 */
  private final String name;

  /** agent 默认 system prompt。 */
  private final String systemPrompt;

  /** agent 默认 provider。 */
  private final String defaultProvider;

  /** agent 默认 model。 */
  private final String defaultModel;

  /** agent 默认 variant。 */
  private final String defaultVariant;

  /** agent 可用工具列表。 */
  private final List<String> tools;

  /** agent 可用子代理列表。 */
  private final List<String> subagents;

  /** agent 可用技能列表。 */
  private final List<String> skills;
}
