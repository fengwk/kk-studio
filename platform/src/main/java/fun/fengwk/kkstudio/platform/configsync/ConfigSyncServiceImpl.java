package fun.fengwk.kkstudio.platform.configsync;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.share.configsync.ConfigSyncExportDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncExportRequestDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncImportRequestDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncImportResultDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncInventoryDTO;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncItem;
import fun.fengwk.kkstudio.share.configsync.ConfigSyncRef;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置同步编排：inventory / export 在单一只读快照上完成，确保跨集合一致；import 把校验与外部准备放在事务外，写事务委托给
 * {@link ConfigSyncApplier}。
 */
@AllArgsConstructor
@Service
public class ConfigSyncServiceImpl implements ConfigSyncService {

  private final ConfigSyncSnapshotReader snapshotReader;
  private final ConfigSyncGraph graph;
  private final ConfigSyncExporter exporter;
  private final ConfigSyncParser parser;
  private final ConfigSyncPlanner planner;
  private final ConfigSyncApplier applier;

  @Override
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ConfigSyncInventoryDTO inventory() {
    ConfigSyncSnapshot snapshot = snapshotReader.read();
    List<ConfigSyncItem> items = new ArrayList<>();
    for (ConfigSyncRef ref : graph.allRefs(snapshot)) {
      items.add(new ConfigSyncItem(ref.getKind(), ref.getName(), graph.closure(snapshot, ref)));
    }
    return new ConfigSyncInventoryDTO(items);
  }

  @Override
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public ConfigSyncExportDTO export(ConfigSyncExportRequestDTO request) {
    List<ConfigSyncRef> selected = request == null ? List.of() : request.getItems();
    ConfigSyncSnapshot snapshot = snapshotReader.read();
    List<ConfigSyncRef> expanded = graph.expand(snapshot, selected);
    return new ConfigSyncExportDTO(exporter.export(snapshot, expanded));
  }

  @Override
  public ConfigSyncImportResultDTO importYaml(ConfigSyncImportRequestDTO request) {
    String yaml = request == null ? null : request.getYaml();
    ConfigSyncParser.ParsedDocument document = parser.parse(yaml);
    ConfigSyncPlan plan = planner.plan(document);
    applier.apply(plan);
    return new ConfigSyncImportResultDTO(plan.imported(), plan.skipped());
  }
}
