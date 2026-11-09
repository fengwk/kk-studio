package fun.fengwk.kkstudio.ide.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 关闭 IDE 视图：{@code discard} 必须由显式放弃操作给出。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EnvironmentIdeCloseViewDTO extends EnvironmentIdeViewBindingDTO {

  private boolean discard;
}
