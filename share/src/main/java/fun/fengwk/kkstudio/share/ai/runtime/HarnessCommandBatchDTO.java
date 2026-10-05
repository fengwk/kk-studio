package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.util.List;

/** 唯一产品用户创建型命令写入口的请求 DTO：只承载 NEW_SESSION / NEW_THREAD。 */
@Data
public class HarnessCommandBatchDTO {

  /** Chat 或 Canvas owner。 */
  private HarnessCommandOwnerDTO owner;

  /** NEW_SESSION 或 NEW_THREAD 创建 target；既有 Thread 的继续写入见 {@link HarnessThreadCommandBatchDTO}。 */
  private HarnessCommandTargetDTO target;

  /** 非空、按产品顺序排列的 SET_* + USER_MESSAGE 命令列表。 */
  private List<HarnessCommandCreateDTO> commands = List.of();

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new HarnessRequestFormatException("unknown command batch field: " + name);
  }
}
