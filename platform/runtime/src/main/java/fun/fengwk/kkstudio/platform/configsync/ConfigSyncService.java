package fun.fengwk.kkstudio.platform.configsync;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncExportDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncExportRequestDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncImportCheckDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncImportCheckRequestDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncImportRequestDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncImportResultDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncInventoryDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncItem;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncRef;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 设置同步编排：inventory / export 在单一只读快照上完成，确保跨集合一致；import 把校验与外部准备放在事务外，写事务委托给 {@link
 * ConfigSyncApplier}。
 */
@AllArgsConstructor
@Service
public class ConfigSyncService {

  private static final String RESOURCE = "config_sync";

  private final ConfigSyncSnapshotReader snapshotReader;
  private final ConfigSyncGraph graph;
  private final ConfigSyncExporter exporter;
  private final ConfigSyncParser parser;
  private final ConfigSyncPlanner planner;
  private final ConfigSyncApplier applier;

  /** 返回全部可同步条目及其依赖闭包；不含配置值与凭据。 */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ConfigSyncInventoryDTO inventory() {
    ConfigSyncSnapshot snapshot = snapshotReader.read();
    ConfigSyncGraph.Graph prepared = graph.graph(snapshot);
    List<ConfigSyncItem> items = new ArrayList<>();
    for (ConfigSyncRef ref : prepared.allRefs()) {
      items.add(new ConfigSyncItem(ref.getKind(), ref.getName(), graph.closure(prepared, ref)));
    }
    return new ConfigSyncInventoryDTO(items);
  }

  /** 按选择补齐依赖闭包后导出完整 YAML（含凭据与 MCP headers 原值、Environment 凭证摘要）。 */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ConfigSyncExportDTO export(ConfigSyncExportRequestDTO request) {
    List<ConfigSyncRef> selected = request == null ? null : request.getItems();
    ConfigSyncSnapshot snapshot = snapshotReader.read();
    ConfigSyncGraph.Graph prepared = graph.graph(snapshot);
    List<ConfigSyncRef> expanded = graph.expand(prepared, selected);
    return new ConfigSyncExportDTO(exporter.export(snapshot, expanded));
  }

  /** 不写数据库，预览将新增、覆盖与跳过的条目；非法文件直接拒绝。 */
  public ConfigSyncImportCheckDTO checkImport(ConfigSyncImportCheckRequestDTO request) {
    ConfigSyncParser.ParsedDocument document =
        parser.parse(request == null ? null : request.getYaml());
    ConfigSyncSnapshot snapshot = snapshotReader.read();
    ConfigSyncPlan plan = planner.plan(document, snapshot);
    Set<ConfigSyncRef> existing = new HashSet<>(graph.allRefs(snapshot));
    List<ConfigSyncRef> created = new ArrayList<>();
    List<ConfigSyncRef> updated = new ArrayList<>();
    for (ConfigSyncRef ref : plan.imported()) {
      if (existing.contains(ref)) {
        updated.add(ref);
      } else {
        created.add(ref);
      }
    }
    return new ConfigSyncImportCheckDTO(created, updated, plan.skipped());
  }

  /** 重新校验后事务导入；跳过项必须获得显式部分导入授权。 */
  public ConfigSyncImportResultDTO importYaml(ConfigSyncImportRequestDTO request) {
    String yaml = request == null ? null : request.getYaml();
    ConfigSyncParser.ParsedDocument document = parser.parse(yaml);
    ConfigSyncPlan plan = planner.plan(document);
    if (!plan.skipped().isEmpty() && !request.isAllowPartial()) {
      throw new AiValidationException(RESOURCE, "partial import requires explicit confirmation");
    }
    if (plan.imported().isEmpty()) {
      return new ConfigSyncImportResultDTO(plan.imported(), plan.skipped());
    }
    try {
      applier.apply(plan);
    } catch (AiValidationException error) {
      // 已知校验失败：不透传可能包含真实值的下游 DTO/服务错误文本（尤其凭据片段），只给安全导入错误；
      // unexpected 异常与数据库 cause 不回显，也不被吞。
      throw new AiValidationException(RESOURCE, "config import is invalid");
    }
    return new ConfigSyncImportResultDTO(plan.imported(), plan.skipped());
  }
}
