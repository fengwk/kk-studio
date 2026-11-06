package fun.fengwk.kkstudio.platform.harness.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
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
  private Type type;

  /** 已完成片段文本；两种类型均必填。 */
  private @Nullable String text;

  /** 助手可见内容类型；wire 使用小写。 */
  public enum Type {
    @JsonProperty("text")
    TEXT,
    @JsonProperty("thinking")
    THINKING
  }
}
