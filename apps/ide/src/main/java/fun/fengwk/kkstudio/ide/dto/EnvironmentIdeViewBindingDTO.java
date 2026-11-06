package fun.fengwk.kkstudio.ide.dto;

import lombok.Data;

/** 需要当前文档与绑定代际的视图操作；旧代际不可作用于新绑定。 */
@Data
public class EnvironmentIdeViewBindingDTO {

  private String documentId;
  private String bindingEpoch;
}
