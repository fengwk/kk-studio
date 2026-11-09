package fun.fengwk.kkstudio.ide.dto;

import lombok.Data;

/** 需要浏览器会话、绑定代际与 Daemon 进程代次的视图操作；旧代际不可作用于新绑定。 */
@Data
public class EnvironmentIdeViewBindingDTO {

  private String browserSessionId;
  private Long bindingEpoch;
  private String expectedDaemonInstanceId;
}
