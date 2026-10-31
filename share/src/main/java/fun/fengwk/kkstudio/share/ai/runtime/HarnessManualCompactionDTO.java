package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;

/**
 * 独立压缩 advisory 端点的手动压缩可用性投影。
 *
 * <p>它不是快照字段，也不构成 compact 提交许可；实际提交仍由 compact 请求的 expectedVersion 与服务端权威校验守护。
 */
@Data
public class HarnessManualCompactionDTO {

  private Boolean available;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String disabledReason;
}
