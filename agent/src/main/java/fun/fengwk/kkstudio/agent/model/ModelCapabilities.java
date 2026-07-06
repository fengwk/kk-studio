package fun.fengwk.kkstudio.agent.model;

import java.util.List;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * ModelCapabilities 表示模型能力描述。
 *
 * @author fengwk
 */
@Builder
@Data
public class ModelCapabilities {

  /** 是否支持工具调用。 */
  private final boolean tools;

  /** 支持的输入模态列表。 */
  private final List<ModelModality> input;

  /** 支持的输出模态列表。 */
  private final List<ModelModality> output;
}
