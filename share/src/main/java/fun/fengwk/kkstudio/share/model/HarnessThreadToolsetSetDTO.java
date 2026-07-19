package fun.fengwk.kkstudio.share.model;

import lombok.Data;

import java.util.List;

/** 排队 SET_TOOLSET 输入。 */
@Data
public class HarnessThreadToolsetSetDTO {
  private List<String> tools;
  private String clientMessageId;
}
