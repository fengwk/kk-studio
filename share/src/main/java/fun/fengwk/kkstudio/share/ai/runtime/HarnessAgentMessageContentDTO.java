package fun.fengwk.kkstudio.share.ai.runtime;

import lombok.Data;
import org.jspecify.annotations.Nullable;

/**
 * 公共 Assistant 输出内容单元。
 *
 * <p>只有 Driver 已完成的文本与思考片段进入公共快照；类型与 Entry payload 中的 durable content discriminator 保持一致。
 */
@Data
public class HarnessAgentMessageContentDTO {

  /** 内容类型：{@code text} 或 {@code thinking}。 */
  private String type;

  /** 已完成片段文本；两种类型均必填。 */
  private @Nullable String text;
}
