package fun.fengwk.kkstudio.share.interaction;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;

/** 服务端解析的产品归属或独立 Agent 任务。 */
@Data
public class InteractionOwnerDTO {

  private String kind;
  private String id;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String title;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String link;
}
