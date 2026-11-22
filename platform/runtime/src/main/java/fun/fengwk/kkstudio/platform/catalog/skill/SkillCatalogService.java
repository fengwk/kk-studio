package fun.fengwk.kkstudio.platform.catalog.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentRepository;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillArchiveDownloadDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageCheckDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageDetailDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageEditDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageFileDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackagePublishDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageStagingCreateDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageStagingDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageStagingPublishDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageSummaryDTO;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveBuilder;
import fun.fengwk.kkstudio.platform.catalog.skill.archive.SkillArchiveIndex;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitException;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitRepository;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.secret.SecretUpdate;
import fun.fengwk.kkstudio.platform.storage.dto.StoragePresignedUrlDTO;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageBlobRepository;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Platform 全局 Skill Package 的应用服务：当前版本模型、两个发布渠道、暂存预览与按摘要的环境对账投影。
 *
 * <p>技能包是「名称 + 当前版本」。Git 渠道用 {@code ls-remote} 观察 branch HEAD，发布只能使用检查时看到的 exact commit；上传渠道读取原始
 * zip/tar.gz 后走同一个规范化构建器。两个渠道都把构建结果先落成暂存上传对象，发布时切换当前版本、释放旧归档引用并消费暂存对象。Git 网络与归档构建全程在事务之外完成；HTTP 发布由
 * {@link SkillCatalogWrites} 开启短写事务，配置导入则加入调用方的整份导入事务。
 *
 * <p>归档暂存与引用生命周期在 {@link SkillPackageArchiveStaging}，请求字段的 canonical 规则在 {@link
 * SkillPackageInputs}，只读投影在 {@link SkillPackageProjection}；本类只负责编排每个渠道的操作顺序。
 */
@Slf4j
@Service
public class SkillCatalogService {

  private static final int MAX_CHECK_ERROR_BYTES = 4096;

  private final SkillPackageRepository skillPackageRepository;
  private final AgentRepository agentRepository;
  private final SkillGitRepository skillGitRepository;
  private final AgentEditableSupport editableSupport;
  private final SkillCatalogWrites writes;
  private final StorageUploadService uploadService;
  private final StorageBlobManager blobManager;
  private final SkillPackageArchiveStaging staging;

  public SkillCatalogService(
      SkillPackageRepository skillPackageRepository,
      AgentRepository agentRepository,
      SkillGitRepository skillGitRepository,
      AgentEditableSupport editableSupport,
      SkillCatalogWrites writes,
      StorageUploadService uploadService,
      StorageBlobContentService blobContentService,
      StorageBlobManager blobManager,
      StorageBlobRepository blobRepository) {
    this.skillPackageRepository =
        Objects.requireNonNull(skillPackageRepository, "skillPackageRepository");
    this.agentRepository = Objects.requireNonNull(agentRepository, "agentRepository");
    this.skillGitRepository = Objects.requireNonNull(skillGitRepository, "skillGitRepository");
    this.editableSupport = Objects.requireNonNull(editableSupport, "editableSupport");
    this.writes = Objects.requireNonNull(writes, "writes");
    this.uploadService = Objects.requireNonNull(uploadService, "uploadService");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
    this.staging =
        new SkillPackageArchiveStaging(
            uploadService, blobContentService, blobManager, blobRepository);
  }

  /** 全部 Package 摘要，按 {@code packageName} 升序。 */
  public List<SkillPackageSummaryDTO> listPackages() {
    return SkillPackageProjection.toSummaries(skillPackageRepository.listPackages());
  }

  /** 单个 Package 详情；不存在时 404。 */
  public SkillPackageDetailDTO getPackage(String packageName) {
    return details(requirePackage(packageName));
  }

  /** 当前版本归档的预签名下载信息。 */
  public SkillArchiveDownloadDTO archiveDownload(String packageName) {
    SkillPackage skillPackage = requirePackage(packageName);
    StorageBlob artifact = requireArtifact(skillPackage);
    StoragePresignedUrlDTO signed = blobManager.presignOriginalUrl(artifact.getId());
    SkillArchiveDownloadDTO dto = new SkillArchiveDownloadDTO();
    dto.setFilename(archiveFilename(skillPackage));
    dto.setSha256(artifact.getSha256());
    dto.setSizeBytes(artifact.getSizeBytes());
    dto.setDownload(signed);
    return dto;
  }

  /** 新建 Git 渠道 Package，并发布当时解析出的 branch HEAD。 */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public SkillPackageDetailDTO createGitPackage(SkillPackageCreateDTO createDTO) {
    requireBody(createDTO != null);
    String packageName = SkillPackageInputs.requirePackageName(createDTO.getPackageName());
    String description = description(createDTO.getDescription());
    String repositoryUrl = SkillPackageInputs.requireRepositoryUrl(createDTO.getRepositoryUrl());
    String branch = SkillPackageInputs.requireBranch(createDTO.getBranch());
    String token = SkillPackageInputs.createToken(createDTO.getToken());
    if (skillPackageRepository.getPackage(packageName) != null) {
      throw DomainException.duplicate(
          SkillPackageInputs.RESOURCE, "skill package already exists: " + packageName);
    }
    String commit = git(() -> skillGitRepository.resolveBranchHead(repositoryUrl, branch, token));
    SkillArchiveBuilder.Result archive =
        git(() -> skillGitRepository.buildArchive(repositoryUrl, commit, token));
    UUID stagingUploadId = staging.stage(packageName, archive);
    try {
      return writes.execute(
          () ->
              insertGitPackage(
                  packageName,
                  description,
                  new SkillPackageGitSource(
                      repositoryUrl, branch, token, commit, Instant.now(), null),
                  archive,
                  stagingUploadId));
    } catch (RuntimeException error) {
      staging.cancelBestEffort(stagingUploadId);
      throw error;
    }
  }

  /** 只观察 branch HEAD，更新检查事实；失败时保留当前版本与上一次成功观察值。 */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public SkillPackageDetailDTO checkGitPackage(String packageName, SkillPackageCheckDTO checkDTO) {
    requireBody(checkDTO != null);
    long expected = SkillPackageInputs.requireExpectedVersion(checkDTO.getExpectedVersion());
    SkillPackage current = requireGitPackage(packageName);
    requireCurrentVersion(current, expected);
    GitObservation observation = observeBranchHead(current);
    return writes.execute(() -> recordGitObservation(packageName, expected, observation));
  }

  /** 发布最近一次检查到的 exact commit，并原子切换当前版本与归档。 */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public SkillPackageDetailDTO publishGitPackage(
      String packageName, SkillPackagePublishDTO publishDTO) {
    requireBody(publishDTO != null);
    long expected = SkillPackageInputs.requireExpectedVersion(publishDTO.getExpectedVersion());
    String targetCommit =
        SkillPackageInputs.requireCommit(publishDTO.getTargetCommit(), "targetCommit");
    SkillPackage current = requireGitPackage(packageName);
    requireCurrentVersion(current, expected);
    if (!targetCommit.equals(current.gitSource().headCommit())) {
      throw DomainException.validation(
          SkillPackageInputs.RESOURCE,
          "targetCommit must equal the checked branch head of this package: "
              + current.gitSource().headCommit());
    }
    SkillPackageGitSource gitSource = current.gitSource();
    SkillArchiveBuilder.Result archive =
        git(
            () ->
                skillGitRepository.buildArchive(
                    gitSource.repositoryUrl(), targetCommit, gitSource.token()));
    UUID stagingUploadId = staging.stage(packageName, archive);
    try {
      return writes.execute(
          () -> publishGitArchive(packageName, expected, targetCommit, archive, stagingUploadId));
    } catch (RuntimeException error) {
      staging.cancelBestEffort(stagingUploadId);
      throw error;
    }
  }

  /** 当前版本归档的文件清单；条目只来自服务端生成的 {@code storage_blob.archive_index}。 */
  public List<SkillPackageFileDTO> listFiles(String packageName) {
    SkillPackage skillPackage = requirePackage(packageName);
    StorageBlob artifact = requireArtifact(skillPackage);
    String encodedIndex = artifact.getArchiveIndex();
    if (encodedIndex == null) {
      throw new IllegalStateException("skill package archive has no server index: " + packageName);
    }
    return SkillPackageProjection.toFiles(SkillArchiveIndex.decode(encodedIndex));
  }

  /** 编辑技能包的描述与 Git 渠道设置；上传渠道只能修改描述。 */
  @Transactional
  public SkillPackageDetailDTO editPackage(String packageName, SkillPackageEditDTO editDTO) {
    requireBody(editDTO != null);
    long expected = SkillPackageInputs.requireExpectedVersion(editDTO.getExpectedVersion());
    SkillPackage current = requirePackageForUpdate(packageName);
    requireCurrentVersion(current, expected);
    String description = description(editDTO.getDescription());
    if (!current.isGit()) {
      return editUploadPackage(packageName, current, description, editDTO, expected);
    }
    return editGitPackage(packageName, current, description, editDTO, expected);
  }

  /** 用已完成的原始上传构建预览；原始上传被消费，规范化归档作为暂存上传保留。 */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public SkillPackageStagingDTO previewUpload(SkillPackageStagingCreateDTO previewDTO) {
    requireBody(previewDTO != null);
    String packageName = SkillPackageInputs.requirePackageName(previewDTO.getPackageName());
    String packageVersion = SkillPackageInputs.requireVersionText(previewDTO.getPackageVersion());
    String description = description(previewDTO.getDescription());
    UUID uploadId = previewDTO.getUploadId();
    SkillArchiveBuilder.Result archive = staging.readUploaded(uploadId);
    UUID stagingUploadId = staging.stage(packageName, archive);
    try {
      uploadService.delete(uploadId);
    } catch (RuntimeException error) {
      staging.cancelBestEffort(stagingUploadId);
      throw error;
    }
    StorageBlob staged = staging.requireStagedBlob(stagingUploadId);
    SkillPackageStagingDTO dto = new SkillPackageStagingDTO();
    dto.setId(stagingUploadId.toString());
    dto.setPackageName(packageName);
    dto.setPackageVersion(packageVersion);
    dto.setDescription(description);
    dto.setSkills(SkillPackageProjection.toSkills(archive.skills()));
    dto.setIgnoredDirectories(archive.ignoredDirectories());
    dto.setArchiveSha256(staged.getSha256());
    dto.setArchiveSizeBytes(staged.getSizeBytes());
    dto.setContentSizeBytes(
        archive.index().stream().mapToLong(SkillArchiveBuilder.IndexEntry::size).sum());
    dto.setFileCount(archive.index().size());
    return dto;
  }

  /** 发布暂存的上传归档；{@code expectedVersion} 为 null 表示新建上传渠道包。 */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public SkillPackageDetailDTO publishUpload(
      UUID stagingUploadId, SkillPackageStagingPublishDTO publishDTO) {
    requireBody(publishDTO != null);
    String packageName = SkillPackageInputs.requirePackageName(publishDTO.getPackageName());
    String packageVersion = SkillPackageInputs.requireVersionText(publishDTO.getPackageVersion());
    String description = description(publishDTO.getDescription());
    Long rawExpected = publishDTO.getExpectedVersion();
    SkillPackage existing = skillPackageRepository.getPackage(packageName);
    if (existing != null && existing.isGit()) {
      throw DomainException.validation(
          SkillPackageInputs.RESOURCE,
          "skill package is a Git package and cannot be replaced by an upload");
    }
    if (existing == null && rawExpected != null) {
      throw DomainException.versionConflict(SkillPackageInputs.RESOURCE, rawExpected, 0);
    }
    if (existing != null && rawExpected == null) {
      throw DomainException.validation(
          SkillPackageInputs.RESOURCE, "expectedVersion is required for an existing package");
    }
    long expected =
        rawExpected == null ? 0L : SkillPackageInputs.requireExpectedVersion(rawExpected);
    if (existing != null) {
      requireCurrentVersion(existing, expected);
    }
    List<SkillManifestEntry> skills = staging.readStaged(stagingUploadId).archive().skills();
    try {
      return writes.execute(
          () ->
              publishStagedUpload(
                  packageName,
                  packageVersion,
                  description,
                  expected,
                  existing != null,
                  skills,
                  stagingUploadId));
    } catch (RuntimeException error) {
      staging.cancelBestEffort(stagingUploadId);
      throw error;
    }
  }

  /** 取消未发布的暂存归档。 */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void cancelUpload(UUID stagingUploadId) {
    uploadService.delete(stagingUploadId);
  }

  /** 删除 Package；仍被 Agent {@code SkillRef} 引用时拒绝。 */
  @Transactional
  public void deletePackage(String packageName, long expected) {
    SkillPackage current = requirePackageForUpdate(packageName);
    requireCurrentVersion(current, expected);
    ensureSkillsRemovable(packageName, manifestNames(current.skills()));
    if (!skillPackageRepository.deletePackage(packageName, expected)) {
      throw conflictOrMissing(packageName, expected);
    }
    staging.release(current);
  }

  /** 配置导入发布所需的事实；暂存上传归调用方所有，失败时必须取消。 */
  public record PreparedGitImport(
      String packageName,
      String description,
      String repositoryUrl,
      String branch,
      String token,
      String commit,
      List<SkillManifestEntry> skills,
      UUID stagingUploadId) {
    public PreparedGitImport {
      skills = List.copyOf(skills);
    }

    @Override
    public String toString() {
      return "PreparedGitImport[packageName=" + packageName + ", commit=" + commit + "]";
    }
  }

  /** 在写事务之外解析 branch HEAD、构建归档并暂存。 */
  @Transactional(propagation = Propagation.NEVER)
  public PreparedGitImport prepareGitImport(
      String packageName, String description, String repositoryUrl, String branch, String token) {
    String name = SkillPackageInputs.requirePackageName(packageName);
    String canonicalDescription = description(description);
    String url = SkillPackageInputs.requireRepositoryUrl(repositoryUrl);
    String canonicalBranch = SkillPackageInputs.requireBranch(branch);
    String canonicalToken = SkillPackageInputs.canonicalImportToken(token);
    String commit =
        git(() -> skillGitRepository.resolveBranchHead(url, canonicalBranch, canonicalToken));
    SkillArchiveBuilder.Result archive =
        git(() -> skillGitRepository.buildArchive(url, commit, canonicalToken));
    UUID stagingUploadId = staging.stage(name, archive);
    return new PreparedGitImport(
        name,
        canonicalDescription,
        url,
        canonicalBranch,
        canonicalToken,
        commit,
        archive.skills(),
        stagingUploadId);
  }

  /** 加入调用方事务发布已准备的归档，并在行锁下重检渠道、URL 和技能引用。 */
  @Transactional(propagation = Propagation.MANDATORY)
  public void publishPreparedGitImport(PreparedGitImport prepared) {
    String name = prepared.packageName();
    SkillPackage current = skillPackageRepository.lockPackage(name);
    if (current != null && !current.isGit()) {
      throw DomainException.validation(
          SkillPackageInputs.RESOURCE,
          "uploaded skill package cannot be replaced by a Git import: " + name);
    }
    if (current != null && !prepared.repositoryUrl().equals(current.gitSource().repositoryUrl())) {
      throw DomainException.validation(
          SkillPackageInputs.RESOURCE, "skill package repositoryUrl is immutable: " + name);
    }
    if (current != null) {
      ensureSkillsRemovable(name, removedSkillNames(current, prepared.skills()));
    }
    UUID artifactBlobId = staging.consume(prepared.stagingUploadId());
    if (current == null) {
      insertImportedGitPackage(name, prepared, artifactBlobId);
      return;
    }
    updateImportedGitPackage(name, prepared, current, artifactBlobId);
  }

  /** 导入失败后在回滚事务之外尽力取消暂存上传；剩余上传由过期清理兜底。 */
  @Transactional(propagation = Propagation.NEVER)
  public void cancelPreparedGitImport(PreparedGitImport prepared) {
    staging.cancelBestEffort(prepared.stagingUploadId());
  }

  /** 只校验导入字段，不访问 Git 或写入数据库。 */
  public void validateImport(
      String packageName, String description, String repositoryUrl, String branch, String token) {
    SkillPackageInputs.requirePackageName(packageName);
    description(description);
    SkillPackageInputs.requireRepositoryUrl(repositoryUrl);
    SkillPackageInputs.requireBranch(branch);
    if (token != null && !token.isBlank()) {
      SkillPackageInputs.requireToken(token);
    }
  }

  /** 写事务内插入新的 Git 渠道 Package；归档 blob 与行必须同时生效。 */
  private SkillPackageDetailDTO insertGitPackage(
      String packageName,
      String description,
      SkillPackageGitSource gitSource,
      SkillArchiveBuilder.Result archive,
      UUID stagingUploadId) {
    UUID artifactBlobId = staging.consume(stagingUploadId);
    SkillPackage created =
        new SkillPackage(
            packageName,
            description,
            gitSource.headCommit(),
            artifactBlobId,
            archive.skills(),
            Instant.now(),
            0L,
            null,
            null,
            gitSource);
    try {
      if (!skillPackageRepository.insertPackage(created)) {
        throw new IllegalStateException("create skill package failed");
      }
    } catch (DuplicateKeyException error) {
      throw DomainException.duplicate(
          SkillPackageInputs.RESOURCE, "skill package already exists: " + packageName, error);
    }
    return getPackage(packageName);
  }

  /** 写事务内记录 branch HEAD 观察值；只有观察值变化时才写行。 */
  private SkillPackageDetailDTO recordGitObservation(
      String packageName, long expected, GitObservation observation) {
    SkillPackage locked = requireGitPackageForUpdate(packageName);
    requireCurrentVersion(locked, expected);
    Instant checkedAt = Instant.now();
    if (!Objects.equals(observation.headCommit(), locked.gitSource().headCommit())
        || !Objects.equals(observation.checkError(), locked.gitSource().checkError())) {
      if (!skillPackageRepository.updateGitObservation(
          packageName, observation.headCommit(), checkedAt, observation.checkError())) {
        throw DomainException.notFound(SkillPackageInputs.RESOURCE);
      }
    }
    return getPackage(packageName);
  }

  /** 写事务内切换 Git 渠道当前版本，随后同步观察值并释放旧归档引用。 */
  private SkillPackageDetailDTO publishGitArchive(
      String packageName,
      long expected,
      String targetCommit,
      SkillArchiveBuilder.Result archive,
      UUID stagingUploadId) {
    SkillPackage locked = requireGitPackageForUpdate(packageName);
    requireCurrentVersion(locked, expected);
    if (!targetCommit.equals(locked.gitSource().headCommit())) {
      throw DomainException.versionConflict(
          SkillPackageInputs.RESOURCE, expected, locked.version());
    }
    ensureSkillsRemovable(packageName, removedSkillNames(locked, archive.skills()));
    UUID artifactBlobId = staging.consume(stagingUploadId);
    SkillPackage published =
        new SkillPackage(
            locked.packageName(),
            locked.description(),
            targetCommit,
            artifactBlobId,
            archive.skills(),
            Instant.now(),
            locked.version(),
            locked.createdAt(),
            locked.updatedAt(),
            locked.gitSource());
    if (!skillPackageRepository.updatePublication(published, expected)) {
      throw conflictOrMissing(packageName, expected);
    }
    if (!skillPackageRepository.updateGitObservation(
        packageName, targetCommit, Instant.now(), null)) {
      throw DomainException.notFound(SkillPackageInputs.RESOURCE);
    }
    staging.release(locked);
    return getPackage(packageName);
  }

  /** 上传渠道只能改描述：branch 与 token 必须保持未设置。 */
  private SkillPackageDetailDTO editUploadPackage(
      String packageName,
      SkillPackage current,
      String description,
      SkillPackageEditDTO editDTO,
      long expected) {
    if (editDTO.getBranch() != null
        || editDTO.getToken().operation() != SecretUpdate.Operation.KEEP) {
      throw DomainException.validation(
          SkillPackageInputs.RESOURCE,
          "branch is only valid for Git packages and token must be KEEP");
    }
    if (!Objects.equals(description, current.description())) {
      if (!skillPackageRepository.updateEditable(packageName, description, null, expected)) {
        throw conflictOrMissing(packageName, expected);
      }
    }
    return getPackage(packageName);
  }

  /** Git 渠道可改描述、branch 与 token；三项都未变化时不写行，任一变化只推进一次 version。 */
  private SkillPackageDetailDTO editGitPackage(
      String packageName,
      SkillPackage current,
      String description,
      SkillPackageEditDTO editDTO,
      long expected) {
    String branch = SkillPackageInputs.requireBranch(editDTO.getBranch());
    TokenUpdate tokenUpdate = resolveToken(current.gitSource().token(), editDTO.getToken());
    boolean descriptionChanged = !Objects.equals(description, current.description());
    boolean gitChanged =
        !Objects.equals(branch, current.gitSource().branch()) || tokenUpdate.changed();
    if (descriptionChanged || gitChanged) {
      SkillPackageGitEdit gitEdit =
          gitChanged ? new SkillPackageGitEdit(branch, tokenUpdate.token()) : null;
      if (!skillPackageRepository.updateEditable(packageName, description, gitEdit, expected)) {
        throw conflictOrMissing(packageName, expected);
      }
    }
    return getPackage(packageName);
  }

  /** 写事务内按"新建或覆盖当前版本"发布上传渠道归档。 */
  private SkillPackageDetailDTO publishStagedUpload(
      String packageName,
      String packageVersion,
      String description,
      long expected,
      boolean existing,
      List<SkillManifestEntry> skills,
      UUID stagingUploadId) {
    SkillPackage locked = existing ? requirePackageForUpdate(packageName) : null;
    if (locked != null) {
      requireCurrentVersion(locked, expected);
      ensureSkillsRemovable(packageName, removedSkillNames(locked, skills));
    }
    UUID artifactBlobId = staging.consume(stagingUploadId);
    if (locked == null) {
      insertUploadPackage(packageName, packageVersion, description, artifactBlobId, skills);
      return getPackage(packageName);
    }
    publishUploadVersion(
        packageName, packageVersion, description, locked, artifactBlobId, skills, expected);
    return getPackage(packageName);
  }

  private void insertUploadPackage(
      String packageName,
      String packageVersion,
      String description,
      UUID artifactBlobId,
      List<SkillManifestEntry> skills) {
    SkillPackage created =
        new SkillPackage(
            packageName,
            description,
            packageVersion,
            artifactBlobId,
            skills,
            Instant.now(),
            0L,
            null,
            null,
            null);
    try {
      if (!skillPackageRepository.insertPackage(created)) {
        throw new IllegalStateException("create skill package failed");
      }
    } catch (DuplicateKeyException error) {
      throw DomainException.duplicate(
          SkillPackageInputs.RESOURCE, "skill package already exists: " + packageName, error);
    }
  }

  private void publishUploadVersion(
      String packageName,
      String packageVersion,
      String description,
      SkillPackage locked,
      UUID artifactBlobId,
      List<SkillManifestEntry> skills,
      long expected) {
    SkillPackage published =
        new SkillPackage(
            packageName,
            description,
            packageVersion,
            artifactBlobId,
            skills,
            Instant.now(),
            locked.version(),
            locked.createdAt(),
            locked.updatedAt(),
            null);
    if (!skillPackageRepository.updatePublication(published, expected)) {
      throw conflictOrMissing(packageName, expected);
    }
    staging.release(locked);
  }

  private void insertImportedGitPackage(
      String name, PreparedGitImport prepared, UUID artifactBlobId) {
    SkillPackage created =
        new SkillPackage(
            name,
            prepared.description(),
            prepared.commit(),
            artifactBlobId,
            prepared.skills(),
            Instant.now(),
            0L,
            null,
            null,
            new SkillPackageGitSource(
                prepared.repositoryUrl(),
                prepared.branch(),
                prepared.token(),
                prepared.commit(),
                Instant.now(),
                null));
    if (!skillPackageRepository.insertPackage(created)) {
      throw new IllegalStateException("import skill package failed: " + name);
    }
  }

  private void updateImportedGitPackage(
      String name, PreparedGitImport prepared, SkillPackage current, UUID artifactBlobId) {
    SkillPackage published =
        new SkillPackage(
            name,
            prepared.description(),
            prepared.commit(),
            artifactBlobId,
            prepared.skills(),
            Instant.now(),
            current.version(),
            current.createdAt(),
            current.updatedAt(),
            current.gitSource());
    if (!skillPackageRepository.updatePublication(
        published,
        new SkillPackageGitEdit(prepared.branch(), prepared.token()),
        current.version())) {
      throw conflictOrMissing(name, current.version());
    }
    if (!skillPackageRepository.updateGitObservation(
        name, prepared.commit(), Instant.now(), null)) {
      throw DomainException.notFound(SkillPackageInputs.RESOURCE);
    }
    staging.release(current);
  }

  /** 观察远端 branch HEAD；失败时保留上一次成功观察值并记录有界错误。 */
  private GitObservation observeBranchHead(SkillPackage current) {
    try {
      String headCommit =
          skillGitRepository.resolveBranchHead(
              current.gitSource().repositoryUrl(),
              current.gitSource().branch(),
              current.gitSource().token());
      return new GitObservation(headCommit, null);
    } catch (SkillGitException error) {
      // 检查失败只记录有界错误：当前版本与上一次成功观察值全部保持不变。
      return new GitObservation(
          current.gitSource().headCommit(),
          SkillPackageInputs.boundCheckError(error, MAX_CHECK_ERROR_BYTES));
    }
  }

  private void requireBody(boolean present) {
    if (!present) {
      throw DomainException.validation(
          SkillPackageInputs.RESOURCE, "request body must not be null");
    }
  }

  private String description(String raw) {
    String trimmed = editableSupport.trimToNull(raw);
    if (trimmed == null) {
      return null;
    }
    try {
      return SkillNames.canonicalDescription(trimmed);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation(
          SkillPackageInputs.RESOURCE, "invalid description: " + error.getMessage(), error);
    }
  }

  private SkillPackageDetailDTO details(SkillPackage skillPackage) {
    return SkillPackageProjection.toDetail(skillPackage, requireArtifact(skillPackage));
  }

  private SkillPackage requirePackage(String packageName) {
    SkillPackage current = skillPackageRepository.getPackage(packageName);
    if (current == null) {
      throw DomainException.notFound(SkillPackageInputs.RESOURCE);
    }
    return current;
  }

  private SkillPackage requireGitPackage(String packageName) {
    SkillPackage current = requirePackage(packageName);
    if (!current.isGit()) {
      throw DomainException.validation(
          SkillPackageInputs.RESOURCE, "skill package is not a Git package: " + packageName);
    }
    return current;
  }

  private SkillPackage requirePackageForUpdate(String packageName) {
    SkillPackage current = skillPackageRepository.lockPackage(packageName);
    if (current == null) {
      throw DomainException.notFound(SkillPackageInputs.RESOURCE);
    }
    return current;
  }

  private SkillPackage requireGitPackageForUpdate(String packageName) {
    SkillPackage current = requirePackageForUpdate(packageName);
    if (!current.isGit()) {
      throw DomainException.validation(
          SkillPackageInputs.RESOURCE, "skill package is not a Git package: " + packageName);
    }
    return current;
  }

  private StorageBlob requireArtifact(SkillPackage skillPackage) {
    StorageBlob blob = blobManager.getBlob(skillPackage.artifactBlobId());
    if (blob == null) {
      throw DomainException.notFound(SkillPackageInputs.RESOURCE);
    }
    return blob;
  }

  /** 拒绝让仍被任何 Agent {@code SkillRef} 引用的 Skill 从 Package 中消失。 */
  private void ensureSkillsRemovable(String packageName, Collection<String> removedSkillNames) {
    List<String> referenced =
        removedSkillNames.stream()
            .filter(name -> agentRepository.existsReferencingSkill(packageName, name))
            .sorted()
            .toList();
    if (!referenced.isEmpty()) {
      throw DomainException.inUse(
          SkillPackageInputs.RESOURCE,
          "skills are still referenced by agents: "
              + packageName
              + "/"
              + String.join(", ", referenced));
    }
  }

  private static List<String> removedSkillNames(
      SkillPackage current, List<SkillManifestEntry> published) {
    Set<String> names = new LinkedHashSet<>(manifestNames(published));
    return manifestNames(current.skills()).stream().filter(name -> !names.contains(name)).toList();
  }

  private static List<String> manifestNames(List<SkillManifestEntry> skills) {
    if (skills == null) {
      return List.of();
    }
    return skills.stream().map(SkillManifestEntry::name).toList();
  }

  private RuntimeException conflictOrMissing(String packageName, long expected) {
    SkillPackage reread = skillPackageRepository.getPackage(packageName);
    if (reread == null) {
      return DomainException.notFound(SkillPackageInputs.RESOURCE);
    }
    return DomainException.versionConflict(SkillPackageInputs.RESOURCE, expected, reread.version());
  }

  private static void requireCurrentVersion(SkillPackage skillPackage, long expected) {
    if (skillPackage.version() != expected) {
      throw DomainException.versionConflict(
          SkillPackageInputs.RESOURCE, expected, skillPackage.version());
    }
  }

  private static <T> T git(Supplier<T> action) {
    try {
      return action.get();
    } catch (SkillGitException error) {
      if (error.authenticationFailed()) {
        // 权限失败给出明确指导，且不回显 URL、令牌或远端自由文本；绝不静默尝试公共访问。
        throw DomainException.validation(
            SkillPackageInputs.RESOURCE,
            "repository authentication failed; verify the repository URL and, for a private repository, configure a valid access token",
            error);
      }
      throw DomainException.validation(
          SkillPackageInputs.RESOURCE,
          "cannot build the skill archive: " + error.getMessage(),
          error);
    }
  }

  private static String archiveFilename(SkillPackage skillPackage) {
    String version = skillPackage.packageVersion().replaceAll("[^A-Za-z0-9._-]", "_");
    return skillPackage.packageName() + "-" + version + ".zip";
  }

  private record GitObservation(String headCommit, String checkError) {}

  private record TokenUpdate(String token, boolean changed) {}

  private static TokenUpdate resolveToken(String storedToken, SecretUpdate update) {
    return switch (update.operation()) {
      case KEEP -> new TokenUpdate(storedToken, false);
      case CLEAR -> new TokenUpdate(null, storedToken != null);
      case SET -> {
        String token = SkillPackageInputs.requireToken(update.value());
        yield new TokenUpdate(token, !token.equals(storedToken));
      }
    };
  }
}
