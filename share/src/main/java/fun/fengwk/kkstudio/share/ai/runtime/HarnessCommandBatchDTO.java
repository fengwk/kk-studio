package fun.fengwk.kkstudio.share.ai.runtime;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.util.List;

/** 唯一产品用户命令写入口的请求 DTO。 */
@Data
public class HarnessCommandBatchDTO {

  /** Chat 或 Canvas owner。 */
  private HarnessCommandOwnerDTO owner;

  /** NEW_SESSION、ENTRY 或 THREAD target。 */
  private HarnessCommandTargetDTO target;

  /** 非空、按产品顺序排列的 SET_* + USER_MESSAGE 命令列表。 */
  private List<HarnessCommandCreateDTO> commands = List.of();

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new HarnessRequestFormatException("unknown command batch field: " + name);
  }
}
