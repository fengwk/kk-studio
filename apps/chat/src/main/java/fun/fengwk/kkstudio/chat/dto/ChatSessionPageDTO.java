package fun.fengwk.kkstudio.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import fun.fengwk.kkstudio.share.runtime.HarnessSessionSummaryDTO;

import java.util.ArrayList;
import java.util.List;

/**
 * Chat 归属 Session 列表的一页稳定分页结果。
 *
 * <p>{@code items} 按归属时间从新到旧（{@code (createTime desc, sessionId desc)}）排序；{@code nextCursor}
 * 非空表示还有下一页，原样回传即可继续翻页。
 */
@Data
public class ChatSessionPageDTO {

  private List<HarnessSessionSummaryDTO> items = new ArrayList<>();

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String nextCursor;
}
