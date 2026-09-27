package fun.fengwk.kkstudio.share.ai.interaction;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 待处理 Interaction 的一页稳定分页结果。
 *
 * <p>{@code items} 按 {@code (createTime, interactionId)} 升序；{@code nextCursor}
 * 非空表示还有下一页，原样回传即可继续翻页。
 */
@Data
public class InteractionPageDTO {

  private List<InteractionDTO> items = new ArrayList<>();

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private String nextCursor;
}
