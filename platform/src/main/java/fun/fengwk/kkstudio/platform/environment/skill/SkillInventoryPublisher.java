package fun.fengwk.kkstudio.platform.environment.skill;

import lombok.AllArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceSnapshot;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentStatus;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.skill.model.EnvironmentInventory;
import fun.fengwk.kkstudio.platform.environment.skill.model.SkillInventoryEntry;
import fun.fengwk.kkstudio.platform.environment.skill.model.SkillSource;
import fun.fengwk.kkstudio.platform.environment.skill.repo.SkillSourceRepository;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 把一次 READY 上报持久化为 Platform 的权威 inventory。
 *
 * <p>围栏分三层，任何一层不成立都不写入任何行：当前 {@code environment_connection} 行必须仍是同一 {@code ownerNodeId +
 * leaseToken} 且 READY 且租约未过期（数据库语句时间判定）；{@code capabilities.sourceSetVersion} 必须等于 {@code
 * environment_inventory.source_set_version}；每个快照的 {@code sourceId} 必须已配置，且 {@code sourceVersion}
 * 不得超过该来源的当前 {@code version}。集合代际相等时报告是当前代际的结论，因此 replay 幂等；代际已前进 说明报告描述的是被取代的集合，整份忽略而不是裁剪。
 *
 * <p>逐个快照与来源行版本比对：相等的快照原子替换该来源的 skill 行并把来源置 READY（写 applied_version/revision/诊断/
 * last_applied_at、清空 error）；更低的陈旧快照整体忽略，其旧行与旧状态原样保留。READY 未列出的来源保持现状，绝不因为"这次没报" 而被标成 READY 或清空。
 *
 * <p>锁顺序与来源 CRUD 一致：{@code environment}（key share）→ {@code environment_connection}（for share）→
 * {@code environment_inventory}（for update）→ {@code environment_skill_source}（for update，source_id
 * 升序）。
 */
@AllArgsConstructor
@Service
public class SkillInventoryPublisher {

  private static final String FENCE_SQL =
      """
      select environment_id
      from environment_connection
      where environment_id = ?
        and owner_node_id = ?
        and lease_token = ?
        and status = 'READY'
        and lease_until > statement_timestamp()
      for share
      """;

  private final EnvironmentRepository environmentRepository;
  private final SkillSourceRepository skillSourceRepository;
  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  /** 发布结果：{@link Outcome#APPLIED} 表示本次报告被接受并持久化，{@link Outcome#IGNORED} 表示整体无操作。 */
  public enum Outcome {
    APPLIED,
    IGNORED
  }

  /**
   * 持久化一次 READY 上报。
   *
   * @param connection 当前 READY 连接快照（含 EnvironmentId、ownerNodeId、leaseToken 与严格 capabilities）
   * @return 接受并持久化返回 {@link Outcome#APPLIED}；围栏不成立或报告自相矛盾返回 {@link Outcome#IGNORED}（无任何写入）
   */
  @Transactional
  public Outcome publish(EnvironmentConnection connection) {
    Objects.requireNonNull(connection, "connection");
    if (connection.status() != LiveEnvironmentStatus.READY
        || connection.daemonCapabilities() == null) {
      return Outcome.IGNORED;
    }
    EnvironmentId environmentId = connection.environmentId();
    UUID environmentValue = environmentId.value();
    if (environmentRepository.lockForKeyShare(environmentValue) == null) {
      return Outcome.IGNORED;
    }
    UUID fenced =
        jdbcTemplate
            .query(
                FENCE_SQL,
                (rs, rowNum) -> (UUID) rs.getObject("environment_id"),
                environmentValue,
                connection.ownerNodeId(),
                connection.leaseToken())
            .stream()
            .findFirst()
            .orElse(null);
    if (fenced == null) {
      return Outcome.IGNORED;
    }
    EnvironmentInventory inventory = skillSourceRepository.lockInventory(environmentValue);
    if (inventory == null) {
      return Outcome.IGNORED;
    }
    DaemonCapabilities capabilities = connection.daemonCapabilities();
    if (capabilities.sourceSetVersion() != inventory.getSourceSetVersion()) {
      return Outcome.IGNORED;
    }
    Map<UUID, SkillSource> sources = new HashMap<>();
    for (SkillSource source : skillSourceRepository.lockAllSources(environmentValue)) {
      sources.put(source.getSourceId(), source);
    }
    if (!isPublishable(capabilities, sources)) {
      return Outcome.IGNORED;
    }
    List<DaemonSkillSourceSnapshot> applicableSnapshots = new ArrayList<>();
    for (DaemonSkillSourceSnapshot snapshot : capabilities.skillSources()) {
      SkillSource source = sources.get(snapshot.sourceId());
      if (snapshot.sourceVersion() == source.getVersion()) {
        applicableSnapshots.add(snapshot);
      }
    }

    // 两阶段替换：第一阶段先删除所有已匹配来源现存的全部旧行；
    for (DaemonSkillSourceSnapshot snapshot : applicableSnapshots) {
      skillSourceRepository.deleteSourceSkills(environmentValue, snapshot.sourceId());
    }

    // 第二阶段再插入所有已匹配来源的新行并标记 READY。
    // 这样即便 Skill 在同一个上报中跨来源迁移（例如从 A 移到 B），也不会因来源处理顺序而触犯全局 (environment_id, name) 唯一键。
    for (DaemonSkillSourceSnapshot snapshot : applicableSnapshots) {
      List<SkillInventoryEntry> entries = toEntries(environmentValue, snapshot);
      skillSourceRepository.insertSkills(entries);
      if (!skillSourceRepository.markSourceReady(
          environmentValue,
          snapshot.sourceId(),
          snapshot.sourceVersion(),
          snapshot.sourceRevision(),
          snapshot.diagnostics())) {
        throw new IllegalStateException("mark skill source READY failed: " + snapshot.sourceId());
      }
    }
    if (!skillSourceRepository.applyReadyReport(
        environmentValue,
        capabilities.sourceSetVersion(),
        capabilities.version(),
        capabilities.environment().operatingSystem().wireValue(),
        capabilities.environment().timeZone(),
        capabilities.environment().note(),
        capabilities.environment().rootPath(),
        connection.ownerNodeId(),
        connection.leaseToken())) {
      throw new IllegalStateException("apply skill inventory report failed: " + environmentId);
    }
    return Outcome.APPLIED;
  }

  /** 全部候选检查：未知来源与被新配置超越的快照都让整份报告无效，绝不部分接受。 */
  private boolean isPublishable(DaemonCapabilities capabilities, Map<UUID, SkillSource> sources) {
    for (DaemonSkillSourceSnapshot snapshot : capabilities.skillSources()) {
      SkillSource source = sources.get(snapshot.sourceId());
      if (source == null || snapshot.sourceVersion() > source.getVersion()) {
        return false;
      }
    }
    return true;
  }

  private List<SkillInventoryEntry> toEntries(
      UUID environmentId, DaemonSkillSourceSnapshot snapshot) {
    List<SkillInventoryEntry> entries = new ArrayList<>(snapshot.skills().size());
    var discoveredAt = clock.instant();
    for (DaemonSkillDescriptor skill : snapshot.skills()) {
      SkillInventoryEntry entry = new SkillInventoryEntry();
      entry.setEnvironmentId(environmentId);
      entry.setSourceId(snapshot.sourceId());
      entry.setName(skill.name());
      entry.setSourceVersion(skill.sourceVersion());
      entry.setDescription(skill.description());
      entry.setBaseDirectory(skill.baseDirectory());
      entry.setContentRevision(skill.contentRevision());
      entry.setDiscoveredAt(discoveredAt);
      entries.add(entry);
    }
    return List.copyOf(entries);
  }
}
