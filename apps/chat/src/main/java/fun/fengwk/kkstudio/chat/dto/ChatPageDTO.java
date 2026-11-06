package fun.fengwk.kkstudio.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话列表的一页稳定分页结果。
 *
 * <p>{@code items} 按 {@code (updatedAt desc, createdAt desc, title asc, id asc)} 排序；{@code
 * nextCursor} 非空表示还有下一页，原样回传即可继续翻页。
 */
@Data
public class ChatPageDTO {

  private List<ChatDTO> items = new ArrayList<>();

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String nextCursor;
}
