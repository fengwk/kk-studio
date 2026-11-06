package fun.fengwk.kkstudio.web.configsync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.catalog.definition.Agent;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentService;
import fun.fengwk.kkstudio.platform.catalog.dto.AgentDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillRefDTO;
import fun.fengwk.kkstudio.platform.catalog.model.ModelRepository;
import fun.fengwk.kkstudio.platform.catalog.provider.ProviderRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackage;
import fun.fengwk.kkstudio.platform.catalog.skill.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveBuilder;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveBuilder.InputEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveBuilder.Type;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitException;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitRepository;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncService;
import fun.fengwk.kkstudio.platform.configsync.ConfigSyncYaml;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncImportRequestDTO;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncImportResultDTO;
import fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncKind;
import fun.fengwk.kkstudio.platform.storage.StorageMaintenance;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageUploadRepository;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageUpload;
import fun.fengwk.kkstudio.web.storage.WebStorageS3TestConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 配置同步 Git 技能包原子性契约的真实 PostgreSQL 集成测试。
 *
 * <p>测试在进程级真实 PostgreSQL 容器与内存 S3 假件上运行，使用 Mockito 替代服务端 Git 访问。核心覆盖：
 *
 * <ul>
 *   <li>技能发布后发生后置 Agent 写入校验失败时，全文件数据库写入整体回滚（新建包不存在、既有包版本及 artifactBlobId
 *       保持不变），且准备阶段生成的暂存上传均被标记清理并释放 blob 引用；
 *   <li>同文件内声明 Git 技能包与引用该包下技能的 Agent，可在同一事务内原子导入成功；
 *   <li>多 Git 条目中后序条目在准备阶段发生 Git 异常时，在任何 Catalog 数据库写事务开启前立即中止导入，且在此之前已暂存的前序条目上传被标记清理并释放 blob 引用。
 * </ul>
 */
@Import(WebStorageS3TestConfiguration.class)
class ConfigSyncGitAtomicIntegrationTest extends ConfigSyncTestSupport {

  private static final String EXISTING_PKG = "pkg-atomic-existing";
  private static final String NEW_PKG = "pkg-atomic-new";
  private static final String STAGED_PKG = "pkg-atomic-staged";
  private static final String FAILING_PKG = "pkg-atomic-failing";
  private static final String SUCCESS_PKG = "pkg-atomic-success";

  private static final String EXISTING_URL = "https://git.example.com/existing.git";
  private static final String NEW_URL = "https://git.example.com/new.git";
  private static final String STAGED_URL = "https://git.example.com/staged.git";
  private static final String FAILING_URL = "https://git.example.com/failing.git";
  private static final String SUCCESS_URL = "https://git.example.com/success.git";

  private static final String COMMIT_1 = "1111111111111111111111111111111111111111";
  private static final String COMMIT_2 = "2222222222222222222222222222222222222222";
  private static final String COMMIT_NEW = "3333333333333333333333333333333333333333";
  private static final String COMMIT_STAGED = "4444444444444444444444444444444444444444";
  private static final String COMMIT_SUCCESS = "5555555555555555555555555555555555555555";

  @MockitoBean private SkillGitRepository skillGitRepository;

  /** 暂停后台清扫，以便确定性检查暂存清理请求与引用计数。 */
  @MockitoBean private StorageMaintenance storageMaintenance;

  @Autowired private ConfigSyncService configSyncService;
  @Autowired private ConfigSyncYaml configSyncYaml;
  @Autowired private SkillPackageRepository skillPackageRepository;
  @Autowired private ProviderRepository agentProviderRepository;
  @Autowired private ModelRepository agentModelRepository;
  @Autowired private AgentRepository agentRepository;
  @Autowired private AgentService agentService;
  @Autowired private StorageUploadRepository storageUploadRepository;
  @Autowired private StorageBlobManager storageBlobManager;
  @Autowired private JdbcTemplate jdbcTemplate;

  /**
   * 测试意图：配置导入包含既有 Git 技能包与新建 Git 技能包，在技能包发布后发生后置 Agent 写入校验失败 （引用不存在技能，Planner
   * 接受但写入期因找不到该技能被拒绝），验证全文件事务回滚： 新建包不存在、既有包及其 artifact 引用未改变，且导入期间生成的所有暂存上传均被标记清理并释放 blob 引用。
   */
  @Test
  void importFailsAfterSkillPublicationRollsBackDatabaseAndCleansUpStagedUploads()
      throws Exception {
    // 1. 初始化既有技能包（版本 0，commit 1）
    SkillArchiveBuilder.Result archive1 = buildArchiveResult(Map.of("dev", "dev skill v1"));
    when(skillGitRepository.resolveBranchHead(EXISTING_URL, "main", null)).thenReturn(COMMIT_1);
    when(skillGitRepository.buildArchive(EXISTING_URL, COMMIT_1, null)).thenReturn(archive1);

    String initYaml =
        yamlDocument(
            Map.of(
                "skillPackages",
                List.of(
                    skillPackageEntry(EXISTING_PKG, "existing package", EXISTING_URL, "main"))));
    configSyncService.importYaml(request(initYaml));

    SkillPackage existingBefore = skillPackageRepository.getPackage(EXISTING_PKG);
    assertNotNull(existingBefore, "pre-existing package must exist before atomic test");
    assertEquals(0L, existingBefore.version());
    assertEquals(COMMIT_1, existingBefore.packageVersion());
    UUID originalArtifactBlobId = existingBefore.artifactBlobId();
    assertNotNull(originalArtifactBlobId);

    StorageBlob originalBlob = storageBlobManager.getBlob(originalArtifactBlobId);
    assertNotNull(originalBlob);
    assertEquals(1L, originalBlob.getRefCount());
    assertEquals(StorageBlobState.ACTIVE, originalBlob.getState());

    // 2. 模拟远端推进：既有包推进到 COMMIT_2（包含 dev 与 extra），新包为 COMMIT_NEW（仅包含 dev）
    SkillArchiveBuilder.Result archive2 =
        buildArchiveResult(Map.of("dev", "dev skill v2", "extra", "extra skill"));
    SkillArchiveBuilder.Result archiveNew = buildArchiveResult(Map.of("dev", "new dev skill"));
    when(skillGitRepository.resolveBranchHead(EXISTING_URL, "main", null)).thenReturn(COMMIT_2);
    when(skillGitRepository.buildArchive(EXISTING_URL, COMMIT_2, null)).thenReturn(archive2);
    when(skillGitRepository.resolveBranchHead(NEW_URL, "main", null)).thenReturn(COMMIT_NEW);
    when(skillGitRepository.buildArchive(NEW_URL, COMMIT_NEW, null)).thenReturn(archiveNew);

    // 3. 构造原子性导入文档：声明 Provider、Model、既有包更新、新建包，以及引用新建包下不存在技能的 Agent。
    // 该 Agent 声明格式合法，且引用的包同在文件中，因此 Planner 允许其进入 imported 列表，
    // 直至写入期引用解析器 requireReferencedLifecycles 检查 manifest 时被真实拒绝。
    String providerName = "atomic-rollback-prov";
    String modelName = "atomic-rollback-model";
    String agentName = "atomic-rollback-agent";

    Map<String, Object> document = new LinkedHashMap<>();
    document.put("providers", List.of(providerEntry(providerName)));
    document.put("models", List.of(modelEntry(providerName, modelName)));
    document.put(
        "skillPackages",
        List.of(
            skillPackageEntry(EXISTING_PKG, "existing package updated", EXISTING_URL, "main"),
            skillPackageEntry(NEW_PKG, "new package", NEW_URL, "main")));
    document.put(
        "agents",
        List.of(agentEntry(agentName, modelName, List.of(skillRef(NEW_PKG, "nonexistent-skill")))));
    String importYaml = yamlDocument(document);

    Set<UUID> uploadsBefore = listAllUploadIds();

    // 4. 执行导入，断言校验失败
    DomainException error =
        assertThrows(
            DomainException.class, () -> configSyncService.importYaml(request(importYaml)));
    assertEquals(DomainErrorCode.VALIDATION, error.code());

    // 5. 校验数据库回滚：新建包不存在、Provider 与 Model 不存在、Agent 不存在
    assertNull(
        skillPackageRepository.getPackage(NEW_PKG),
        "new skill package must be absent after write transaction rollback");
    assertNull(
        agentProviderRepository.getByName(providerName),
        "provider must be rolled back after write transaction failure");
    assertNull(
        agentModelRepository.getByName(modelName),
        "model must be rolled back after write transaction failure");
    assertNull(
        agentRepository.getByName(agentName),
        "agent must be rolled back after write transaction failure");

    // 既有包状态完全未改变，artifactBlobId 与引用计数保持原状
    SkillPackage existingAfter = skillPackageRepository.getPackage(EXISTING_PKG);
    assertNotNull(existingAfter);
    assertEquals(0L, existingAfter.version(), "existing package version must remain unchanged");
    assertEquals(
        COMMIT_1,
        existingAfter.packageVersion(),
        "existing package commit must remain original commit");
    assertEquals(
        originalArtifactBlobId,
        existingAfter.artifactBlobId(),
        "existing package artifact blob reference must remain original");

    StorageBlob originalBlobAfter = storageBlobManager.getBlob(originalArtifactBlobId);
    assertNotNull(originalBlobAfter);
    assertEquals(1L, originalBlobAfter.getRefCount());
    assertEquals(StorageBlobState.ACTIVE, originalBlobAfter.getState());

    // 6. 校验暂存上传被标记清理并释放 blob 引用
    List<UUID> stagedUploadIds = listUploadIdsNotIn(uploadsBefore);
    assertEquals(2, stagedUploadIds.size(), "both packages must have been staged");
    for (UUID uploadId : stagedUploadIds) {
      StorageUpload upload = storageUploadRepository.getById(uploadId);
      assertNotNull(upload);
      assertNotNull(
          upload.getCleanupRequestedAt(),
          "staged upload must be marked for cleanup on write failure: " + uploadId);
      assertNotNull(upload.getBlobId());

      StorageBlob stagedBlob = storageBlobManager.getBlob(upload.getBlobId());
      assertNotNull(stagedBlob);
      assertEquals(0L, stagedBlob.getRefCount());
      assertEquals(StorageBlobState.DELETING, stagedBlob.getState());
    }
  }

  /** 意图：成功更新 Git 包时提交新的描述、归档、分支与凭据事实，并原子释放旧归档引用。 */
  @Test
  void successfulImportUpdatesExistingGitPackageAndReleasesOldArtifact() throws Exception {
    SkillArchiveBuilder.Result first = buildArchiveResult(Map.of("dev", "first version"));
    SkillArchiveBuilder.Result second = buildArchiveResult(Map.of("dev", "second version"));
    when(skillGitRepository.resolveBranchHead(EXISTING_URL, "main", "test-token"))
        .thenReturn(COMMIT_1);
    when(skillGitRepository.buildArchive(EXISTING_URL, COMMIT_1, "test-token")).thenReturn(first);
    Map<String, Object> original =
        skillPackageEntry(EXISTING_PKG, "original", EXISTING_URL, "main");
    original.put("token", "test-token");
    configSyncService.importYaml(request(yamlDocument(Map.of("skillPackages", List.of(original)))));
    SkillPackage before = skillPackageRepository.getPackage(EXISTING_PKG);
    assertEquals("original", before.description());
    UUID oldBlobId = before.artifactBlobId();

    when(skillGitRepository.resolveBranchHead(EXISTING_URL, "next", null)).thenReturn(COMMIT_2);
    when(skillGitRepository.buildArchive(EXISTING_URL, COMMIT_2, null)).thenReturn(second);
    Map<String, Object> updated =
        skillPackageEntry(EXISTING_PKG, "updated description", EXISTING_URL, "next");
    configSyncService.importYaml(request(yamlDocument(Map.of("skillPackages", List.of(updated)))));

    SkillPackage after = skillPackageRepository.getPackage(EXISTING_PKG);
    assertEquals(1L, after.version());
    assertEquals("updated description", after.description());
    assertEquals(COMMIT_2, after.packageVersion());
    assertEquals("next", after.gitSource().branch());
    assertEquals(COMMIT_2, after.gitSource().headCommit());
    assertNull(after.gitSource().token());
    assertFalse(oldBlobId.equals(after.artifactBlobId()));
    assertEquals(0L, storageBlobManager.getBlob(oldBlobId).getRefCount());
    assertEquals(StorageBlobState.DELETING, storageBlobManager.getBlob(oldBlobId).getState());
    assertEquals(1L, storageBlobManager.getBlob(after.artifactBlobId()).getRefCount());
  }

  /** 测试意图：单文件同时声明 Git 技能包与引用该包下合法技能的 Agent，验证全流程在同一事务内原子导入成功， 建立正确的技能包与 Agent 引用事实。 */
  @Test
  void successfulImportSameFileAgentRefersToGitSkill() throws Exception {
    SkillArchiveBuilder.Result archiveSuccess =
        buildArchiveResult(Map.of("dev", "dev skill content"));
    when(skillGitRepository.resolveBranchHead(SUCCESS_URL, "main", null))
        .thenReturn(COMMIT_SUCCESS);
    when(skillGitRepository.buildArchive(SUCCESS_URL, COMMIT_SUCCESS, null))
        .thenReturn(archiveSuccess);

    String providerName = "atomic-success-prov";
    String modelName = "atomic-success-model";
    String agentName = "atomic-success-agent";

    Map<String, Object> document = new LinkedHashMap<>();
    document.put("providers", List.of(providerEntry(providerName)));
    document.put("models", List.of(modelEntry(providerName, modelName)));
    document.put(
        "skillPackages",
        List.of(skillPackageEntry(SUCCESS_PKG, "success package", SUCCESS_URL, "main")));
    document.put(
        "agents", List.of(agentEntry(agentName, modelName, List.of(skillRef(SUCCESS_PKG, "dev")))));
    String importYaml = yamlDocument(document);

    ConfigSyncImportResultDTO result = configSyncService.importYaml(request(importYaml));

    assertTrue(
        result.getImported().stream()
            .anyMatch(
                ref ->
                    ref.getKind() == ConfigSyncKind.SKILL_PACKAGES
                        && SUCCESS_PKG.equals(ref.getName())),
        "skillPackages/pkg-atomic-success must be imported");
    assertTrue(
        result.getImported().stream()
            .anyMatch(
                ref -> ref.getKind() == ConfigSyncKind.AGENTS && agentName.equals(ref.getName())),
        "agents/atomic-success-agent must be imported");

    SkillPackage pkg = skillPackageRepository.getPackage(SUCCESS_PKG);
    assertNotNull(pkg);
    assertEquals(COMMIT_SUCCESS, pkg.packageVersion());
    assertNotNull(pkg.findSkill("dev"));

    Agent agent = agentRepository.getByName(agentName);
    assertNotNull(agent);
    AgentDTO agentDto = agentService.toDto(agent);
    assertEquals(1, agentDto.getConfig().getSkills().size());
    assertEquals(SUCCESS_PKG, agentDto.getConfig().getSkills().get(0).getPackageName());
    assertEquals("dev", agentDto.getConfig().getSkills().get(0).getName());

    assertNotNull(agentProviderRepository.getByName(providerName));
    assertNotNull(agentModelRepository.getByName(modelName));
  }

  /**
   * 测试意图：当文件中包含多个 Git 技能包时，后序技能包在准备期发生 Git 异常，必须在发生任何 Catalog 数据库写入之前中止导入； 在 Git Mockito 回调中直接断言
   * Provider 尚不存在且无活动写事务，并验证在此之前已暂存的前序技能包上传被标记清理且 blob 引用被释放。
   */
  @Test
  void gitPrepareFailureAbortsBeforeAnyCatalogDbWritesAndCleansEarlierStaging() throws Exception {
    String providerName = "atomic-fail-prov";

    // Item 1: 准备成功并暂存
    SkillArchiveBuilder.Result archiveStaged =
        buildArchiveResult(Map.of("dev", "staged skill content"));
    when(skillGitRepository.resolveBranchHead(STAGED_URL, "main", null)).thenReturn(COMMIT_STAGED);
    when(skillGitRepository.buildArchive(STAGED_URL, COMMIT_STAGED, null))
        .thenReturn(archiveStaged);

    // Item 2: 准备失败。在 Mockito 回调中验证此时没有任何 Catalog 写入，也不在写事务内
    when(skillGitRepository.resolveBranchHead(eq(FAILING_URL), eq("main"), any()))
        .thenAnswer(
            invocation -> {
              assertFalse(
                  TransactionSynchronizationManager.isActualTransactionActive(),
                  "Git preparation must execute outside of the database write transaction");
              assertNull(
                  agentProviderRepository.getByName(providerName),
                  "Provider must not be written to DB before or during Git prepare");
              assertEquals(
                  0,
                  jdbcTemplate.queryForObject(
                      "select count(*) from provider where name = ?", Integer.class, providerName),
                  "provider table must not contain rows during Git prepare");
              assertNull(
                  skillPackageRepository.getPackage(STAGED_PKG),
                  "staged-pkg must not be written to catalog DB during Git prepare");
              throw new SkillGitException("network connection failure: repository unreachable");
            });

    Map<String, Object> document = new LinkedHashMap<>();
    document.put("providers", List.of(providerEntry(providerName)));
    document.put(
        "skillPackages",
        List.of(
            skillPackageEntry(STAGED_PKG, "staged package", STAGED_URL, "main"),
            skillPackageEntry(FAILING_PKG, "failing package", FAILING_URL, "main")));
    String importYaml = yamlDocument(document);

    Set<UUID> uploadsBefore = listAllUploadIds();

    DomainException error =
        assertThrows(
            DomainException.class, () -> configSyncService.importYaml(request(importYaml)));
    assertEquals(DomainErrorCode.VALIDATION, error.code());

    // 终态验证：整个导入过程未向 Catalog 数据库写入任何数据
    assertNull(agentProviderRepository.getByName(providerName));
    assertNull(skillPackageRepository.getPackage(STAGED_PKG));
    assertNull(skillPackageRepository.getPackage(FAILING_PKG));

    // 验证前序技能包已生成的暂存上传已被标记清理，且关联 blob 引用被释放
    List<UUID> stagedUploadIds = listUploadIdsNotIn(uploadsBefore);
    assertEquals(
        1,
        stagedUploadIds.size(),
        "earlier item must have staged an archive before the second item failed");

    StorageUpload stagedUpload = storageUploadRepository.getById(stagedUploadIds.get(0));
    assertNotNull(stagedUpload);
    assertNotNull(
        stagedUpload.getCleanupRequestedAt(),
        "earlier staged upload must be marked cleanup when later Git prepare fails");
    assertNotNull(stagedUpload.getBlobId());

    StorageBlob stagedBlob = storageBlobManager.getBlob(stagedUpload.getBlobId());
    assertNotNull(stagedBlob);
    assertEquals(0L, stagedBlob.getRefCount());
    assertEquals(StorageBlobState.DELETING, stagedBlob.getState());
  }

  // ---------------------------------------------------------------- helpers

  private static ConfigSyncImportRequestDTO request(String yaml) {
    ConfigSyncImportRequestDTO request = new ConfigSyncImportRequestDTO();
    request.setYaml(yaml);
    return request;
  }

  private Set<UUID> listAllUploadIds() {
    return new HashSet<>(jdbcTemplate.queryForList("select id from storage_upload", UUID.class));
  }

  private List<UUID> listUploadIdsNotIn(Set<UUID> before) {
    return jdbcTemplate.queryForList("select id from storage_upload", UUID.class).stream()
        .filter(id -> !before.contains(id))
        .toList();
  }

  private static SkillArchiveBuilder.Result buildArchiveResult(Map<String, String> skills)
      throws IOException {
    List<InputEntry> entries = new ArrayList<>();
    for (Map.Entry<String, String> entry : skills.entrySet()) {
      String skillName = entry.getKey();
      String skillDesc = entry.getValue();
      byte[] skillMd =
          ("---\nname: " + skillName + "\ndescription: " + skillDesc + "\n---\n")
              .getBytes(StandardCharsets.UTF_8);
      entries.add(new InputEntry(skillName + "/SKILL.md", Type.FILE, false, skillMd));
    }
    return new SkillArchiveBuilder().fromEntries(entries);
  }

  private static Map<String, Object> skillPackageEntry(
      String packageName, String description, String repositoryUrl, String branch) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("packageName", packageName);
    entry.put("description", description);
    entry.put("repositoryUrl", repositoryUrl);
    entry.put("branch", branch);
    return entry;
  }

  private static Map<String, Object> providerEntry(String name) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("name", name);
    entry.put("providerType", "openai");
    entry.put("baseUrl", "https://example.com/v1");
    return entry;
  }

  private static Map<String, Object> modelEntry(String providerName, String name) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("kind", "MODEL");
    entry.put("providerName", providerName);
    entry.put("name", name);
    entry.put("modelId", "wire-" + name);
    entry.put("config", configYaml(modelConfig("{}", "1.0")));
    return entry;
  }

  private static Map<String, Object> agentEntry(
      String name, String modelName, List<SkillRefDTO> skills) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("name", name);
    entry.put(
        "execution",
        Map.of(
            "driver", "builtin", "settings", Map.of("modelName", modelName, "variant", "default")));
    entry.put("config", configYaml(agentConfig(List.of(), skills, List.of())));
    return entry;
  }

  private static Map<String, Object> configYaml(Object config) {
    return new ConfigSyncYaml().toMap(config, "config");
  }

  private String yamlDocument(Map<String, Object> document) {
    return configSyncYaml.dump(document);
  }
}
