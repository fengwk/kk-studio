package fun.fengwk.kkstudio.share.systemsettings;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

/**
 * System settings 的六个强类型 section 载体（tool / aiRuntime / environment / integrations / storageMedia /
 * advanced），是 GET 响应数据与 PUT 请求体的公共部分。
 *
 * <p>每个 section 都是必填对象；客户端必须发送完整聚合，缺失 section 或未知字段都会失败。仓库/Web 约定：{@code Long} 字段（时长 {@code
 * *Millis}、字节、秒数）在 HTTP wire 上输出规范非负十进制字符串（前端友好、bigint-safe），{@code Integer} 字段为 JSON 数值。秘密与
 * bootstrap 输入（DB/server 配置、filesystem 路径、key/token、 OpenCLI instanceId 等）不进入任何 DTO。
 */
@Data
public class SystemSettingsSectionsDTO {

  private SystemSettingsToolDTO tool;

  private SystemSettingsAiRuntimeDTO aiRuntime;

  private SystemSettingsEnvironmentDTO environment;

  private SystemSettingsIntegrationsDTO integrations;

  private SystemSettingsStorageMediaDTO storageMedia;

  private SystemSettingsAdvancedDTO advanced;

  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new IllegalArgumentException("unknown system settings field: " + name);
  }
}
