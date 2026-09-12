package fun.fengwk.kkstudio.harness.daemon.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceConfig;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceSnapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 持久 Skill registry：发布语义、revision 精确加载、跨重启恢复与失败原子性。
 *
 * <p>测试意图：证明 registry 只暴露“完整发布后的目录”，旧冻结 revision 在正文保留期内始终可精确加载，任何失败都不会污染当前 目录，并且数据目录是唯一持久状态来源。
 */
class DaemonSkillRegistryTest {

  private static final UUID SOURCE_ID = DaemonSkillTestSupport.SOURCE_ID;
  private static final UUID OTHER_SOURCE_ID =
      UUID.fromString("44444444-4444-4444-4444-444444444444");

  /** 手工构造 manifest 时使用的 canonical SHA-256 内容 revision。 */
  private static final String CONTENT_REVISION = "a".repeat(64);

  @TempDir Path tempDir;

  @Test
  void publishesChildSkillsAndLoadsExactRevision() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("root"));
    writeSkill(root, "alpha", "Alpha skill", "# Alpha\n\nbody line\n");
    DaemonSkillRegistry registry = DaemonSkillTestSupport.open(tempDir.resolve("data"));

    DaemonSkillSourceSnapshot snapshot = registry.refresh(config(root, 1));

    assertEquals(SOURCE_ID, snapshot.sourceId());
    assertEquals(1, snapshot.sourceVersion());
    assertEquals(1, snapshot.skills().size());
    DaemonSkillDescriptor descriptor = snapshot.skills().get(0);
    assertEquals("alpha", descriptor.name());
    assertEquals("Alpha skill", descriptor.description());
    assertEquals(64, descriptor.contentRevision().length());

    DaemonSkillRegistry.LoadedSkill loaded =
        registry.load(SOURCE_ID, "alpha", descriptor.contentRevision()).orElseThrow();
    // 指令正文 = SKILL.md 去除 front matter。
    assertEquals("# Alpha\n\nbody line", loaded.body());
    assertEquals(descriptor.baseDirectory(), loaded.baseDirectory());
  }

  @Test
  void discoversRootSkillMdInConfiguredDir() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("root"));
    Files.writeString(
        root.resolve("SKILL.md"), "---\nname: root-skill\ndescription: Root\n---\n# Root\n");
    DaemonSkillRegistry registry = DaemonSkillTestSupport.open(tempDir.resolve("data"));

    DaemonSkillSourceSnapshot snapshot = registry.refresh(config(root, 1));

    assertEquals("root-skill", snapshot.skills().get(0).name());
    assertEquals(
        "# Root",
        registry
            .load(SOURCE_ID, "root-skill", snapshot.skills().get(0).contentRevision())
            .orElseThrow()
            .body());
  }

  /** 坏 Skill 必须变成有界 diagnostics 而不是发布失败：一个坏 entry 不影响同一来源内的健康 skill。 */
  @Test
  void reportsBadSkillsAsDiagnosticsWithoutFailingTheSource() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("root"));
    Path broken = Files.createDirectories(root.resolve("broken"));
    Files.writeString(broken.resolve("SKILL.md"), "---\nname: broken\n---\n");
    writeSkill(root, "healthy", "Healthy skill", "# Healthy\n");
    DaemonSkillRegistry registry = DaemonSkillTestSupport.open(tempDir.resolve("data"));

    DaemonSkillSourceSnapshot snapshot = registry.refresh(config(root, 1));

    assertEquals(1, snapshot.skills().size());
    assertEquals("healthy", snapshot.skills().get(0).name());
    assertEquals(1, snapshot.diagnostics().size());
    // 诊断只描述结构性原因，绝不回显 SKILL.md 正文。
    assertTrue(snapshot.diagnostics().get(0).message().contains("description"));
    assertFalse(snapshot.diagnostics().get(0).message().contains("---"));
  }

  /** 缺失的受管 PATH 来源必须显式失败，避免把“来源消失”伪装成“没有 skill”。 */
  @Test
  void rejectsMissingPathSource() {
    DaemonSkillRegistry registry = DaemonSkillTestSupport.open(tempDir.resolve("data"));
    Path missing = tempDir.resolve("does-not-exist");

    DaemonSkillException error =
        assertThrows(DaemonSkillException.class, () -> registry.refresh(config(missing, 1)));
    assertTrue(error.getMessage().contains("not an existing directory"));
  }

  /** 未发布的首启默认来源目录可以不存在：等价于当前没有可发现的 skill。 */
  @Test
  void emptyDefaultSourceDirectoryIsSuccessfulEmptySnapshot() {
    DaemonSkillRegistry registry = DaemonSkillTestSupport.open(tempDir.resolve("data"));
    Path missing = tempDir.resolve("default-skills");

    DaemonSkillSourceSnapshot snapshot =
        registry.refresh(
            DaemonSkillSourceConfig.path(
                SOURCE_ID, 1, 1, missing.toString(), true, Set.of(SOURCE_ID)));

    assertTrue(snapshot.skills().isEmpty());
    assertEquals(0, snapshot.diagnostics().size());
  }

  /** 同一来源重复 skill 名是发布失败，且失败后当前目录保持为空（不发布半成品）。 */
  @Test
  void rejectsDuplicateSkillNamesWithinOneSource() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("root"));
    Path first = Files.createDirectories(root.resolve("first"));
    Path second = Files.createDirectories(root.resolve("second"));
    Files.writeString(first.resolve("SKILL.md"), skillText("shared", "first"));
    Files.writeString(second.resolve("SKILL.md"), skillText("shared", "second"));
    DaemonSkillRegistry registry = DaemonSkillTestSupport.open(tempDir.resolve("data"));

    DaemonSkillException error =
        assertThrows(DaemonSkillException.class, () -> registry.refresh(config(root, 1)));
    assertTrue(error.getMessage().contains("duplicate skill name"));
    assertTrue(registry.snapshots().isEmpty());
  }

  /** 跨来源同名 skill 必须被拒绝，否则 Cloud 侧无法按 name 唯一定位。 */
  @Test
  void rejectsDuplicateSkillNamesAcrossSources() throws IOException {
    Path first = Files.createDirectories(tempDir.resolve("first-root"));
    Path second = Files.createDirectories(tempDir.resolve("second-root"));
    writeSkill(first, "shared", "first", "# first\n");
    writeSkill(second, "shared", "second", "# second\n");
    DaemonSkillRegistry registry = DaemonSkillTestSupport.open(tempDir.resolve("data"));
    registry.refresh(config(first, 1));

    DaemonSkillSourceConfig secondConfig =
        DaemonSkillSourceConfig.path(
            OTHER_SOURCE_ID, 1, 2, second.toString(), false, Set.of(SOURCE_ID, OTHER_SOURCE_ID));
    DaemonSkillException error =
        assertThrows(DaemonSkillException.class, () -> registry.refresh(secondConfig));

    assertTrue(error.getMessage().contains("duplicate skill name"));
    assertEquals(1, registry.snapshots().size());
  }

  /** 旧 revision 在正文保留期内必须可精确加载；未保留的 revision 返回空而不回退到当前版本。 */
  @Test
  void retainsPreviousRevisionAndRejectsUnknownRevision() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("root"));
    writeSkill(root, "alpha", "Alpha skill", "# Alpha v1\n");
    DaemonSkillRegistry registry = DaemonSkillTestSupport.open(tempDir.resolve("data"));
    String firstRevision = registry.refresh(config(root, 1)).skills().get(0).contentRevision();

    writeSkill(root, "alpha", "Alpha skill", "# Alpha v2\n");
    DaemonSkillSourceSnapshot updated = registry.refresh(config(root, 2));
    String secondRevision = updated.skills().get(0).contentRevision();
    assertNotEquals(firstRevision, secondRevision);

    assertEquals(
        "# Alpha v1", registry.load(SOURCE_ID, "alpha", firstRevision).orElseThrow().body());
    assertEquals(
        "# Alpha v2", registry.load(SOURCE_ID, "alpha", secondRevision).orElseThrow().body());
    assertTrue(registry.load(SOURCE_ID, "alpha", "0".repeat(64)).isEmpty());
    assertTrue(registry.load(OTHER_SOURCE_ID, "alpha", secondRevision).isEmpty());
  }

  /** manifest 是唯一持久状态：重新打开 registry 必须恢复上一份已验证的目录。 */
  @Test
  void restoresPublishedSnapshotFromDataDirectory() throws IOException {
    Path dataDir = tempDir.resolve("data");
    Path root = Files.createDirectories(tempDir.resolve("root"));
    writeSkill(root, "alpha", "Alpha skill", "# Alpha\n");
    DaemonSkillRegistry first = DaemonSkillRegistry.open(dataDir, tempDir);
    DaemonSkillSourceSnapshot published = first.refresh(config(root, 1));

    DaemonSkillRegistry reopened = DaemonSkillRegistry.open(dataDir, tempDir);

    assertEquals(List.of(published), reopened.snapshots());
    assertEquals(
        "# Alpha",
        reopened
            .load(SOURCE_ID, "alpha", published.skills().get(0).contentRevision())
            .orElseThrow()
            .body());
  }

  /** 发布失败（跨来源同名冲突）不得改动已持久化的 manifest 与后续重启结果。 */
  @Test
  void failedPublicationLeavesManifestUntouched() throws IOException {
    Path dataDir = tempDir.resolve("data");
    Path root = Files.createDirectories(tempDir.resolve("root"));
    writeSkill(root, "alpha", "Alpha skill", "# Alpha\n");
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(dataDir, tempDir);
    registry.refresh(config(root, 1));

    Path conflictingRoot = Files.createDirectories(tempDir.resolve("conflicting-root"));
    writeSkill(conflictingRoot, "alpha", "Conflicting skill", "# duplicate\n");
    DaemonSkillSourceConfig conflicting =
        DaemonSkillSourceConfig.path(
            OTHER_SOURCE_ID,
            1,
            2,
            conflictingRoot.toString(),
            false,
            Set.of(SOURCE_ID, OTHER_SOURCE_ID));
    assertThrows(DaemonSkillException.class, () -> registry.refresh(conflicting));

    DaemonSkillRegistry reopened = DaemonSkillRegistry.open(dataDir, tempDir);
    assertEquals(1, reopened.snapshots().size());
    assertEquals(SOURCE_ID, reopened.snapshots().get(0).sourceId());
    assertEquals(1, reopened.snapshots().get(0).sourceVersion());
  }

  /** activeSourceIds 之外的来源必须在成功发布时被裁剪，但 PATH 目录与正文保留不受影响。 */
  @Test
  void prunesSourcesBeyondActiveSourceIds() throws IOException {
    Path dataDir = tempDir.resolve("data");
    Path firstRoot = Files.createDirectories(tempDir.resolve("first-root"));
    Path secondRoot = Files.createDirectories(tempDir.resolve("second-root"));
    writeSkill(firstRoot, "alpha", "Alpha skill", "# Alpha\n");
    writeSkill(secondRoot, "beta", "Beta skill", "# Beta\n");
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(dataDir, tempDir);
    registry.refresh(config(firstRoot, 1, 1));
    registry.refresh(
        DaemonSkillSourceConfig.path(
            OTHER_SOURCE_ID,
            1,
            2,
            secondRoot.toString(),
            false,
            Set.of(SOURCE_ID, OTHER_SOURCE_ID)));
    assertEquals(2, registry.snapshots().size());

    registry.refresh(
        DaemonSkillSourceConfig.path(
            SOURCE_ID, 2, 3, firstRoot.toString(), false, Set.of(SOURCE_ID)));

    assertEquals(List.of(SOURCE_ID), registry.snapshots().stream().map(s -> s.sourceId()).toList());
    assertTrue(Files.isRegularFile(firstRoot.resolve("alpha/SKILL.md")));
    assertTrue(Files.isRegularFile(secondRoot.resolve("beta/SKILL.md")));
  }

  /** 发布是可重入的：成功发布后立刻读取自身快照列表必须看到候选快照。 */
  @Test
  void refreshIsSelfVisibleAfterPublication() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("root"));
    writeSkill(root, "alpha", "Alpha skill", "# Alpha\n");
    DaemonSkillRegistry registry = DaemonSkillTestSupport.open(tempDir.resolve("data"));

    registry.refresh(config(root, 1));

    assertEquals(1, registry.snapshots().size());
    assertEquals(1, registry.descriptors().size());
    assertFalse(registry.snapshots().get(0).skills().isEmpty());
  }

  /**
   * 延迟到达的旧集合操作绝不能裁剪掉更新的来源。
   *
   * <p>时序：A 以集合版本 1 发布 → B 以集合版本 2 发布 {A,B} → 延迟到达的 A/集合版本 1/{A}。若只听信 {@code activeSourceIds}，第三步会把
   * B 裁剪掉；集合版本单调性必须使第 3 步显式失败，且重开后 A+B 仍在。
   */
  @Test
  void staleActiveSetCannotPruneNewerSources() throws IOException {
    Path dataDir = tempDir.resolve("data");
    Path firstRoot = Files.createDirectories(tempDir.resolve("first-root"));
    Path secondRoot = Files.createDirectories(tempDir.resolve("second-root"));
    writeSkill(firstRoot, "alpha", "Alpha skill", "# Alpha\n");
    writeSkill(secondRoot, "beta", "Beta skill", "# Beta\n");
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(dataDir, tempDir);

    registry.refresh(config(firstRoot, 1, 1));
    registry.refresh(
        DaemonSkillSourceConfig.path(
            OTHER_SOURCE_ID,
            1,
            2,
            secondRoot.toString(),
            false,
            Set.of(SOURCE_ID, OTHER_SOURCE_ID)));
    assertEquals(2, registry.snapshots().size());

    // 延迟到达的旧操作：A 的 v1 与集合 v1/{A}，只落后于集合版本（来源版本与已发布相同，因此只有集合版本检查能拦住它）。
    DaemonSkillException error =
        assertThrows(DaemonSkillException.class, () -> registry.refresh(config(firstRoot, 1, 1)));

    assertFalse(error.getMessage().contains(firstRoot.toString()), error.getMessage());
    assertEquals(
        List.of(SOURCE_ID, OTHER_SOURCE_ID),
        registry.snapshots().stream().map(snapshot -> snapshot.sourceId()).toList());
    DaemonSkillRegistry reopened = DaemonSkillRegistry.open(dataDir, tempDir);
    assertEquals(
        List.of(SOURCE_ID, OTHER_SOURCE_ID),
        reopened.snapshots().stream().map(snapshot -> snapshot.sourceId()).toList());
    assertEquals(List.of("alpha", "beta"), skillNames(reopened));
  }

  /** 同一集合版本必须描述同一个集合：同版本但更窄的集合同样被拒绝，避免绕过单调性裁剪来源。 */
  @Test
  void sameSourceSetVersionMustDescribeTheSameSourceSet() throws IOException {
    Path firstRoot = Files.createDirectories(tempDir.resolve("first-root"));
    Path secondRoot = Files.createDirectories(tempDir.resolve("second-root"));
    writeSkill(firstRoot, "alpha", "Alpha skill", "# Alpha\n");
    writeSkill(secondRoot, "beta", "Beta skill", "# Beta\n");
    DaemonSkillRegistry registry = DaemonSkillTestSupport.open(tempDir.resolve("data"));

    registry.refresh(config(firstRoot, 1, 1));
    registry.refresh(
        DaemonSkillSourceConfig.path(
            OTHER_SOURCE_ID,
            1,
            2,
            secondRoot.toString(),
            false,
            Set.of(SOURCE_ID, OTHER_SOURCE_ID)));

    assertThrows(DaemonSkillException.class, () -> registry.refresh(config(firstRoot, 2, 2)));

    assertEquals(2, registry.snapshots().size());
  }

  /** 同一来源的旧行版本不得覆盖已发布的新行版本，避免回退到旧扫描结果。 */
  @Test
  void staleSameSourceVersionIsRejected() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("root"));
    writeSkill(root, "alpha", "Alpha skill", "# Alpha v1\n");
    DaemonSkillRegistry registry = DaemonSkillTestSupport.open(tempDir.resolve("data"));
    registry.refresh(config(root, 2, 2));

    assertThrows(DaemonSkillException.class, () -> registry.refresh(config(root, 1, 2)));

    assertEquals(2, registry.snapshots().get(0).sourceVersion());
  }

  /** 非当前历史遵循确定性的 FIFO 淘汰：最旧的记录先被移除，最近的历史仍在。 */
  @Test
  void evictsOldestNonCurrentHistoryDeterministically() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("root"));
    DaemonSkillRegistry registry = DaemonSkillTestSupport.open(tempDir.resolve("data"));
    List<String> revisions = new ArrayList<>();
    int total = DaemonSkillRegistry.MAX_RETAINED_SKILLS + 3;
    for (int index = 0; index < total; index++) {
      writeSkill(root, "alpha", "Alpha skill", "# Alpha v" + index + "\n");
      revisions.add(
          registry.refresh(config(root, index + 1, index + 1)).skills().get(0).contentRevision());
    }

    // 共 total = MAX + 3 条记录：当前 1 条 + 非当前 MAX + 2 条，因此最旧的 2 条被淘汰。
    for (int index = 0; index < 2; index++) {
      assertTrue(
          registry.load(SOURCE_ID, "alpha", revisions.get(index)).isEmpty(),
          "expected evicted history at index " + index);
    }
    for (int index = 2; index < total; index++) {
      assertEquals(
          "# Alpha v" + index,
          registry.load(SOURCE_ID, "alpha", revisions.get(index)).orElseThrow().body());
    }
    assertEquals(
        DaemonSkillRegistry.MAX_RETAINED_SKILLS + 1, retainedCount(tempDir.resolve("data")));
  }

  /** 当前 skill 数超过历史上限时全部当前 descriptor 仍必须可加载：上限只约束非当前历史。 */
  @Test
  void retainsEveryCurrentSkillBeyondTheHistoryLimit() throws IOException {
    Path root = Files.createDirectories(tempDir.resolve("root"));
    int currentCount = DaemonSkillRegistry.MAX_RETAINED_SKILLS + 4;
    for (int index = 0; index < currentCount; index++) {
      writeSkill(root, "skill-" + index, "Skill " + index, "# Skill " + index + "\n");
    }
    DaemonSkillRegistry registry = DaemonSkillTestSupport.open(tempDir.resolve("data"));

    DaemonSkillSourceSnapshot snapshot = registry.refresh(config(root, 1, 1));

    assertEquals(currentCount, snapshot.skills().size());
    for (DaemonSkillDescriptor skill : snapshot.skills()) {
      assertEquals(
          "# Skill " + skill.name().substring("skill-".length()),
          registry.load(SOURCE_ID, skill.name(), skill.contentRevision()).orElseThrow().body());
    }
  }

  /** manifest 与 blob 是两个事实：被保留记录的正文缺失时恢复必须失败，绝不进入半可用的 READY。 */
  @Test
  void openFailsClosedWhenARetainedBodyIsMissing() throws IOException {
    Path dataDir = tempDir.resolve("data");
    Path root = Files.createDirectories(tempDir.resolve("root"));
    writeSkill(root, "alpha", "Alpha skill", "# Alpha\n");
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(dataDir, tempDir);
    registry.refresh(config(root, 1, 1));
    deleteBodies(dataDir);

    DaemonSkillException error =
        assertThrows(DaemonSkillException.class, () -> DaemonSkillRegistry.open(dataDir, tempDir));
    assertFalse(error.getMessage().contains(dataDir.toString()), error.getMessage());
  }

  /** manifest 自身损坏（版本不符）必须显式失败，绝不静默降级为空目录。 */
  @Test
  void openFailsClosedWhenManifestIsCorrupt() throws IOException {
    Path dataDir = tempDir.resolve("data");
    Path root = Files.createDirectories(tempDir.resolve("root"));
    writeSkill(root, "alpha", "Alpha skill", "# Alpha\n");
    DaemonSkillRegistry registry = DaemonSkillRegistry.open(dataDir, tempDir);
    registry.refresh(config(root, 1, 1));

    Path manifest = dataDir.resolve("skills/manifest.json");
    Files.writeString(
        manifest,
        "{\"version\":99,\"sourceSetVersion\":1,\"activeSourceIds\":[],"
            + "\"sources\":[],\"retained\":[]}");

    assertThrows(DaemonSkillException.class, () -> DaemonSkillRegistry.open(dataDir, tempDir));
  }

  /** 恢复出的 manifest 必须能原样编码为 READY；超出来源或 Skill 上限不能拖到握手时才失败。 */
  @Test
  void manifestRejectsInventoriesBeyondReadyLimits() {
    List<UUID> tooManySourceIds = new ArrayList<>();
    for (int index = 0; index <= DaemonCapabilities.MAX_SOURCES; index++) {
      tooManySourceIds.add(new UUID(0L, index + 1L));
    }
    IllegalArgumentException sourceLimit =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new DaemonSkillManifest(
                    DaemonSkillManifest.VERSION, 1, tooManySourceIds, List.of(), List.of()));
    assertTrue(sourceLimit.getMessage().contains("READY protocol limit"));

    List<DaemonSkillDescriptor> tooManySkills = new ArrayList<>();
    for (int index = 0; index <= DaemonCapabilities.MAX_SKILLS; index++) {
      tooManySkills.add(
          new DaemonSkillDescriptor(
              SOURCE_ID,
              1,
              "skill-" + index,
              "Skill " + index,
              "/srv/skills/skill-" + index,
              CONTENT_REVISION));
    }
    DaemonSkillSourceSnapshot oversized =
        new DaemonSkillSourceSnapshot(SOURCE_ID, 1, CONTENT_REVISION, tooManySkills, List.of());
    IllegalArgumentException skillLimit =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new DaemonSkillManifest(
                    DaemonSkillManifest.VERSION,
                    1,
                    List.of(SOURCE_ID),
                    List.of(oversized),
                    List.of()));
    assertTrue(skillLimit.getMessage().contains("READY protocol limit"));
  }

  /**
   * manifest 解码必须拒绝自相矛盾的清单：缺失/不匹配的保留记录、重复身份、非 canonical identity 与损坏 JSON。
   *
   * <p>这些形状都能被手工构造出来（磁盘损坏或半截写入），若不拒绝，READY 会广告无法加载的当前 skill。
   */
  @Test
  void manifestRejectsSelfContradictoryContent() throws IOException {
    assertInvalidManifest(
        manifestJson(
                snapshotJson("22222222-2222-2222-2222-222222222222", 1, "alpha", CONTENT_REVISION),
                "")
            .replace(
                "\"activeSourceIds\":[\"33333333-3333-3333-3333-333333333333\"]",
                "\"activeSourceIds\":[]"),
        "已发布来源必须属于生效集合");
    assertInvalidManifest(
        manifestJson(
                snapshotJson("33333333-3333-3333-3333-333333333333", 1, "alpha", CONTENT_REVISION),
                "")
            .replace(
                "\"retained\":[]",
                "\"retained\":[{\"sourceId\":\"33333333-3333-3333-3333-333333333333\","
                    + "\"name\":\"alpha\",\"revision\":\""
                    + CONTENT_REVISION
                    + "\","
                    + "\"baseDirectory\":\"/srv/other\"}]"),
        "保留记录的 baseDirectory 必须与当前 descriptor 一致");
    assertInvalidManifest(
        manifestJson(
                snapshotJson("33333333-3333-3333-3333-333333333333", 1, "alpha", CONTENT_REVISION),
                "")
            .replace(
                "\"retained\":[]",
                "\"retained\":[{\"sourceId\":\"33333333-3333-3333-3333-333333333333\","
                    + "\"name\":\"beta\",\"revision\":\""
                    + CONTENT_REVISION
                    + "\","
                    + "\"baseDirectory\":\"/srv/skills/alpha\"}]"),
        "保留记录的 name 必须与当前 descriptor 一致");
    assertInvalidManifest(
        "{\"version\":1,\"sourceSetVersion\":1,\"activeSourceIds\":[],\"sources\":[],"
            + "\"retained\":[{"
            + "\"sourceId\":\"33333333-3333-3333-3333-333333333333\",\"name\":\"alpha\","
            + "\"revision\":\""
            + CONTENT_REVISION
            + "\",\"baseDirectory\":\"/srv/skills/alpha\"},"
            + "{\"sourceId\":\"33333333-3333-3333-3333-333333333333\",\"name\":\"alpha\","
            + "\"revision\":\""
            + CONTENT_REVISION
            + "\",\"baseDirectory\":\"/srv/skills/alpha\"}]}",
        "重复保留身份必须被拒绝");
    assertInvalidManifest(
        "{\"version\":1,\"sourceSetVersion\":-1,\"activeSourceIds\":[],\"sources\":[],"
            + "\"retained\":[]}",
        "负 sourceSetVersion 必须被拒绝");
    assertInvalidManifest(
        "{\"version\":1,\"sourceSetVersion\":1,\"activeSourceIds\":[],\"sources\":[],"
            + "\"retained\":[{"
            + "\"sourceId\":\"33333333-3333-3333-3333-333333333333\",\"name\":\"alpha\","
            + "\"revision\":\"not-a-revision\",\"baseDirectory\":\"/srv/skills/alpha\"}]}",
        "非 canonical revision 必须被拒绝");
    assertInvalidManifest(
        "{\"version\":1,\"sourceSetVersion\":1,\"activeSourceIds\":[],\"sources\":[],"
            + "\"retained\":[{"
            + "\"sourceId\":\"33333333-3333-3333-3333-333333333333\",\"name\":\"alpha\","
            + "\"revision\":\""
            + CONTENT_REVISION
            + "\",\"baseDirectory\":\"relative/skills\"}]}",
        "非绝对 baseDirectory 必须被拒绝");
    assertInvalidManifest("{", "损坏 JSON 必须被拒绝");
  }

  /** 同一来源在同一份清单中出现两次必须被拒绝：来源 ID 全局唯一是 READY 与裁剪的前提。 */
  @Test
  void manifestRejectsDuplicateSourceIds() {
    String source =
        snapshotJson("33333333-3333-3333-3333-333333333333", 1, "alpha", CONTENT_REVISION);
    assertInvalidManifest(
        "{\"version\":1,\"sourceSetVersion\":1,"
            + "\"activeSourceIds\":[\"33333333-3333-3333-3333-333333333333\"],"
            + "\"sources\":["
            + source
            + ","
            + source
            + "],\"retained\":[]}",
        "重复来源 ID 必须被拒绝");
  }

  /** 跨来源同名 skill 必须被拒绝：READY 与 skill.load 都按名称唯一定位。 */
  @Test
  void manifestRejectsDuplicateGlobalSkillNames() {
    assertInvalidManifest(
        "{\"version\":1,\"sourceSetVersion\":1,"
            + "\"activeSourceIds\":[\"33333333-3333-3333-3333-333333333333\","
            + "\"44444444-4444-4444-4444-444444444444\"],\"sources\":["
            + snapshotJson("33333333-3333-3333-3333-333333333333", 1, "shared", CONTENT_REVISION)
            + ","
            + snapshotJson("44444444-4444-4444-4444-444444444444", 1, "shared", CONTENT_REVISION)
            + "],\"retained\":[]}",
        "跨来源同名 skill 必须被拒绝");
  }

  /** 非 canonical 保留身份必须被拒绝；这些形状只可能来自损坏或手工篡改。 */
  @Test
  void manifestRejectsMalformedIdentities() {
    assertInvalidManifest(
        "{\"version\":1,\"sourceSetVersion\":1,\"activeSourceIds\":[],\"sources\":[],"
            + "\"retained\":[{"
            + "\"sourceId\":\"33333333-3333-3333-3333-33333333333Z\",\"name\":\"alpha\","
            + "\"revision\":\""
            + CONTENT_REVISION
            + "\",\"baseDirectory\":\"/srv/skills/alpha\"}]}",
        "非 UUID sourceId 必须被拒绝");
    assertInvalidManifest(
        "{\"version\":1,\"sourceSetVersion\":1,\"activeSourceIds\":[],\"sources\":[],"
            + "\"retained\":[{"
            + "\"sourceId\":\"33333333-3333-3333-3333-333333333333\",\"name\":\"alpha\","
            + "\"revision\":\""
            + CONTENT_REVISION.toUpperCase()
            + "\",\"baseDirectory\":\"/srv/skills/alpha\"}]}",
        "大写 revision 必须被拒绝");
  }

  private static void assertInvalidManifest(String json, String intent) {
    assertThrows(
        DaemonSkillException.class,
        () -> {
          Path dataDir = Files.createTempDirectory("manifest-corrupt");
          Path skills = Files.createDirectories(dataDir.resolve("skills"));
          Files.createDirectories(skills.resolve("bodies"));
          Files.createDirectories(skills.resolve("checkouts"));
          Files.createDirectories(skills.resolve("staging"));
          Files.writeString(skills.resolve("manifest.json"), json);
          try {
            DaemonSkillRegistry.open(dataDir, dataDir);
          } finally {
            deleteRecursively(dataDir);
          }
        },
        intent);
  }

  private static String manifestJson(String snapshotJson, String retainedJson) {
    return "{\"version\":1,\"sourceSetVersion\":1,"
        + "\"activeSourceIds\":[\"33333333-3333-3333-3333-333333333333\"],"
        + "\"sources\":["
        + snapshotJson
        + "],\"retained\":["
        + retainedJson
        + "]}";
  }

  private static String snapshotJson(
      String sourceId, long sourceVersion, String name, String contentRevision) {
    return "{\"sourceId\":\""
        + sourceId
        + "\",\"sourceVersion\":"
        + sourceVersion
        + ",\"sourceRevision\":\""
        + CONTENT_REVISION
        + "\",\"skills\":[{\"sourceId\":\""
        + sourceId
        + "\",\"sourceVersion\":"
        + sourceVersion
        + ",\"name\":\""
        + name
        + "\",\"description\":\"desc\",\"baseDirectory\":\"/srv/skills/"
        + name
        + "\",\"contentRevision\":\""
        + contentRevision
        + "\"}],\"diagnostics\":[]}";
  }

  private static long retainedCount(Path dataDir) throws IOException {
    String manifest = Files.readString(dataDir.resolve("skills/manifest.json"));
    int index = manifest.indexOf("\"retained\":[");
    long count = 0;
    boolean inObject = false;
    for (int cursor = index; cursor < manifest.length(); cursor++) {
      char ch = manifest.charAt(cursor);
      if (ch == '{') {
        inObject = true;
      } else if (ch == '}') {
        if (inObject) {
          count++;
        }
        inObject = false;
      }
    }
    return count;
  }

  private static void deleteBodies(Path dataDir) throws IOException {
    Path bodies = dataDir.resolve("skills/bodies");
    if (!Files.isDirectory(bodies)) {
      return;
    }
    try (var entries = Files.list(bodies)) {
      for (Path entry : entries.toList()) {
        Files.deleteIfExists(entry);
      }
    }
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (var walk = Files.walk(root)) {
      for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  /** 常规用法：行版本与全局来源集合版本同序前进。 */
  private static List<String> skillNames(DaemonSkillRegistry registry) {
    return registry.descriptors().stream().map(skill -> skill.name()).toList();
  }

  private static DaemonSkillSourceConfig config(Path root, long sourceVersion) {
    return config(root, sourceVersion, sourceVersion);
  }

  /** 显式区分行版本与集合版本，用于构造“集合已前进但本操作仍携带旧集合”的场景。 */
  private static DaemonSkillSourceConfig config(
      Path root, long sourceVersion, long sourceSetVersion) {
    return DaemonSkillSourceConfig.path(
        SOURCE_ID, sourceVersion, sourceSetVersion, root.toString(), false, Set.of(SOURCE_ID));
  }

  private void writeSkill(Path root, String name, String description, String body)
      throws IOException {
    Path skill = Files.createDirectories(root.resolve(name));
    Files.writeString(
        skill.resolve("SKILL.md"),
        "---\nname: " + name + "\ndescription: " + description + "\n---\n" + body);
  }

  private static String skillText(String name, String description) {
    return "---\nname: " + name + "\ndescription: " + description + "\n---\n# " + name + "\n";
  }
}
