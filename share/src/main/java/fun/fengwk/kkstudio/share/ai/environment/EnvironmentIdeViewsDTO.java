package fun.fengwk.kkstudio.share.ai.environment;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.util.List;

/** owner 的当前视图快照；用于恢复路由与租约核对，不创建视图也不续租。 */
@Data
public class EnvironmentIdeViewsDTO {

  private List<EnvironmentIdeViewDTO> views;

  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object value) {
    throw new IllegalArgumentException("unknown environment ide views field: " + fieldName);
  }
}
