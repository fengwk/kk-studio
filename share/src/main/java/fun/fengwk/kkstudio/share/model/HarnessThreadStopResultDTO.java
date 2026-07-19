package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.util.List;

/** Stop durable 回执与可恢复草稿。 */
@Data
public class HarnessThreadStopResultDTO {
  private String stopId;
  private List<HarnessThreadInputDTO> cancelledInputs;
  private List<String> restoredMessages;
}
