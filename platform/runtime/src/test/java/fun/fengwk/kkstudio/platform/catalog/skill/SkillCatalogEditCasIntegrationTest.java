package fun.fengwk.kkstudio.platform.catalog.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageCheckDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageDetailDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageEditDTO;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveBuilder;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveBuilder.InputEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveBuilder.Type;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitRepository;
import fun.fengwk.kkstudio.platform.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.platform.secret.SecretUpdate;
import fun.fengwk.kkstudio.platform.storage.StorageS3TestConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Skill Package 编辑的 CAS 语义（真实 PostgreSQL）：描述、branch 与令牌都是可编辑事实，任一变更都在同一 {@code expectedVersion}
 * 下推进一次 {@code skill_package.version}；检查观察值不推进版本。
 *
 * <p>并行路径的版本推进由 {@link SkillCatalogService#publishPreparedGitImport} 承担，因此配置同步改写仓库设置时同样只在一次版本推进内生效。
 */
@Import(StorageS3TestConfiguration.class)
@TestPropertySource(
    properties = {
      "kk-studio.storage.s3.endpoint=http://minio.example.local:9000",
      "kk-studio.storage.s3.public-endpoint=https://cdn.example.com",
      "kk-studio.storage.s3.region=us-east-1",
      "kk-studio.storage.s3.bucket=test-bucket",
      "kk-studio.storage.s3.access-key=local-test-access-key",
      "kk-studio.storage.s3.secret-key=local-test-secret-key"
    })
class SkillCatalogEditCasIntegrationTest extends PostgresSpringTestSupport {

  private static final String PACKAGE = "edit-pkg";
  private static final String REPOSITORY_URL = "https://git.example.com/repo.git";
  private static final String DESCRIPTION = "dev tools";
  private static final String HEAD = "0123456789abcdef0123456789abcdef01234567";
  private static final String NEW_HEAD = "89abcdef0123456789abcdef0123456789abcdef";

  @MockitoBean private SkillGitRepository skillGitRepository;
  @Autowired private SkillCatalogService skillCatalogService;
  @Autowired private SkillPackageRepository skillPackageRepository;
  @Autowired private PlatformTransactionManager transactionManager;

  /** 测试意图：只改 branch 也是可编辑事实变更，必须让陈旧卡片拿到 version conflict。 */
  @Test
  void branchOnlyEditAdvancesVersion() {
    createGitPackage();

    SkillPackageDetailDTO detail =
        skillCatalogService.editPackage(PACKAGE, editDTO(0L, DESCRIPTION, "dev"));

    assertEquals(1L, detail.getVersion(), "只改 branch 也必须推进一次 CAS 版本");
    assertEquals("dev", detail.getGit().getBranch());
    assertEquals(1L, skillPackageRepository.getPackage(PACKAGE).version());
  }

  /** 测试意图：只替换或清空令牌同样推进版本，不能留下对 CAS 令牌不可见的仓库设置变更。 */
  @Test
  void tokenOnlyEditAdvancesVersion() {
    createGitPackage();
    SkillPackageEditDTO dto = editDTO(0L, DESCRIPTION, "main");
    dto.setToken(SecretUpdate.set("fresh-token"));

    SkillPackageDetailDTO detail = skillCatalogService.editPackage(PACKAGE, dto);

    assertEquals(1L, detail.getVersion());
    assertTrue(detail.getGit().getToken().configured());
    SkillPackage stored = skillPackageRepository.getPackage(PACKAGE);
    assertEquals(1L, stored.version());
    assertEquals("fresh-token", stored.gitSource().token());
  }

  /** 测试意图：用编辑前的版本再次编辑必须冲突，先到的 branch 改动不能被后到的陈旧卡片静默覆盖。 */
  @Test
  void staleVersionAfterBranchEditConflicts() {
    createGitPackage();
    skillCatalogService.editPackage(PACKAGE, editDTO(0L, DESCRIPTION, "dev"));

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> skillCatalogService.editPackage(PACKAGE, editDTO(0L, DESCRIPTION, "release")));

    assertEquals(DomainErrorCode.VERSION_CONFLICT, error.code());
    assertEquals(
        "dev",
        skillPackageRepository.getPackage(PACKAGE).gitSource().branch(),
        "陈旧卡片不得覆盖先到的 branch 改动");
  }

  /** 测试意图：描述与 branch 同时修改只推进一次版本，否则一次编辑会消耗两个客户端版本。 */
  @Test
  void descriptionAndBranchEditAdvancesVersionOnce() {
    createGitPackage();

    SkillPackageDetailDTO detail =
        skillCatalogService.editPackage(PACKAGE, editDTO(0L, "renamed", "dev"));

    assertEquals(1L, detail.getVersion());
    assertEquals("renamed", detail.getDescription());
    assertEquals("dev", detail.getGit().getBranch());
  }

  /** 测试意图：检查只写观察事实，不推进 CAS 版本，卡片不会因为轮询检查而失效。 */
  @Test
  void checkRecordsObservationWithoutAdvancingVersion() {
    createGitPackage();
    when(skillGitRepository.resolveBranchHead(REPOSITORY_URL, "main", null)).thenReturn(NEW_HEAD);
    SkillPackageCheckDTO checkDTO = new SkillPackageCheckDTO();
    checkDTO.setExpectedVersion(0L);

    SkillPackageDetailDTO detail = skillCatalogService.checkGitPackage(PACKAGE, checkDTO);

    assertEquals(0L, detail.getVersion());
    assertEquals(NEW_HEAD, detail.getHeadCommit());
  }

  /** 测试意图：配置同步改写仓库设置时，branch 与发布事实仍在一次版本推进内落地。 */
  @Test
  void configurationImportRewriteAdvancesVersionOnce() {
    createGitPackage();
    when(skillGitRepository.resolveBranchHead(REPOSITORY_URL, "dev", null)).thenReturn(NEW_HEAD);
    when(skillGitRepository.buildArchive(REPOSITORY_URL, NEW_HEAD, null)).thenReturn(archive());

    SkillCatalogService.PreparedGitImport prepared =
        skillCatalogService.prepareGitImport(
            PACKAGE, "imported description", REPOSITORY_URL, "dev", null);
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> skillCatalogService.publishPreparedGitImport(prepared));

    SkillPackage stored = skillPackageRepository.getPackage(PACKAGE);
    assertEquals(1L, stored.version());
    assertEquals("dev", stored.gitSource().branch());
    assertEquals(NEW_HEAD, stored.packageVersion());
    assertEquals("imported description", stored.description());
  }

  /** 创建即发布的 Git 渠道包：version 0、branch main、HEAD 已发布。 */
  private void createGitPackage() {
    when(skillGitRepository.resolveBranchHead(REPOSITORY_URL, "main", null)).thenReturn(HEAD);
    when(skillGitRepository.buildArchive(REPOSITORY_URL, HEAD, null)).thenReturn(archive());
    SkillPackageCreateDTO createDTO = new SkillPackageCreateDTO();
    createDTO.setPackageName(PACKAGE);
    createDTO.setDescription(DESCRIPTION);
    createDTO.setRepositoryUrl(REPOSITORY_URL);
    createDTO.setBranch("main");
    skillCatalogService.createGitPackage(createDTO);
    assertEquals(0L, skillPackageRepository.getPackage(PACKAGE).version());
  }

  private static SkillPackageEditDTO editDTO(
      long expectedVersion, String description, String branch) {
    SkillPackageEditDTO dto = new SkillPackageEditDTO();
    dto.setExpectedVersion(expectedVersion);
    dto.setDescription(description);
    dto.setBranch(branch);
    dto.setToken(SecretUpdate.keep());
    return dto;
  }

  private static SkillArchiveBuilder.Result archive() {
    byte[] skillMd =
        "---\nname: dev\ndescription: dev skill\n---\n".getBytes(StandardCharsets.UTF_8);
    try {
      return new SkillArchiveBuilder()
          .fromEntries(List.of(new InputEntry("dev/SKILL.md", Type.FILE, false, skillMd)));
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }
}
