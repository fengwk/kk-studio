package fun.fengwk.kkstudio.platform.configsync;

import fun.fengwk.kkstudio.share.configsync.ConfigSyncExportDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncExportRequestDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncImportCheckDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncImportCheckRequestDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncImportRequestDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncImportResultDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncInventoryDTO;

/** 设置同步的应用服务：inventory、YAML 导出与导入。 */
public interface ConfigSyncService {

  /** 返回全部可同步条目及其依赖闭包；不含配置值与凭据。 */
  ConfigSyncInventoryDTO inventory();

  /** 按选择补齐依赖闭包后导出完整 YAML（含凭据、registrationToken 与 MCP headers 原值）。 */
  ConfigSyncExportDTO export(ConfigSyncExportRequestDTO request);

  /** 不写数据库，预览将新增、覆盖与跳过的条目；非法文件直接拒绝。 */
  ConfigSyncImportCheckDTO checkImport(ConfigSyncImportCheckRequestDTO request);

  /** 重新校验后事务导入；跳过项必须获得显式部分导入授权。 */
  ConfigSyncImportResultDTO importYaml(ConfigSyncImportRequestDTO request);
}
