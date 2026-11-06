package fun.fengwk.kkstudio.platform.configsync.dto;

import lombok.Data;

/** 导入预检查请求；服务端不记录 YAML 内容。 */
@Data
public class ConfigSyncImportCheckRequestDTO {

  private String yaml;
}
