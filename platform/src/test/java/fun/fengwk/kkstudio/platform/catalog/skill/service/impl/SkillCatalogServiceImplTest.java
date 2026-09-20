package fun.fengwk.kkstudio.platform.catalog.skill.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitCache;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitException;
import fun.fengwk.kkstudio.platform.catalog.skill.repo.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.service.converter.SkillCatalogConverter;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageCheckDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageCreateDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageEditDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackagePublishDTO;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill Package 单表 catalog 的契约测试。
 *
 * <p>冻结四条关键语义：Create 发布当时解析出的 branch HEAD；编辑与发布只在事实变化时推进 version；Check 失败完整保留 current commit 与
 * manifest；发布只接受等于当前观察值的 exact commit，且移除仍被 Agent {@code SkillRef} 引用的 Skill 一律拒绝。
 */
public class SkillCatalogServiceImplTest {

  private static final String REPOSITORY_URL = "https://git.example.com/skills.git";
  private static final String OLD_COMMIT = "a".repeat(40);
  private static final String NEW_COMMIT = "b".repeat(40);

  /** 测试意图：Create 解析 branch HEAD 并把它作为首个 current commit 与 manifest 一起发布。 */
  @Test
  public void shouldPublishResolvedBranchHeadOnCreate() {
    Fixture fixture = new Fixture();
    fixture.git.resolveHead(REPOSITORY_URL, "main", OLD_COMMIT);
    fixture.git.manifest(OLD_COMMIT, List.of(entry("dev", "developer skill")));

    SkillPackageDTO created = fixture.service.createPackage(create("dev-package"));

    assertEquals("dev-package", created.getPackageName());
    assertEquals(REPOSITORY_URL, created.getRepositoryUrl());
    assertEquals("main", created.getBranch());
    assertEquals(OLD_COMMIT, created.getCurrentCommit());
    assertEquals(OLD_COMMIT, created.getObservedHeadCommit());
    assertEquals("UP_TO_DATE", created.getCheckStatus());
    assertEquals("0", created.getVersion());
    assertEquals(List.of("dev"), created.getSkills().stream().map(s -> s.getName()).toList());
    // Create 必须把该 exact commit 物化进 cache，而不是只记录事实。
    assertEquals(List.of(OLD_COMMIT), fixture.git.ensuredCommits);
  }

  /** 测试意图：同名 Package 或非法请求体直接拒绝，不产生半成品行。 */
  @Test
  public void shouldRejectDuplicateAndInvalidCreate() {
    Fixture fixture = new Fixture();
    fixture.git.resolveHead(REPOSITORY_URL, "main", OLD_COMMIT);
    fixture.git.manifest(OLD_COMMIT, List.of(entry("dev", "developer skill")));
    fixture.service.createPackage(create("dev-package"));

    assertThrows(
        AiDuplicateException.class, () -> fixture.service.createPackage(create("dev-package")));
    assertThrows(
        AiValidationException.class,
        () -> fixture.service.createPackage(createWithUrl("git@example.com:skills.git")));
    assertThrows(
        AiValidationException.class,
        () ->
            fixture.service.createPackage(createWithUrl("https://user:secret@example.com/s.git")));
    assertThrows(
        AiValidationException.class,
        () -> {
          SkillPackageCreateDTO dto = create("other-package");
          dto.setBranch("  ");
          fixture.service.createPackage(dto);
        });
  }

  /** 测试意图：branch HEAD 无法解析或 manifest 非法时，Create 必须整体失败且不落任何行。 */
  @Test
  public void shouldRejectCreateWhenRepositoryOrManifestIsUnusable() {
    Fixture fixture = new Fixture();
    fixture.git.failHead(new SkillGitException("remote branch not found: main"));

    assertThrows(
        AiValidationException.class, () -> fixture.service.createPackage(create("dev-package")));
    assertTrue(fixture.repository.packages.isEmpty());

    Fixture brokenManifest = new Fixture();
    brokenManifest.git.resolveHead(REPOSITORY_URL, "main", OLD_COMMIT);
    brokenManifest.git.failManifest(new SkillGitException("skill dev has no SKILL.md frontmatter"));

    assertThrows(
        AiValidationException.class,
        () -> brokenManifest.service.createPackage(create("dev-package")));
    assertTrue(brokenManifest.repository.packages.isEmpty());
  }

  /** 测试意图：编辑只改 description/branch；同值请求是 no-op，不推进 version，也不触碰已发布内容。 */
  @Test
  public void shouldEditEditableFieldsOnlyAndSkipUnchangedFacts() {
    Fixture fixture = new Fixture();
    SkillPackageDTO created = fixture.createPublished("dev-package");

    SkillPackageEditDTO edit = new SkillPackageEditDTO();
    edit.setExpectedVersion(created.getVersion());
    edit.setDescription("new description");
    edit.setBranch("release");
    SkillPackageDTO edited = fixture.service.editPackage("dev-package", edit);

    assertEquals("1", edited.getVersion());
    assertEquals("new description", edited.getDescription());
    assertEquals("release", edited.getBranch());
    // repository URL 与已发布内容都不可由编辑改变。
    assertEquals(REPOSITORY_URL, edited.getRepositoryUrl());
    assertEquals(OLD_COMMIT, edited.getCurrentCommit());

    SkillPackageEditDTO noop = new SkillPackageEditDTO();
    noop.setExpectedVersion(edited.getVersion());
    noop.setDescription("new description");
    noop.setBranch("release");
    SkillPackageDTO unchanged = fixture.service.editPackage("dev-package", noop);

    assertEquals("1", unchanged.getVersion());
    assertThrows(
        AiVersionConflictException.class,
        () -> {
          SkillPackageEditDTO stale = new SkillPackageEditDTO();
          stale.setExpectedVersion(created.getVersion());
          stale.setDescription("another");
          stale.setBranch("main");
          fixture.service.editPackage("dev-package", stale);
        });
  }

  /** 测试意图：Check 只更新观察三元组，成功时保留 current commit 与 manifest，失败时保留全部已发布事实。 */
  @Test
  public void shouldCheckWithoutTouchingPublishedContent() {
    Fixture fixture = new Fixture();
    SkillPackageDTO created = fixture.createPublished("dev-package");

    fixture.git.resolveHead(REPOSITORY_URL, "main", NEW_COMMIT);
    SkillPackageCheckDTO check = check(created.getVersion());
    SkillPackageDTO available = fixture.service.checkPackage("dev-package", check);

    assertEquals("1", available.getVersion());
    assertEquals(NEW_COMMIT, available.getObservedHeadCommit());
    assertEquals("UPDATE_AVAILABLE", available.getCheckStatus());
    assertEquals(OLD_COMMIT, available.getCurrentCommit());
    assertEquals(List.of("dev"), available.getSkills().stream().map(s -> s.getName()).toList());

    // 同一观察结果再次检查不推进 version：只读检查不制造写放大与无谓的 Card 失效。
    SkillPackageDTO same =
        fixture.service.checkPackage("dev-package", check(available.getVersion()));
    assertEquals("1", same.getVersion());

    // 检查失败：有界错误进入 Card，已发布内容与上一次成功观察值保持原文。
    fixture.git.failHead(new SkillGitException("remote unreachable"));
    SkillPackageDTO failed = fixture.service.checkPackage("dev-package", check(same.getVersion()));

    assertEquals("2", failed.getVersion());
    assertEquals("CHECK_FAILED", failed.getCheckStatus());
    assertEquals(OLD_COMMIT, failed.getCurrentCommit());
    assertEquals(NEW_COMMIT, failed.getObservedHeadCommit());
    assertFalse(failed.getHeadCheckError().isBlank());
  }

  /** 测试意图：发布只接受等于当前观察值的 exact commit，并原子替换 current commit 与 manifest。 */
  @Test
  public void shouldPublishOnlyObservedExactCommit() {
    Fixture fixture = new Fixture();
    SkillPackageDTO created = fixture.createPublished("dev-package");
    fixture.git.resolveHead(REPOSITORY_URL, "main", NEW_COMMIT);
    SkillPackageDTO observed =
        fixture.service.checkPackage("dev-package", check(created.getVersion()));

    // branch 之后又前进到第三个 commit：Card 未展示的 HEAD 绝不被暗中发布。
    String advancedCommit = "c".repeat(40);
    fixture.git.resolveHead(REPOSITORY_URL, "main", advancedCommit);
    SkillPackagePublishDTO stale = publish(observed.getVersion(), advancedCommit);
    assertThrows(
        AiValidationException.class, () -> fixture.service.updatePackage("dev-package", stale));

    fixture.git.manifest(
        NEW_COMMIT, List.of(entry("dev", "v2"), entry("review", "reviewer skill")));
    SkillPackagePublishDTO publish = publish(observed.getVersion(), NEW_COMMIT);
    SkillPackageDTO published = fixture.service.updatePackage("dev-package", publish);

    assertEquals("2", published.getVersion());
    assertEquals(NEW_COMMIT, published.getCurrentCommit());
    assertEquals(NEW_COMMIT, published.getObservedHeadCommit());
    assertNull(published.getHeadCheckError());
    assertEquals("UP_TO_DATE", published.getCheckStatus());
    assertEquals(
        List.of("dev", "review"), published.getSkills().stream().map(s -> s.getName()).toList());
    // 发布必须物化目标 commit 后重新扫描，绝不复用上一次的 manifest。
    assertEquals(List.of(OLD_COMMIT, NEW_COMMIT), fixture.git.ensuredCommits);
  }

  /** 测试意图：重复发布同一个已发布的 commit 且 manifest 未变化时是 no-op，不推进 version。 */
  @Test
  public void shouldSkipPublishWhenFactsUnchanged() {
    Fixture fixture = new Fixture();
    SkillPackageDTO created = fixture.createPublished("dev-package");
    fixture.git.manifest(OLD_COMMIT, List.of(entry("dev", "developer skill")));

    SkillPackageDTO republished =
        fixture.service.updatePackage("dev-package", publish(created.getVersion(), OLD_COMMIT));

    assertEquals("0", republished.getVersion());
    assertEquals(OLD_COMMIT, republished.getCurrentCommit());
  }

  /** 测试意图：发布移除仍被 Agent {@code SkillRef} 引用的 Skill 时拒绝，且不改变已发布内容。 */
  @Test
  public void shouldRejectPublishRemovingReferencedSkill() {
    Fixture fixture = new Fixture();
    SkillPackageDTO created = fixture.createPublished("dev-package");
    fixture.referenced("dev-package", "dev");
    fixture.git.resolveHead(REPOSITORY_URL, "main", NEW_COMMIT);
    SkillPackageDTO observed =
        fixture.service.checkPackage("dev-package", check(created.getVersion()));
    fixture.git.manifest(NEW_COMMIT, List.of(entry("review", "reviewer skill")));

    assertThrows(
        AiInUseException.class,
        () ->
            fixture.service.updatePackage(
                "dev-package", publish(observed.getVersion(), NEW_COMMIT)));
    assertEquals(1L, fixture.repository.getPackage("dev-package").getVersion());
    assertEquals(OLD_COMMIT, fixture.repository.getPackage("dev-package").getCurrentCommit());
  }

  /** 测试意图：删除被引用的 Package 一律拒绝，未被引用时按 expectedVersion 删除，陈旧版本返回冲突。 */
  @Test
  public void shouldGuardDeleteByAgentReferencesAndVersion() {
    Fixture fixture = new Fixture();
    SkillPackageDTO referenced = fixture.createPublished("dev-package");
    fixture.referenced("dev-package", "dev");

    assertThrows(
        AiInUseException.class,
        () -> fixture.service.deletePackage("dev-package", referenced.getVersion()));
    assertTrue(fixture.repository.packages.containsKey("dev-package"));

    Fixture free = new Fixture();
    SkillPackageDTO deletable = free.createPublished("dev-package");
    free.service.deletePackage("dev-package", deletable.getVersion());
    assertFalse(free.repository.packages.containsKey("dev-package"));
    assertThrows(AiResourceNotFoundException.class, () -> free.service.getPackage("dev-package"));
  }

  /** 测试意图：删除与发布的 CAS 都必须以客户端展示的 version 为准，陈旧请求统一 version conflict。 */
  @Test
  public void shouldRejectStaleVersions() {
    Fixture fixture = new Fixture();
    SkillPackageDTO created = fixture.createPublished("dev-package");
    fixture.git.resolveHead(REPOSITORY_URL, "main", NEW_COMMIT);
    fixture.service.checkPackage("dev-package", check(created.getVersion()));

    assertThrows(
        AiVersionConflictException.class,
        () -> fixture.service.deletePackage("dev-package", created.getVersion()));
    assertThrows(
        AiVersionConflictException.class,
        () -> {
          SkillPackageEditDTO edit = new SkillPackageEditDTO();
          edit.setExpectedVersion(created.getVersion());
          edit.setDescription("stale edit");
          edit.setBranch("main");
          fixture.service.editPackage("dev-package", edit);
        });
  }

  /** 测试意图：从未检查过的行投影为 UNCHECKED，且列表按 package 名升序。 */
  @Test
  public void shouldProjectUncheckedStatusAndListOrder() {
    Fixture fixture = new Fixture();
    fixture.repository.seed(unchecked("zeta-package"));
    fixture.repository.seed(unchecked("alpha-package"));

    SkillPackageDTO unchecked = fixture.service.getPackage("zeta-package");
    assertNull(unchecked.getObservedHeadCommit());
    assertNull(unchecked.getHeadCheckedAt());
    assertEquals("UNCHECKED", unchecked.getCheckStatus());

    assertEquals(
        List.of("alpha-package", "zeta-package"),
        fixture.service.listPackages().stream().map(SkillPackageDTO::getPackageName).toList());
  }

  /** 测试意图：不存在的 Package 读取返回 not found。 */
  @Test
  public void shouldRejectMissingPackage() {
    Fixture fixture = new Fixture();
    assertThrows(AiResourceNotFoundException.class, () -> fixture.service.getPackage("missing"));
  }

  /** 测试意图：请求体缺 expectedVersion 时必须在任何写操作前失败。 */
  @Test
  public void shouldRequireExpectedVersion() {
    Fixture fixture = new Fixture();
    SkillPackageDTO created = fixture.createPublished("dev-package");

    SkillPackageEditDTO edit = new SkillPackageEditDTO();
    edit.setDescription("x");
    edit.setBranch("main");
    assertThrows(
        AiValidationException.class, () -> fixture.service.editPackage("dev-package", edit));

    SkillPackageCheckDTO check = new SkillPackageCheckDTO();
    assertThrows(
        AiValidationException.class, () -> fixture.service.checkPackage("dev-package", check));

    SkillPackagePublishDTO publish = new SkillPackagePublishDTO();
    publish.setTargetCommit(OLD_COMMIT);
    assertThrows(
        AiValidationException.class, () -> fixture.service.updatePackage("dev-package", publish));
    verify(fixture.agentDefinitionRepository, never()).existsReferencingSkill(any(), any());
    assertEquals(created.getVersion(), fixture.service.getPackage("dev-package").getVersion());
  }

  private static SkillPackageCreateDTO create(String packageName) {
    return createWithUrl(REPOSITORY_URL, packageName);
  }

  private static SkillPackageCreateDTO createWithUrl(String repositoryUrl) {
    return createWithUrl(repositoryUrl, "dev-package");
  }

  private static SkillPackageCreateDTO createWithUrl(String repositoryUrl, String packageName) {
    SkillPackageCreateDTO dto = new SkillPackageCreateDTO();
    dto.setPackageName(packageName);
    dto.setDescription("skill package");
    dto.setRepositoryUrl(repositoryUrl);
    dto.setBranch("main");
    return dto;
  }

  private static SkillPackageCheckDTO check(String expectedVersion) {
    SkillPackageCheckDTO dto = new SkillPackageCheckDTO();
    dto.setExpectedVersion(expectedVersion);
    return dto;
  }

  private static SkillPackagePublishDTO publish(String expectedVersion, String targetCommit) {
    SkillPackagePublishDTO dto = new SkillPackagePublishDTO();
    dto.setExpectedVersion(expectedVersion);
    dto.setTargetCommit(targetCommit);
    return dto;
  }

  private static SkillManifestEntry entry(String name, String description) {
    return new SkillManifestEntry(name, description);
  }

  private static SkillPackage unchecked(String packageName) {
    SkillPackage skillPackage = new SkillPackage();
    skillPackage.setPackageName(packageName);
    skillPackage.setRepositoryUrl(REPOSITORY_URL);
    skillPackage.setBranch("main");
    skillPackage.setCurrentCommit(OLD_COMMIT);
    skillPackage.setSkills(List.of(entry("dev", "developer skill")));
    skillPackage.setVersion(0L);
    return skillPackage;
  }

  /** 单测夹具：内存仓库 + 可编程 fake Git cache + mock Agent 引用。 */
  private static final class Fixture {

    private final FakeRepository repository = new FakeRepository();
    private final FakeGitCache git = new FakeGitCache();
    private final AgentDefinitionRepository agentDefinitionRepository =
        mock(AgentDefinitionRepository.class);
    private final SkillCatalogServiceImpl service;

    private Fixture() {
      this.service =
          new SkillCatalogServiceImpl(
              repository,
              git,
              new SkillCatalogConverter(),
              new SkillPackageGuard(repository, agentDefinitionRepository),
              new AgentEditableSupport(new ObjectMapper()));
    }

    private SkillPackageDTO createPublished(String packageName) {
      git.resolveHead(REPOSITORY_URL, "main", OLD_COMMIT);
      git.manifest(OLD_COMMIT, List.of(entry("dev", "developer skill")));
      return service.createPackage(create(packageName));
    }

    private void referenced(String packageName, String skillName) {
      when(agentDefinitionRepository.existsReferencingSkill(packageName, skillName))
          .thenReturn(true);
    }
  }

  /** 内存权威表：只实现 service 用到的 CAS 语义。 */
  private static final class FakeRepository implements SkillPackageRepository {

    private final Map<String, SkillPackage> packages = new LinkedHashMap<>();

    private void seed(SkillPackage skillPackage) {
      packages.put(skillPackage.getPackageName(), skillPackage);
    }

    /** 与 SQL 的 {@code order by package_name asc} 一致：排序契约属于仓库层。 */
    @Override
    public List<SkillPackage> listPackages() {
      return packages.values().stream()
          .sorted(Comparator.comparing(SkillPackage::getPackageName))
          .toList();
    }

    @Override
    public SkillPackage getPackage(String packageName) {
      return packages.get(packageName);
    }

    @Override
    public SkillPackage lockPackage(String packageName) {
      return packages.get(packageName);
    }

    @Override
    public boolean insertPackage(SkillPackage skillPackage) {
      if (packages.containsKey(skillPackage.getPackageName())) {
        return false;
      }
      skillPackage.setCreateTime(Instant.EPOCH);
      skillPackage.setUpdateTime(Instant.EPOCH);
      packages.put(skillPackage.getPackageName(), copy(skillPackage));
      return true;
    }

    @Override
    public boolean updatePackage(SkillPackage skillPackage, long expectedVersion) {
      SkillPackage current = packages.get(skillPackage.getPackageName());
      if (current == null || current.getVersion() != expectedVersion) {
        return false;
      }
      skillPackage.setVersion(expectedVersion + 1);
      packages.put(skillPackage.getPackageName(), copy(skillPackage));
      return true;
    }

    @Override
    public boolean deletePackage(String packageName, long expectedVersion) {
      SkillPackage current = packages.get(packageName);
      if (current == null || current.getVersion() != expectedVersion) {
        return false;
      }
      packages.remove(packageName);
      return true;
    }

    private static SkillPackage copy(SkillPackage source) {
      SkillPackage target = new SkillPackage();
      target.setPackageName(source.getPackageName());
      target.setDescription(source.getDescription());
      target.setRepositoryUrl(source.getRepositoryUrl());
      target.setBranch(source.getBranch());
      target.setCurrentCommit(source.getCurrentCommit());
      target.setObservedHeadCommit(source.getObservedHeadCommit());
      target.setHeadCheckedAt(source.getHeadCheckedAt());
      target.setHeadCheckError(source.getHeadCheckError());
      target.setSkills(List.copyOf(source.getSkills()));
      target.setVersion(source.getVersion());
      target.setCreateTime(source.getCreateTime());
      target.setUpdateTime(source.getUpdateTime());
      return target;
    }
  }

  /** 可编程 fake Git cache：记录物化过的 commit，并按 commit 返回预设 manifest。 */
  private static final class FakeGitCache implements SkillGitCache {

    private final List<String> ensuredCommits = new ArrayList<>();
    private final Map<String, String> heads = new LinkedHashMap<>();
    private final Map<String, List<SkillManifestEntry>> manifests = new LinkedHashMap<>();
    private SkillGitException headFailure;
    private SkillGitException manifestFailure;

    private void resolveHead(String repositoryUrl, String branch, String commit) {
      heads.put(repositoryUrl + "#" + branch, commit);
      headFailure = null;
    }

    private void failHead(SkillGitException failure) {
      this.headFailure = failure;
    }

    private void manifest(String commit, List<SkillManifestEntry> entries) {
      manifests.put(commit, entries);
      manifestFailure = null;
    }

    private void failManifest(SkillGitException failure) {
      this.manifestFailure = failure;
    }

    @Override
    public String resolveBranchHead(String repositoryUrl, String branch) {
      if (headFailure != null) {
        throw headFailure;
      }
      String commit = heads.get(repositoryUrl + "#" + branch);
      if (commit == null) {
        throw new SkillGitException("remote branch not found: " + branch);
      }
      return commit;
    }

    @Override
    public void ensureCommit(String packageName, String repositoryUrl, String commit) {
      ensuredCommits.add(commit);
    }

    @Override
    public List<SkillManifestEntry> scanManifest(String packageName, String commit) {
      if (manifestFailure != null) {
        throw manifestFailure;
      }
      List<SkillManifestEntry> entries = manifests.get(commit);
      if (entries == null) {
        throw new SkillGitException("commit not found: " + commit);
      }
      return entries;
    }

    @Override
    public byte[] readFile(String packageName, String commit, String path) {
      throw new UnsupportedOperationException();
    }
  }
}
