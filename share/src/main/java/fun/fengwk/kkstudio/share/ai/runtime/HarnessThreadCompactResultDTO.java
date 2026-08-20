package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/** 手动压缩提交结果。 */
@Data
public class HarnessThreadCompactResultDTO {

  /** 提交后的权威 Thread projection。 */
  private HarnessThreadDTO thread;

  /** 本次压缩 Turn 的 start Entry。 */
  private String turnStartEntryId;

  /** Resolver 接受时的 Model invocation；业务拒绝时为 null。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String modelInvocationId;
}
