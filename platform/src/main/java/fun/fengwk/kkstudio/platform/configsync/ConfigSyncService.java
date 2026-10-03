package fun.fengwk.kkstudio.platform.configsync;

import fun.fengwk.kkstudio.share.configsync.ConfigSyncExportDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncExportRequestDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncImportRequestDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncImportResultDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncInventoryDTO;

/** 设置同步的应用服务：inventory、YAML 导出与导入。 */
public interface ConfigSyncService {

  /** 返回全部可同步条目及其依赖闭包；不含配置值与凭据。 */
  ConfigSyncInventoryDTO inventory();

  /** 按选择补齐依赖闭包后导出完整 YAML（含凭据、registrationToken 与 MCP headers 原值）。 */
  ConfigSyncExportDTO export(ConfigSyncExportRequestDTO request);

  /** 解析、预校验并按事务整体导入 YAML；返回实际写入与明确跳过的条目。 */
  ConfigSyncImportResultDTO importYaml(ConfigSyncImportRequestDTO request);
}
