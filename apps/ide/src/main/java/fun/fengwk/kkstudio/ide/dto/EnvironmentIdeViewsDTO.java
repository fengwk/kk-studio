package fun.fengwk.kkstudio.ide.dto;

import lombok.Data;

import java.util.List;

/** 一个 Daemon 进程的 owner 视图快照；revision 与 views 在同一提交点冻结，不创建视图也不续租。 */
@Data
public class EnvironmentIdeViewsDTO {

  private String daemonInstanceId;
  private Long revision;
  private List<EnvironmentIdeViewDTO> views;
}
