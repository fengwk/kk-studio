package fun.fengwk.kkstudio.share.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Session Entry 的一页稳定 keyset 结果。
 *
 * <p>{@code items} 按存储追加顺序（创建顺序）升序，父节点必先于子节点出现；{@code nextCursor} 非空时原样回传即可继续翻页，null 表示已到末页。
 */
@Data
public class HarnessSessionEntryPageDTO {

  private List<HarnessSessionEntryDTO> items = new ArrayList<>();

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private @Nullable String nextCursor;
}
