package fun.fengwk.kkstudio.ide.dto;

import lombok.Data;

/** 关闭 IDE 视图：必须携带当前文档/代际，{@code discard} 必须由显式放弃操作给出。 */
@Data
public class EnvironmentIdeCloseViewDTO {

  private String documentId;
  private String bindingEpoch;
  private boolean discard;
}
