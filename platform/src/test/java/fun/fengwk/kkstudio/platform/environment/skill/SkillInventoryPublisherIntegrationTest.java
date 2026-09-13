package fun.fengwk.kkstudio.platform.environment.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDiagnostic;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceSnapshot;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentStatus;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentService;
import fun.fengwk.kkstudio.platform.environment.skill.model.EnvironmentInventory;
import fun.fengwk.kkstudio.platform.environment.skill.model.SkillInventoryEntry;
import fun.fengwk.kkstudio.platform.environment.skill.repo.SkillSourceRepository;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInventoryDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillSourceCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillSourceDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillSourceUpdateDTO;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** READY 发布、持久 inventory 对账、围栏保护与持久查询在真实 PostgreSQL 上的契约。 */
class SkillInventoryPublisherIntegrationTest extends PostgresSpringTestSupport {

  private static final String DEFAULT_SOURCE_PATH = "~/.agents/skills";
  private static final String REVISION_A = "0123456789abcdef0123456789abcdef01234567";
  private static final String REVISION_B = "abcdef0123456789abcdef0123456789abcdef01";
  private static final String CONTENT_REV = "0".repeat(64);

  @Autowired private EnvironmentService environmentService;
  @Autowired private EnvironmentSkillSourceService sourceService;
  @Autowired private SkillSourceRepository skillSourceRepository;
  @Autowired private SkillInventoryPublisher publisher;
  @Autowired private EnvironmentSkillInventoryQueryService queryService;
  @Autowired private JdbcTemplate jdbcTemplate;

  private EnvironmentId environmentId;
  private UUID defaultSourceId;
  private UUID ownerNodeId;
  private UUID leaseToken;

  @BeforeEach
  void setUp() {
    EnvironmentCreateDTO create = new EnvironmentCreateDTO();
    create.setName("publisher-env-" + System.nanoTime());
    environmentId = EnvironmentId.of(UUID.fromString(environmentService.create(create).getId()));

    List<EnvironmentSkillSourceDTO> sources = sourceService.list(environmentId);
    defaultSourceId = UUID.fromString(sources.get(0).getSourceId());

    ownerNodeId = UUID.randomUUID();
    leaseToken = UUID.randomUUID();
    insertLiveConnection(ownerNodeId, leaseToken, "READY", 300);
  }

  /** 意图：合法连接、当前代际与配置版本完全匹配时，发布被接受并持久化。 */
  @Test
  void publishAppliesReportAtomically() {
    DaemonSkillDescriptor descriptor =
        new DaemonSkillDescriptor(
            defaultSourceId, 0L, "default-skill", "desc", "/home/dev/skills/default", CONTENT_REV);
    DaemonSkillDiagnostic diagnostic = new DaemonSkillDiagnostic("/home/dev/skills", "healthy");
    DaemonSkillSourceSnapshot snapshot =
        new DaemonSkillSourceSnapshot(
            defaultSourceId, 0L, REVISION_A, List.of(descriptor), List.of(diagnostic));
    DaemonCapabilities capabilities =
        new DaemonCapabilities(
            DaemonCapabilities.VERSION,
            new DaemonEnvironmentInfo(
                DaemonOperatingSystem.LINUX, "UTC", "Linux environment.", "/home/dev"),
            0L,
            List.of(snapshot));
    EnvironmentConnection conn = readyConnection(capabilities);

    SkillInventoryPublisher.Outcome outcome = publisher.publish(conn);

    assertEquals(SkillInventoryPublisher.Outcome.APPLIED, outcome);

    EnvironmentSkillSourceDTO source = sourceService.get(environmentId, defaultSourceId);
    assertEquals("READY", source.getStatus());
    assertEquals("0", source.getAppliedVersion());
    assertEquals(REVISION_A, source.getAppliedRevision());
    assertEquals(1, source.getDiagnostics().size());
    assertEquals("healthy", source.getDiagnostics().get(0).getMessage());
    assertNull(source.getLastErrorCode());

    EnvironmentInventory inventory = skillSourceRepository.getInventory(environmentId.value());
    assertNotNull(inventory);
    assertEquals(0L, inventory.getSourceSetVersion());
    assertEquals(0L, inventory.getAppliedSourceSetVersion());
    assertEquals("/home/dev", inventory.getRootPath());
    assertEquals("linux", inventory.getOperatingSystem());

    List<SkillInventoryEntry> usable =
        skillSourceRepository.listUsableSkills(environmentId.value());
    assertEquals(1, usable.size());
    assertEquals("default-skill", usable.get(0).getName());
  }

  /** 意图：五重围栏校验——ownerNodeId、leaseToken、状态非 READY、租约已过期以及内存连接状态不一致时，报告整体被忽略。 */
  @Test
  void fenceRejectsMismatchedOrExpiredOrConnectingState() {
    DaemonCapabilities capabilities = minimalCapabilities(0L, List.of());

    // 1. ownerNodeId 不匹配
    EnvironmentConnection wrongOwner =
        new EnvironmentConnection(
            environmentId,
            UUID.randomUUID(),
            leaseToken,
            LiveEnvironmentStatus.READY,
            capabilities,
            Instant.now(),
            Instant.now().plusSeconds(300));
    assertEquals(SkillInventoryPublisher.Outcome.IGNORED, publisher.publish(wrongOwner));

    // 2. leaseToken 不匹配
    EnvironmentConnection wrongToken =
        new EnvironmentConnection(
            environmentId,
            ownerNodeId,
            UUID.randomUUID(),
            LiveEnvironmentStatus.READY,
            capabilities,
            Instant.now(),
            Instant.now().plusSeconds(300));
    assertEquals(SkillInventoryPublisher.Outcome.IGNORED, publisher.publish(wrongToken));

    // 3. 连接行状态非 READY (修改 DB 连接行为 CONNECTING)
    jdbcTemplate.update(
        "update environment_connection set status = 'CONNECTING', runtime_info = null where environment_id = ?",
        environmentId.value());
    assertEquals(
        SkillInventoryPublisher.Outcome.IGNORED, publisher.publish(readyConnection(capabilities)));
    // 恢复 READY
    jdbcTemplate.update(
        "update environment_connection set status = 'READY', runtime_info = '{}'::jsonb where environment_id = ?",
        environmentId.value());

    // 4. 租约已过期 (修改 DB 租约到过去)
    jdbcTemplate.update(
        "update environment_connection set last_seen_at = current_timestamp - interval '10 minutes', "
            + "lease_until = current_timestamp - interval '5 minutes' where environment_id = ?",
        environmentId.value());
    assertEquals(
        SkillInventoryPublisher.Outcome.IGNORED, publisher.publish(readyConnection(capabilities)));
    // 恢复租约
    jdbcTemplate.update(
        "update environment_connection set lease_until = current_timestamp + interval '5 minutes' where environment_id = ?",
        environmentId.value());

    // 5. connection 对象自身状态不是 READY
    EnvironmentConnection connectingConn =
        new EnvironmentConnection(
            environmentId,
            ownerNodeId,
            leaseToken,
            LiveEnvironmentStatus.CONNECTING,
            capabilities,
            Instant.now(),
            Instant.now().plusSeconds(300));
    assertEquals(SkillInventoryPublisher.Outcome.IGNORED, publisher.publish(connectingConn));
  }

  /** 意图：报告中的 sourceSetVersion 与当前 inventory.source_set_version 不一致时整份报告忽略。 */
  @Test
  void sourceSetMismatchIgnoresWholeReport() {
    // 报告带旧的 sourceSetVersion (0)，但当前已推进为 1
    sourceService.create(environmentId, pathSource("/srv/extra"));

    DaemonCapabilities capabilities = minimalCapabilities(0L, List.of());
    assertEquals(
        SkillInventoryPublisher.Outcome.IGNORED, publisher.publish(readyConnection(capabilities)));

    EnvironmentInventory inventory = skillSourceRepository.getInventory(environmentId.value());
    assertNull(inventory.getAppliedSourceSetVersion());
    assertNull(inventory.getRootPath());
  }

  /** 意图：快照中包含未知来源或未来版本来源时，整份报告拒绝（无任何写入）。 */
  @Test
  void unknownOrFutureSourceRejectsWholeReport() {
    // 未知来源
    UUID unknownSourceId = UUID.randomUUID();
    DaemonSkillSourceSnapshot unknownSnapshot =
        new DaemonSkillSourceSnapshot(unknownSourceId, 0L, REVISION_A, List.of(), List.of());
    DaemonCapabilities capUnknown = minimalCapabilities(0L, List.of(unknownSnapshot));
    assertEquals(
        SkillInventoryPublisher.Outcome.IGNORED, publisher.publish(readyConnection(capUnknown)));

    // 未来版本来源（当前是 0，快照上报 5）
    DaemonSkillSourceSnapshot futureSnapshot =
        new DaemonSkillSourceSnapshot(defaultSourceId, 5L, REVISION_A, List.of(), List.of());
    DaemonCapabilities capFuture = minimalCapabilities(0L, List.of(futureSnapshot));
    assertEquals(
        SkillInventoryPublisher.Outcome.IGNORED, publisher.publish(readyConnection(capFuture)));

    assertEquals("UNAPPLIED", sourceService.get(environmentId, defaultSourceId).getStatus());
  }

  /** 意图：快照版本低于当前配置版本时被忽略，相等版本应用，不在快照中的来源保持现状。 */
  @Test
  void lowerSourceVersionIgnoredEqualSourceAppliedAbsentSourceUnchanged() {
    // 增加来源 B
    EnvironmentSkillSourceDTO sourceB = sourceService.create(environmentId, pathSource("/srv/b"));
    UUID sourceBId = UUID.fromString(sourceB.getSourceId());
    long currentSetVersion =
        skillSourceRepository.getInventory(environmentId.value()).getSourceSetVersion();

    // 推进 defaultSource 到版本 1
    EnvironmentSkillSourceUpdateDTO update = new EnvironmentSkillSourceUpdateDTO();
    update.setType("path");
    update.setPath("/srv/default-v1");
    update.setExpectedVersion("0");
    sourceService.update(environmentId, defaultSourceId, update);

    // 上报：defaultSource 仍是旧版本 0（陈旧快照），sourceB 是当前版本 0（相等快照）
    DaemonSkillDescriptor skillB =
        new DaemonSkillDescriptor(sourceBId, 0L, "skill-b", "b", "/srv/b/b", CONTENT_REV);
    DaemonSkillSourceSnapshot snapAStale =
        new DaemonSkillSourceSnapshot(defaultSourceId, 0L, REVISION_A, List.of(), List.of());
    DaemonSkillSourceSnapshot snapBEqual =
        new DaemonSkillSourceSnapshot(sourceBId, 0L, REVISION_B, List.of(skillB), List.of());

    DaemonCapabilities capabilities =
        minimalCapabilities(currentSetVersion, List.of(snapAStale, snapBEqual));

    SkillInventoryPublisher.Outcome outcome = publisher.publish(readyConnection(capabilities));
    assertEquals(SkillInventoryPublisher.Outcome.APPLIED, outcome);

    // defaultSource: 因版本过低被跳过，保持 UNAPPLIED
    EnvironmentSkillSourceDTO currentA = sourceService.get(environmentId, defaultSourceId);
    assertEquals("UNAPPLIED", currentA.getStatus());
    assertNull(currentA.getAppliedVersion());

    // sourceB: 版本相等，被应用为 READY
    EnvironmentSkillSourceDTO currentB = sourceService.get(environmentId, sourceBId);
    assertEquals("READY", currentB.getStatus());
    assertEquals("0", currentB.getAppliedVersion());

    // 可用技能只有 skill-b
    List<SkillInventoryEntry> usable =
        skillSourceRepository.listUsableSkills(environmentId.value());
    assertEquals(1, usable.size());
    assertEquals("skill-b", usable.get(0).getName());
  }

  /** 意图：幂等重放——相同报告再次发布结果一致且不报错。 */
  @Test
  void idempotentReplay() {
    DaemonSkillDescriptor descriptor =
        new DaemonSkillDescriptor(
            defaultSourceId, 0L, "skill-replay", "desc", "/home/dev/skills/replay", CONTENT_REV);
    DaemonSkillSourceSnapshot snapshot =
        new DaemonSkillSourceSnapshot(
            defaultSourceId, 0L, REVISION_A, List.of(descriptor), List.of());
    DaemonCapabilities capabilities = minimalCapabilities(0L, List.of(snapshot));
    EnvironmentConnection conn = readyConnection(capabilities);

    assertEquals(SkillInventoryPublisher.Outcome.APPLIED, publisher.publish(conn));
    assertEquals(SkillInventoryPublisher.Outcome.APPLIED, publisher.publish(conn));

    List<SkillInventoryEntry> skills = skillSourceRepository.listSkills(environmentId.value());
    assertEquals(1, skills.size());
    assertEquals("skill-replay", skills.get(0).getName());
  }

  /** 意图：当新来源上报的 Skill 名称与已存在来源的 Skill 名称冲突时，数据库全局唯一约束生效触发原子回滚，新来源状态保持 UNAPPLIED。 */
  @Test
  void duplicateGlobalSkillNameTriggersAtomicRollback() {
    // 先发布 defaultSource，包含技能 "conflict-name"
    DaemonSkillDescriptor skillA =
        new DaemonSkillDescriptor(
            defaultSourceId, 0L, "conflict-name", "desc-a", "/srv/a", CONTENT_REV);
    DaemonSkillSourceSnapshot snapA =
        new DaemonSkillSourceSnapshot(defaultSourceId, 0L, REVISION_A, List.of(skillA), List.of());
    assertEquals(
        SkillInventoryPublisher.Outcome.APPLIED,
        publisher.publish(readyConnection(minimalCapabilities(0L, List.of(snapA)))));

    // 新增来源 B
    EnvironmentSkillSourceDTO sourceB = sourceService.create(environmentId, pathSource("/srv/b"));
    UUID sourceBId = UUID.fromString(sourceB.getSourceId());
    long currentSetVersion =
        skillSourceRepository.getInventory(environmentId.value()).getSourceSetVersion();

    // 来源 B 上报同名技能 "conflict-name"，且报告中不包含 defaultSource（避免 DaemonCapabilities 内存去重拦截）
    DaemonSkillDescriptor skillB =
        new DaemonSkillDescriptor(sourceBId, 0L, "conflict-name", "desc-b", "/srv/b", CONTENT_REV);
    DaemonSkillSourceSnapshot snapB =
        new DaemonSkillSourceSnapshot(sourceBId, 0L, REVISION_B, List.of(skillB), List.of());
    DaemonCapabilities capabilities = minimalCapabilities(currentSetVersion, List.of(snapB));

    // 数据库全局 (environment_id, name) 唯一约束拦截，事务回滚
    assertThrows(Exception.class, () -> publisher.publish(readyConnection(capabilities)));

    // 确认原子回滚：来源 B 保持 UNAPPLIED，未被持久化；defaultSource 与其已有技能完好无损
    assertEquals("UNAPPLIED", sourceService.get(environmentId, sourceBId).getStatus());
    assertEquals("READY", sourceService.get(environmentId, defaultSourceId).getStatus());
    List<SkillInventoryEntry> skills = skillSourceRepository.listSkills(environmentId.value());
    assertEquals(1, skills.size());
    assertEquals(defaultSourceId, skills.get(0).getSourceId());
    assertEquals("conflict-name", skills.get(0).getName());
  }

  /**
   * 意图（回归测试）：同一份上报中某个 Skill 从来源 A 迁移到来源 B。 快照列表顺序即使是 [B, A]（B 在 A 之前插入），两阶段先删后插也保证不会因 A
   * 处旧行未删而触犯唯一键约束。
   */
  @Test
  void skillMovesFromSourceAToSourceBInOneReportWithBFirstOrder() {
    EnvironmentSkillSourceDTO sourceB = sourceService.create(environmentId, pathSource("/srv/b"));
    UUID sourceBId = UUID.fromString(sourceB.getSourceId());
    long setVersion =
        skillSourceRepository.getInventory(environmentId.value()).getSourceSetVersion();

    // 初始状态：Skill "migrating-skill" 归属于 defaultSource (来源 A)
    DaemonSkillDescriptor initialSkill =
        new DaemonSkillDescriptor(
            defaultSourceId, 0L, "migrating-skill", "orig", "/srv/a/skill", CONTENT_REV);
    DaemonSkillSourceSnapshot initialSnapA =
        new DaemonSkillSourceSnapshot(
            defaultSourceId, 0L, REVISION_A, List.of(initialSkill), List.of());
    DaemonSkillSourceSnapshot initialSnapB =
        new DaemonSkillSourceSnapshot(sourceBId, 0L, REVISION_B, List.of(), List.of());

    assertEquals(
        SkillInventoryPublisher.Outcome.APPLIED,
        publisher.publish(
            readyConnection(minimalCapabilities(setVersion, List.of(initialSnapA, initialSnapB)))));

    // 验证初始状态在 A 下
    List<SkillInventoryEntry> initialEntries =
        skillSourceRepository.listSkills(environmentId.value());
    assertEquals(1, initialEntries.size());
    assertEquals(defaultSourceId, initialEntries.get(0).getSourceId());
    assertEquals("migrating-skill", initialEntries.get(0).getName());

    // 第二次上报：Skill "migrating-skill" 从 A 迁移至 B！
    // 故意将快照列表组织为 [B, A]，若单阶段循环处理，B 会先尝试 insert，与 A 现存行冲突
    DaemonSkillDescriptor movedSkill =
        new DaemonSkillDescriptor(
            sourceBId, 0L, "migrating-skill", "new location", "/srv/b/skill", CONTENT_REV);
    DaemonSkillSourceSnapshot reportSnapB =
        new DaemonSkillSourceSnapshot(sourceBId, 0L, REVISION_B, List.of(movedSkill), List.of());
    DaemonSkillSourceSnapshot reportSnapA =
        new DaemonSkillSourceSnapshot(defaultSourceId, 0L, REVISION_A, List.of(), List.of());

    SkillInventoryPublisher.Outcome outcome =
        publisher.publish(
            readyConnection(minimalCapabilities(setVersion, List.of(reportSnapB, reportSnapA))));

    assertEquals(SkillInventoryPublisher.Outcome.APPLIED, outcome);

    // 验证技能已成功移至来源 B
    List<SkillInventoryEntry> afterEntries =
        skillSourceRepository.listSkills(environmentId.value());
    assertEquals(1, afterEntries.size());
    assertEquals(sourceBId, afterEntries.get(0).getSourceId());
    assertEquals("migrating-skill", afterEntries.get(0).getName());
    assertEquals("/srv/b/skill", afterEntries.get(0).getBaseDirectory());
  }

  /** 意图：离线状态下，查询服务依然能够读取持久 inventory 与可用 Skill。 */
  @Test
  void durableInventoryQueriesWorkOffline() {
    DaemonSkillDescriptor skill =
        new DaemonSkillDescriptor(
            defaultSourceId, 0L, "offline-usable", "desc", "/srv/skills", CONTENT_REV);
    DaemonSkillSourceSnapshot snap =
        new DaemonSkillSourceSnapshot(defaultSourceId, 0L, REVISION_A, List.of(skill), List.of());
    publisher.publish(readyConnection(minimalCapabilities(0L, List.of(snap))));

    // 模拟离线：删除连接行
    jdbcTemplate.update(
        "delete from environment_connection where environment_id = ?", environmentId.value());

    // 离线查询
    EnvironmentInventoryDTO inventoryDto = queryService.getInventory(environmentId);
    assertEquals("0", inventoryDto.getSourceSetVersion());
    assertEquals("0", inventoryDto.getAppliedSourceSetVersion());
    assertEquals("/home/dev", inventoryDto.getRootPath());

    List<EnvironmentSkillDTO> usable = queryService.listUsableSkills(environmentId);
    assertEquals(1, usable.size());
    assertEquals("offline-usable", usable.get(0).getName());
    assertEquals("/srv/skills", usable.get(0).getBaseDirectory());

    assertEquals("/home/dev", queryService.findReportedRootPath(environmentId));
  }

  /** 意图（并发锁顺序）：来源增删改与 READY 发布并发竞争同一 Environment 时，按全局锁顺序执行，无死锁且结果原子。 */
  @Test
  void concurrencyBetweenMembershipMutationAndReadyPublicationNoDeadlock() throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      Callable<Boolean> mutationTask =
          () -> {
            start.await(5, TimeUnit.SECONDS);
            sourceService.create(environmentId, pathSource("/srv/concurrent-extra"));
            return true;
          };

      Callable<SkillInventoryPublisher.Outcome> publishTask =
          () -> {
            start.await(5, TimeUnit.SECONDS);
            DaemonSkillDescriptor desc =
                new DaemonSkillDescriptor(
                    defaultSourceId, 0L, "racing-skill", "desc", "/home/dev/racing", CONTENT_REV);
            DaemonSkillSourceSnapshot snap =
                new DaemonSkillSourceSnapshot(
                    defaultSourceId, 0L, REVISION_A, List.of(desc), List.of());
            // 使用代际 0 尝试发布
            DaemonCapabilities cap = minimalCapabilities(0L, List.of(snap));
            return publisher.publish(readyConnection(cap));
          };

      Future<Boolean> mutationFuture = executor.submit(mutationTask);
      Future<SkillInventoryPublisher.Outcome> publishFuture = executor.submit(publishTask);

      start.countDown();

      Boolean mutated = mutationFuture.get(10, TimeUnit.SECONDS);
      SkillInventoryPublisher.Outcome outcome = publishFuture.get(10, TimeUnit.SECONDS);

      assertTrue(mutated);
      assertNotNull(outcome);
      // 若 publish 先获得锁，则 APPLIED；若 mutation 先获得锁推进了 sourceSetVersion，则 publish 拿到
      // IGNORED。两者皆合法，关键是无死锁产生。
    } finally {
      executor.shutdownNow();
    }
  }

  /** 意图：markSourceFailed 版本围栏当前 vs 陈旧版本行为、错误格式校验及不泄密契约。 */
  @Test
  void markSourceFailedCurrentVsStaleAndNoLeak() {
    // 1. 当前版本 0 成功置 FAILED
    boolean success =
        skillSourceRepository.markSourceFailed(
            environmentId.value(), defaultSourceId, 0L, "IO_ERROR", "Read failure");
    assertTrue(success);

    EnvironmentSkillSourceDTO source = sourceService.get(environmentId, defaultSourceId);
    assertEquals("FAILED", source.getStatus());
    assertEquals("IO_ERROR", source.getLastErrorCode());
    assertEquals("Read failure", source.getLastErrorMessage());

    // 2. 陈旧版本 0 无操作（若将版本推进到 1）
    EnvironmentSkillSourceUpdateDTO update = new EnvironmentSkillSourceUpdateDTO();
    update.setType("path");
    update.setPath("/srv/skills-v1");
    update.setExpectedVersion("0");
    sourceService.update(environmentId, defaultSourceId, update);

    boolean staleAttempt =
        skillSourceRepository.markSourceFailed(
            environmentId.value(), defaultSourceId, 0L, "STALE_ERROR", "stale message");
    assertFalse(staleAttempt, "陈旧版本必须无操作返回 false");

    EnvironmentSkillSourceDTO reread = sourceService.get(environmentId, defaultSourceId);
    assertEquals("UNAPPLIED", reread.getStatus(), "编辑后是 UNAPPLIED，陈旧失败更新未生效");

    // 3. 错误码格式校验（UPPER_SNAKE）：非法格式结构化拒绝且不泄密
    IllegalArgumentException lowerEx =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                skillSourceRepository.markSourceFailed(
                    environmentId.value(), defaultSourceId, 1L, "lowercase_error", "msg"));
    assertEquals("invalid error code", lowerEx.getMessage());
    assertNull(lowerEx.getCause());

    IllegalArgumentException digitFirstEx =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                skillSourceRepository.markSourceFailed(
                    environmentId.value(), defaultSourceId, 1L, "123_INVALID", "msg"));
    assertEquals("invalid error code", digitFirstEx.getMessage());
    assertNull(digitFirstEx.getCause());

    IllegalArgumentException dashEx =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                skillSourceRepository.markSourceFailed(
                    environmentId.value(), defaultSourceId, 1L, "ERR-WITH-DASH", "msg"));
    assertEquals("invalid error code", dashEx.getMessage());
    assertNull(dashEx.getCause());

    // 4. 消息格式校验（控制字符/超长）
    assertThrows(
        IllegalArgumentException.class,
        () ->
            skillSourceRepository.markSourceFailed(
                environmentId.value(), defaultSourceId, 1L, "SAFE_CODE", "msg\u0000bad"));
  }

  private void insertLiveConnection(UUID ownerNode, UUID token, String status, int leaseSeconds) {
    jdbcTemplate.update(
        "insert into environment_connection ("
            + "environment_id, owner_node_id, lease_token, status, runtime_info, last_seen_at, lease_until"
            + ") values (?, ?, ?, ?, '{\"env\":\"test\"}'::jsonb, current_timestamp, current_timestamp + (interval '1 second' * ?)) "
            + "on conflict (environment_id) do update set owner_node_id = excluded.owner_node_id, "
            + "lease_token = excluded.lease_token, status = excluded.status, lease_until = excluded.lease_until",
        environmentId.value(),
        ownerNode,
        token,
        status,
        leaseSeconds);
  }

  private EnvironmentConnection readyConnection(DaemonCapabilities capabilities) {
    return new EnvironmentConnection(
        environmentId,
        ownerNodeId,
        leaseToken,
        LiveEnvironmentStatus.READY,
        capabilities,
        Instant.now(),
        Instant.now().plusSeconds(300));
  }

  private DaemonCapabilities minimalCapabilities(
      long sourceSetVersion, List<DaemonSkillSourceSnapshot> snapshots) {
    return new DaemonCapabilities(
        DaemonCapabilities.VERSION,
        new DaemonEnvironmentInfo(
            DaemonOperatingSystem.LINUX, "UTC", "Linux environment.", "/home/dev"),
        sourceSetVersion,
        snapshots);
  }

  private EnvironmentSkillSourceCreateDTO pathSource(String path) {
    EnvironmentSkillSourceCreateDTO create = new EnvironmentSkillSourceCreateDTO();
    create.setType("path");
    create.setPath(path);
    return create;
  }
}
