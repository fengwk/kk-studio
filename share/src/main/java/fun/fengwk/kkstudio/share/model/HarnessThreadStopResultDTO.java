package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.util.List;

/** Stop 结果：新 executionEpoch 与本次取消的 Input。 */
@Data
public class HarnessThreadStopResultDTO {
  private Long executionEpoch;
  private List<HarnessThreadInputDTO> cancelledInputs;
}
