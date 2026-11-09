package fun.fengwk.kkstudio.ide.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 切换 IDE view 目录的请求；dirty 文档由 Daemon 明确拒绝。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EnvironmentIdeChangeDirectoryDTO extends EnvironmentIdeViewBindingDTO {

  private String directory;
}
