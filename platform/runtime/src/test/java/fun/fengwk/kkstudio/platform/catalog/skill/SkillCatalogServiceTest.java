package fun.fengwk.kkstudio.platform.catalog.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveBuilder;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveIndex;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitException;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitRepository;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentSkillState;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentStatus;
import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageBlobRepository;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobContent;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageCheckDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageDetailDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageEditDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageFileDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageGitCreateDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageGitPublishDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackagePreviewDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageUploadPreviewDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageUploadPublishDTO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** {@link SkillCatalogService} 的当前版本模型、两个渠道与发布事务单元测试。 */
class SkillCatalogServiceTest {

  private static final String PACKAGE = "dev-tools";
  private static final String HEAD = "0123456789abcdef0123456789abcdef01234567";
  private static final String OLD_HEAD = "89abcdef0123456789abcdef0123456789abcdef";
  private static final String ARTIFACT_SHA = "a".repeat(64);
  private static final String NEW_ARTIFACT_SHA = "b".repeat(64);

  private SkillPackageRepository packageRepository;
  private AgentDefinitionRepository agentDefinitionRepository;
  private SkillGitRepository gitRepository;
  private SkillCatalogWrites writes;
  private StorageUploadService uploadService;
  private StorageBlobContentService contentService;
  private StorageBlobManager blobManager;
  private StorageBlobRepository blobRepository;
  private EnvironmentRegistry environmentRegistry;
  private SkillCatalogService service;

  @BeforeEach
  void setUp() {
    packageRepository = mock(SkillPackageRepository.class);
    agentDefinitionRepository = mock(AgentDefinitionRepository.class);
    gitRepository = mock(SkillGitRepository.class);
    writes = mock(SkillCatalogWrites.class);
    uploadService = mock(StorageUploadService.class);
    contentService = mock(StorageBlobContentService.class);
    blobManager = mock(StorageBlobManager.class);
    blobRepository = mock(StorageBlobRepository.class);
    environmentRegistry = mock(EnvironmentRegistry.class);
    doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(0)).get())
        .when(writes)
        .execute(any());
    when(blobRepository.updateArchiveIndex(any(), anyString())).thenReturn(true);
    service =
        new SkillCatalogService(
            packageRepository,
            agentDefinitionRepository,
            gitRepository,
            new AgentEditableSupport(JsonMapper.builder().build()),
            writes,
            uploadService,
            contentService,
            blobManager,
            blobRepository,
            environmentRegistry);
  }

  @Test
  void listFilesReadsOnlyTheServerArchiveIndex() {
    // 意图：文件清单直接来自 blob 上服务端生成的索引，并按路径确定性排序。
    SkillArchiveBuilder.Result archive = archive("review", "dev");
    UUID blobId = UUID.randomUUID();
    StorageBlob artifact = indexedBlob(blobId, ARTIFACT_SHA, archive);
    when(packageRepository.getPackage(PACKAGE))
        .thenReturn(
            new SkillPackage(
                PACKAGE,
                "dev tools",
                HEAD,
                blobId,
                List.of(new SkillManifestEntry("dev", "d"), new SkillManifestEntry("review", "r")),
                Instant.parse("2024-01-01T00:00:00Z"),
                0L,
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-01-01T00:00:00Z"),
                null));
    when(blobManager.getBlob(blobId)).thenReturn(artifact);

    List<SkillPackageFileDTO> files = service.listFiles(PACKAGE);

    assertEquals(
        List.of("dev/SKILL.md", "review/SKILL.md"),
        files.stream().map(SkillPackageFileDTO::getPath).toList());
    for (SkillPackageFileDTO file : files) {
      long expected =
          archive.index().stream()
              .filter(entry -> entry.path().equals(file.getPath()))
              .findFirst()
              .orElseThrow()
              .size();
      assertEquals(expected, file.getSize());
    }
  }

  @Test
  void listFilesFailsClosedWithoutAServerIndex() {
    // 意图：缺少服务端索引是数据损坏，绝不由客户端内容或文件名回退推断。
    UUID blobId = UUID.randomUUID();
    StorageBlob artifact = blob(blobId, ARTIFACT_SHA, 10);
    when(packageRepository.getPackage(PACKAGE)).thenReturn(uploadPackageWith(blobId, ARTIFACT_SHA));
    when(blobManager.getBlob(blobId)).thenReturn(artifact);

    assertThrows(IllegalStateException.class, () -> service.listFiles(PACKAGE));
  }

  @Test
  void editUploadPackageUpdatesDescriptionWithCas() {
    // 意图：上传渠道允许改描述，且必须命中客户端展示的 version。
    when(packageRepository.lockPackage(PACKAGE)).thenReturn(uploadPackage(3));
    when(packageRepository.updateDescription(PACKAGE, "updated", 3)).thenReturn(true);
    SkillPackage updated = uploadPackage(4);
    when(packageRepository.getPackage(PACKAGE)).thenReturn(updated);
    when(blobManager.getBlob(updated.artifactBlobId()))
        .thenReturn(blob(updated.artifactBlobId(), ARTIFACT_SHA, 10));

    SkillPackageDetailDTO detail = service.editPackage(PACKAGE, editDTO("3", "updated"));

    assertEquals("UPLOAD", detail.getSource());
    verify(packageRepository).updateDescription(PACKAGE, "updated", 3);
    verify(packageRepository, never()).updateGitSettings(any(), any(), any());
  }

  @Test
  void editUploadPackageRejectsGitFields() {
    // 意图：上传渠道传入分支或令牌是无效契约，必须在写入前拒绝。
    when(packageRepository.lockPackage(PACKAGE)).thenReturn(uploadPackage(3));
    SkillPackageEditDTO branchEdit = editDTO("3", "updated");
    branchEdit.setBranch("main");
    assertThrows(AiValidationException.class, () -> service.editPackage(PACKAGE, branchEdit));
    SkillPackageEditDTO tokenEdit = editDTO("3", "updated");
    tokenEdit.applyToken(null);
    assertThrows(AiValidationException.class, () -> service.editPackage(PACKAGE, tokenEdit));
    verify(packageRepository, never()).updateDescription(any(), any(), anyLong());
  }

  @Test
  void editStalePackageIsRejectedBeforeAnyWrite() {
    // 意图：统一编辑沿用 CAS version，陈旧卡片不会改写描述或 Git 设置。
    when(packageRepository.lockPackage(PACKAGE)).thenReturn(gitPackage(2, HEAD, HEAD));

    assertThrows(
        AiVersionConflictException.class,
        () -> service.editPackage(PACKAGE, editDTO("1", "stale")));
    verify(packageRepository, never()).updateDescription(any(), any(), anyLong());
    verify(packageRepository, never()).updateGitSettings(any(), any(), any());
  }

  @Test
  void createGitPackagePublishesTheResolvedHead() {
    // 意图：创建即发布检查到的 HEAD，并把规范化归档作为暂存上传消费后绑定为当前版本。
    SkillArchiveBuilder.Result archive = archive("dev", "review");
    UUID stagingUploadId = UUID.randomUUID();
    UUID blobId = UUID.randomUUID();
    when(gitRepository.resolveBranchHead("https://git.example.com/repo.git", "main", "token"))
        .thenReturn(HEAD);
    when(gitRepository.buildArchive("https://git.example.com/repo.git", HEAD, "token"))
        .thenReturn(archive);
    when(uploadService.stage(eq(PACKAGE + ".zip"), eq("application/zip"), any(), anyLong()))
        .thenReturn(staged(stagingUploadId, blobId));
    when(uploadService.lockReady(stagingUploadId))
        .thenReturn(new StorageUploadService.ReadyUpload(blobId, PACKAGE + ".zip"));
    when(blobManager.retain(blobId)).thenReturn(blob(blobId, ARTIFACT_SHA, archive.zip().length));
    when(blobManager.getBlob(blobId)).thenReturn(blob(blobId, ARTIFACT_SHA, archive.zip().length));
    AtomicReference<SkillPackage> stored = new AtomicReference<>();
    when(packageRepository.insertPackage(any()))
        .thenAnswer(
            invocation -> {
              stored.set(invocation.getArgument(0));
              return true;
            });
    when(packageRepository.getPackage(PACKAGE)).thenAnswer(invocation -> stored.get());

    SkillPackageDetailDTO detail = service.createGitPackage(createDTO("token"));

    assertEquals(HEAD, detail.getPackageVersion());
    assertEquals("GIT", detail.getSource());
    assertEquals(HEAD, detail.getHeadCommit());
    assertTrue(detail.getGit().isHasToken());
    assertFalse(detail.isUpdateAvailable());
    assertEquals(
        List.of("dev", "review"), detail.getSkills().stream().map(s -> s.getName()).toList());
    assertEquals("0", detail.getVersion());
    assertEquals(ARTIFACT_SHA, detail.getArtifactSha256());
    verify(blobManager).retain(blobId);
    verify(uploadService).delete(stagingUploadId);
    verify(blobRepository)
        .updateArchiveIndex(eq(blobId), eq(SkillArchiveIndex.from(archive.index()).encode()));
  }

  @Test
  void createGitPackageRejectsAnExistingName() {
    when(packageRepository.getPackage(PACKAGE)).thenReturn(uploadPackage(3));

    assertThrows(AiDuplicateException.class, () -> service.createGitPackage(createDTO(null)));
    verify(gitRepository, never()).resolveBranchHead(anyString(), anyString(), any());
  }

  @Test
  void checkGitPackageKeepsVersionAndRecordsBoundedFailure() {
    // 意图：检查失败只记录有界错误，不改变当前版本，也不推进乐观锁版本。
    SkillPackage current = gitPackage(4, HEAD, OLD_HEAD);
    when(packageRepository.getPackage(PACKAGE)).thenReturn(current);
    when(packageRepository.lockPackage(PACKAGE)).thenReturn(current);
    when(packageRepository.updateGitObservation(eq(PACKAGE), eq(OLD_HEAD), any(), eq("remote")))
        .thenReturn(true);
    when(blobManager.getBlob(current.artifactBlobId()))
        .thenReturn(blob(current.artifactBlobId(), ARTIFACT_SHA, 10));
    when(gitRepository.resolveBranchHead(anyString(), anyString(), any()))
        .thenThrow(new SkillGitException("remote\nerror"));

    service.checkGitPackage(PACKAGE, checkDTO("4"));

    verify(packageRepository).updateGitObservation(eq(PACKAGE), eq(OLD_HEAD), any(), eq("remote"));
    verify(packageRepository, never()).updatePublication(any(), anyLong());
  }

  @Test
  void checkGitPackageRejectsAStaleVersion() {
    when(packageRepository.getPackage(PACKAGE)).thenReturn(gitPackage(5, HEAD, HEAD));

    assertThrows(
        AiVersionConflictException.class, () -> service.checkGitPackage(PACKAGE, checkDTO("4")));
  }

  @Test
  void publishGitPackageRequiresTheCheckedHead() {
    when(packageRepository.getPackage(PACKAGE)).thenReturn(gitPackage(1, HEAD, OLD_HEAD));

    assertThrows(
        AiValidationException.class,
        () -> service.publishGitPackage(PACKAGE, publishDTO("1", HEAD)));
    verify(gitRepository, never()).buildArchive(anyString(), anyString(), any());
  }

  @Test
  void publishGitPackageSwitchesArtifactAndReleasesTheOldOne() {
    // 意图：发布在短事务内切换归档、释放旧引用并消费暂存上传。
    SkillPackage current = gitPackage(2, OLD_HEAD, HEAD);
    SkillArchiveBuilder.Result archive = archive("dev");
    UUID stagingUploadId = UUID.randomUUID();
    UUID newBlobId = UUID.randomUUID();
    when(packageRepository.getPackage(PACKAGE)).thenReturn(current);
    when(packageRepository.lockPackage(PACKAGE)).thenReturn(current);
    when(gitRepository.buildArchive(anyString(), eq(HEAD), any())).thenReturn(archive);
    when(uploadService.stage(anyString(), anyString(), any(), anyLong()))
        .thenReturn(staged(stagingUploadId, newBlobId));
    when(uploadService.lockReady(stagingUploadId))
        .thenReturn(new StorageUploadService.ReadyUpload(newBlobId, PACKAGE + ".zip"));
    when(blobManager.retain(newBlobId)).thenReturn(blob(newBlobId, NEW_ARTIFACT_SHA, 10));
    when(blobManager.getBlob(newBlobId)).thenReturn(blob(newBlobId, NEW_ARTIFACT_SHA, 10));
    when(packageRepository.updatePublication(any(), eq(2L)))
        .thenAnswer(
            invocation -> {
              SkillPackage published = invocation.getArgument(0);
              when(packageRepository.getPackage(PACKAGE)).thenReturn(published);
              return true;
            });
    when(packageRepository.updateGitObservation(eq(PACKAGE), eq(HEAD), any(), eq(null)))
        .thenReturn(true);

    SkillPackageDetailDTO detail = service.publishGitPackage(PACKAGE, publishDTO("2", HEAD));

    assertEquals(HEAD, detail.getPackageVersion());
    assertEquals(NEW_ARTIFACT_SHA, detail.getArtifactSha256());
    verify(blobManager).retain(newBlobId);
    verify(blobManager).release(current.artifactBlobId());
    verify(uploadService).delete(stagingUploadId);
  }

  @Test
  void publishGitPackageCasConflictDoesNotReleaseTheOldArtifact() {
    SkillPackage current = gitPackage(2, OLD_HEAD, HEAD);
    when(packageRepository.getPackage(PACKAGE)).thenReturn(current);
    when(packageRepository.lockPackage(PACKAGE)).thenReturn(current);
    when(gitRepository.buildArchive(anyString(), eq(HEAD), any())).thenReturn(archive("dev"));
    UUID stagingUploadId = UUID.randomUUID();
    UUID newBlobId = UUID.randomUUID();
    when(uploadService.stage(anyString(), anyString(), any(), anyLong()))
        .thenReturn(staged(stagingUploadId, newBlobId));
    when(uploadService.lockReady(stagingUploadId))
        .thenReturn(new StorageUploadService.ReadyUpload(newBlobId, PACKAGE + ".zip"));
    when(blobManager.retain(newBlobId)).thenReturn(blob(newBlobId, NEW_ARTIFACT_SHA, 10));
    when(packageRepository.updatePublication(any(), eq(2L))).thenReturn(false);

    assertThrows(
        AiVersionConflictException.class,
        () -> service.publishGitPackage(PACKAGE, publishDTO("2", HEAD)));

    verify(blobManager, never()).release(current.artifactBlobId());
    // 冲突后暂存对象被取消，不会留下无主归档。
    verify(uploadService, atLeastOnce()).delete(stagingUploadId);
  }

  @Test
  void deleteRejectsSkillsStillReferencedByAgents() {
    SkillPackage current = uploadPackage(1);
    when(packageRepository.lockPackage(PACKAGE)).thenReturn(current);
    when(agentDefinitionRepository.existsReferencingSkill(PACKAGE, "dev")).thenReturn(true);

    assertThrows(AiInUseException.class, () -> service.deletePackage(PACKAGE, "1"));
    verify(packageRepository, never()).deletePackage(anyString(), anyLong());
  }

  @Test
  void previewUploadBuildsCanonicalArchiveAndStagesIt() throws IOException {
    // 意图：上传渠道读取原始压缩包、走同一个构建器，并把规范化结果保留为暂存上传。
    SkillArchiveBuilder.Result archive = archive("dev");
    UUID rawUploadId = UUID.randomUUID();
    UUID rawBlobId = UUID.randomUUID();
    UUID stagingUploadId = UUID.randomUUID();
    UUID stagedBlobId = UUID.randomUUID();
    when(uploadService.peekReady(rawUploadId))
        .thenReturn(new StorageUploadService.ReadyUpload(rawBlobId, "skills.zip"));
    when(uploadService.peekReady(stagingUploadId))
        .thenReturn(new StorageUploadService.ReadyUpload(stagedBlobId, PACKAGE + ".zip"));
    when(contentService.readBlobContent(rawBlobId, SkillArchiveBuilder.MAX_ARCHIVE_BYTES))
        .thenReturn(content(rawBlobId, archive.zip()));
    when(uploadService.stage(eq(PACKAGE + ".zip"), eq("application/zip"), any(), anyLong()))
        .thenReturn(staged(stagingUploadId, stagedBlobId));
    when(blobManager.getBlob(stagedBlobId))
        .thenReturn(blob(stagedBlobId, ARTIFACT_SHA, archive.zip().length));

    SkillPackagePreviewDTO preview = service.previewUpload(uploadPreviewDTO(rawUploadId));

    assertEquals(stagingUploadId.toString(), preview.getStagingUploadId());
    assertEquals("v1", preview.getPackageVersion());
    assertEquals(1, preview.getFileCount());
    assertEquals(archive.index().get(0).size(), preview.getContentSizeBytes());
    assertEquals(ARTIFACT_SHA, preview.getArchiveSha256());
    verify(uploadService).delete(rawUploadId);
    verify(blobRepository).updateArchiveIndex(eq(stagedBlobId), anyString());
  }

  @Test
  void previewUploadRejectsNonArchiveBytes() {
    UUID rawUploadId = UUID.randomUUID();
    UUID rawBlobId = UUID.randomUUID();
    when(uploadService.peekReady(rawUploadId))
        .thenReturn(new StorageUploadService.ReadyUpload(rawBlobId, "notes.txt"));
    when(contentService.readBlobContent(rawBlobId, SkillArchiveBuilder.MAX_ARCHIVE_BYTES))
        .thenReturn(content(rawBlobId, "not a zip".getBytes(StandardCharsets.UTF_8)));

    assertThrows(
        AiValidationException.class, () -> service.previewUpload(uploadPreviewDTO(rawUploadId)));
    verify(uploadService, never()).stage(anyString(), anyString(), any(), anyLong());
  }

  @Test
  void publishUploadCreatesANewPackageAndConsumesTheStagingUpload() throws IOException {
    SkillArchiveBuilder.Result archive = archive("dev", "review");
    UUID stagingUploadId = UUID.randomUUID();
    UUID stagedBlobId = UUID.randomUUID();
    UUID artifactBlobId = UUID.randomUUID();
    when(uploadService.peekReady(stagingUploadId))
        .thenReturn(new StorageUploadService.ReadyUpload(stagingUploadId, PACKAGE + ".zip"));
    when(blobManager.getBlob(stagingUploadId))
        .thenReturn(indexedBlob(stagingUploadId, ARTIFACT_SHA, archive));
    when(contentService.readBlobContent(stagingUploadId, SkillArchiveBuilder.MAX_ARCHIVE_BYTES))
        .thenReturn(content(stagingUploadId, archive.zip()));
    when(uploadService.lockReady(stagingUploadId))
        .thenReturn(new StorageUploadService.ReadyUpload(artifactBlobId, PACKAGE + ".zip"));
    when(blobManager.retain(artifactBlobId))
        .thenReturn(blob(artifactBlobId, ARTIFACT_SHA, archive.zip().length));
    when(blobManager.getBlob(artifactBlobId))
        .thenReturn(blob(artifactBlobId, ARTIFACT_SHA, archive.zip().length));
    when(packageRepository.lockPackage(PACKAGE)).thenReturn(null);
    when(packageRepository.insertPackage(any())).thenReturn(true);
    when(packageRepository.getPackage(PACKAGE))
        .thenReturn(null, uploadPackageWith(artifactBlobId, ARTIFACT_SHA));

    SkillPackageDetailDTO detail = service.publishUpload(uploadPublishDTO(stagingUploadId, null));

    assertEquals("UPLOAD", detail.getSource());
    assertNull(detail.getGit());
    assertEquals("v1", detail.getPackageVersion());
    verify(blobManager).retain(artifactBlobId);
    verify(uploadService).delete(stagingUploadId);
  }

  @Test
  void publishUploadRejectsReplacingAGitPackage() {
    when(packageRepository.getPackage(PACKAGE)).thenReturn(gitPackage(1, HEAD, HEAD));

    assertThrows(
        AiValidationException.class,
        () -> service.publishUpload(uploadPublishDTO(UUID.randomUUID(), "1")));
  }

  @Test
  void publishUploadReplacesAnUploadPackageAndReleasesTheOldArtifact() throws IOException {
    SkillArchiveBuilder.Result archive = archive("dev");
    SkillPackage current = uploadPackage(3);
    UUID stagingUploadId = UUID.randomUUID();
    UUID stagedBlobId = UUID.randomUUID();
    UUID artifactBlobId = UUID.randomUUID();
    when(packageRepository.getPackage(PACKAGE))
        .thenReturn(current, uploadPackageWith(artifactBlobId, NEW_ARTIFACT_SHA));
    when(packageRepository.lockPackage(PACKAGE)).thenReturn(current);
    when(uploadService.peekReady(stagingUploadId))
        .thenReturn(new StorageUploadService.ReadyUpload(stagedBlobId, PACKAGE + ".zip"));
    when(blobManager.getBlob(stagedBlobId))
        .thenReturn(indexedBlob(stagedBlobId, NEW_ARTIFACT_SHA, archive));
    when(contentService.readBlobContent(stagedBlobId, SkillArchiveBuilder.MAX_ARCHIVE_BYTES))
        .thenReturn(content(stagedBlobId, archive.zip()));
    when(uploadService.lockReady(stagingUploadId))
        .thenReturn(new StorageUploadService.ReadyUpload(artifactBlobId, PACKAGE + ".zip"));
    when(blobManager.retain(artifactBlobId))
        .thenReturn(blob(artifactBlobId, NEW_ARTIFACT_SHA, archive.zip().length));
    when(packageRepository.updatePublication(any(), eq(3L))).thenReturn(true);
    when(blobManager.getBlob(artifactBlobId))
        .thenReturn(blob(artifactBlobId, NEW_ARTIFACT_SHA, archive.zip().length));

    service.publishUpload(uploadPublishDTO(stagingUploadId, "3"));

    verify(blobManager).release(current.artifactBlobId());
    verify(uploadService).delete(stagingUploadId);
  }

  @Test
  void cancelUploadReleasesTheStagingUpload() {
    UUID stagingUploadId = UUID.randomUUID();

    service.cancelUpload(stagingUploadId.toString());

    verify(uploadService).delete(stagingUploadId);
  }

  @Test
  void listEnvironmentSyncProjectsInstalledDigest() {
    SkillPackage current = uploadPackageWith(UUID.randomUUID(), ARTIFACT_SHA);
    when(packageRepository.getPackage(PACKAGE)).thenReturn(current);
    when(blobManager.getBlob(current.artifactBlobId()))
        .thenReturn(blob(current.artifactBlobId(), ARTIFACT_SHA, 10));
    EnvironmentConnection installed =
        connection(ARTIFACT_SHA, "/home/dev/.kk-studio/skills/" + PACKAGE);
    EnvironmentConnection stale = connection("c".repeat(64), "/tmp/" + PACKAGE);
    when(environmentRegistry.list()).thenReturn(List.of(installed, stale));

    var states = service.listEnvironmentSync(PACKAGE);

    assertEquals(2, states.size());
    assertTrue(states.get(0).isUpToDate());
    assertFalse(states.get(1).isUpToDate());
    assertEquals(ARTIFACT_SHA, states.get(0).getInstalledSha256());
  }

  private static SkillPackageGitCreateDTO createDTO(String token) {
    SkillPackageGitCreateDTO dto = new SkillPackageGitCreateDTO();
    dto.setPackageName(PACKAGE);
    dto.setDescription("dev tools");
    dto.setRepositoryUrl("https://git.example.com/repo.git");
    dto.setBranch("main");
    dto.setToken(token);
    return dto;
  }

  private static SkillPackageEditDTO editDTO(String expectedVersion, String description) {
    SkillPackageEditDTO dto = new SkillPackageEditDTO();
    dto.setExpectedVersion(expectedVersion);
    dto.setDescription(description);
    return dto;
  }

  private static SkillPackageCheckDTO checkDTO(String expectedVersion) {
    SkillPackageCheckDTO dto = new SkillPackageCheckDTO();
    dto.setExpectedVersion(expectedVersion);
    return dto;
  }

  private static SkillPackageGitPublishDTO publishDTO(String expectedVersion, String targetCommit) {
    SkillPackageGitPublishDTO dto = new SkillPackageGitPublishDTO();
    dto.setExpectedVersion(expectedVersion);
    dto.setTargetCommit(targetCommit);
    return dto;
  }

  private static SkillPackageUploadPreviewDTO uploadPreviewDTO(UUID uploadId) {
    SkillPackageUploadPreviewDTO dto = new SkillPackageUploadPreviewDTO();
    dto.setUploadId(uploadId.toString());
    dto.setPackageName(PACKAGE);
    dto.setPackageVersion("v1");
    dto.setDescription("uploaded");
    return dto;
  }

  private static SkillPackageUploadPublishDTO uploadPublishDTO(
      UUID stagingUploadId, String expectedVersion) {
    SkillPackageUploadPublishDTO dto = new SkillPackageUploadPublishDTO();
    dto.setStagingUploadId(stagingUploadId.toString());
    dto.setPackageName(PACKAGE);
    dto.setPackageVersion("v1");
    dto.setDescription("uploaded");
    dto.setExpectedVersion(expectedVersion);
    return dto;
  }

  private static SkillPackage gitPackage(long version, String packageVersion, String headCommit) {
    return new SkillPackage(
        PACKAGE,
        "dev tools",
        packageVersion,
        UUID.randomUUID(),
        List.of(new SkillManifestEntry("dev", "d")),
        Instant.parse("2024-01-01T00:00:00Z"),
        version,
        Instant.parse("2024-01-01T00:00:00Z"),
        Instant.parse("2024-01-01T00:00:00Z"),
        new SkillPackageGitSource(
            "https://git.example.com/repo.git",
            "main",
            "token",
            headCommit,
            Instant.parse("2024-01-01T00:00:00Z"),
            null));
  }

  private static SkillPackage uploadPackage(long version) {
    return new SkillPackage(
        PACKAGE,
        "uploaded",
        "v1",
        UUID.randomUUID(),
        List.of(new SkillManifestEntry("dev", "d")),
        Instant.parse("2024-01-01T00:00:00Z"),
        version,
        Instant.parse("2024-01-01T00:00:00Z"),
        Instant.parse("2024-01-01T00:00:00Z"),
        null);
  }

  private static SkillPackage uploadPackageWith(UUID artifactBlobId, String sha) {
    return new SkillPackage(
        PACKAGE,
        "uploaded",
        "v1",
        artifactBlobId,
        List.of(new SkillManifestEntry("dev", "d")),
        Instant.parse("2024-01-01T00:00:00Z"),
        0L,
        Instant.parse("2024-01-01T00:00:00Z"),
        Instant.parse("2024-01-01T00:00:00Z"),
        null);
  }

  private static StorageBlob blob(UUID id, String sha, long size) {
    StorageBlob blob = new StorageBlob();
    blob.setId(id);
    blob.setSha256(sha);
    blob.setSizeBytes(size);
    blob.setMediaType("application/zip");
    blob.setRefCount(1);
    blob.setState(StorageBlobState.ACTIVE);
    return blob;
  }

  private static StorageBlob indexedBlob(UUID id, String sha, SkillArchiveBuilder.Result archive) {
    StorageBlob blob = blob(id, sha, archive.zip().length);
    blob.setArchiveIndex(SkillArchiveIndex.from(archive.index()).encode());
    return blob;
  }

  private static StorageBlobContent content(UUID id, byte[] bytes) {
    return new StorageBlobContent(id, bytes, "application/zip", bytes.length);
  }

  private static StorageUploadService.StagedUpload staged(UUID uploadId, UUID blobId) {
    return new StorageUploadService.StagedUpload(
        uploadId, blobId, PACKAGE + ".zip", "application/zip", 10, ARTIFACT_SHA);
  }

  private static EnvironmentConnection connection(String installedSha256, String localPath) {
    return new EnvironmentConnection(
        EnvironmentId.of(UUID.randomUUID()),
        UUID.randomUUID(),
        UUID.randomUUID(),
        LiveEnvironmentStatus.CONNECTING,
        null,
        List.of(EnvironmentSkillState.installed(PACKAGE, installedSha256, localPath)),
        List.of(),
        Instant.parse("2024-01-01T00:00:00Z"),
        Instant.parse("2024-01-01T00:05:00Z"));
  }

  private static SkillArchiveBuilder.Result archive(String... skills) {
    List<SkillArchiveBuilder.InputEntry> entries = new ArrayList<>();
    for (String skill : skills) {
      entries.add(
          new SkillArchiveBuilder.InputEntry(
              skill + "/SKILL.md",
              SkillArchiveBuilder.Type.FILE,
              false,
              ("---\nname: " + skill + "\ndescription: d-" + skill + "\n---\n")
                  .getBytes(StandardCharsets.UTF_8)));
    }
    try {
      return new SkillArchiveBuilder().fromEntries(entries);
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }
}
