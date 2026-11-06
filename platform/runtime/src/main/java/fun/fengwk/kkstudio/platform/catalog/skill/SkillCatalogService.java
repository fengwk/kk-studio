package fun.fengwk.kkstudio.platform.catalog.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.common.id.Uuids;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillArchiveDownloadDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillEnvironmentSyncDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillGitSourceDTO;
import fun.fengwk.kkstudio.platform.catalog.dto.SkillManifestEntryDTO;
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
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentSkillState;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.platform.secret.SecretConfiguredDTO;
import fun.fengwk.kkstudio.platform.secret.SecretUpdate;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageBlobRepository;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobContent;
import fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Platform 全局 Skill Package 的应用服务：当前版本模型、两个发布渠道、暂存预览与按摘要的环境对账投影。
 *
 * <p>技能包是「名称 + 当前版本」。Git 渠道用 {@code ls-remote} 观察 branch HEAD，发布只能使用检查时看到的 exact commit； 上传渠道读取原始
 * zip/tar.gz 后走同一个规范化构建器。两个渠道都把构建结果先落成暂存上传对象，发布时切换当前版本、释放旧归档引用并消费暂存对象。Git 网络与归档构建全程在事务之外完成；HTTP 发布由
 * {@link SkillCatalogWrites} 开启短写事务，配置导入则加入调用方的整份导入事务。
 */
@Slf4j
@Service
public class SkillCatalogService {

  private static final String RESOURCE = "skill_package";

  /** 40 或 64 位小写 hex object id。 */
  private static final Pattern COMMIT = Pattern.compile("^([0-9a-f]{40}|[0-9a-f]{64})$");

  /** Git repository URL：必须带受支持 scheme、无环绕空白、无控制字符、≤2048、不得内嵌 userinfo。 */
  private static final Pattern REPOSITORY_URL = Pattern.compile("^[a-z][a-z0-9+.-]*://[^\\s]+$");

  /** 本地 file 仓库可由 {@code File#toURI()} 产生单斜杠形式：{@code file:/path}。 */
  private static final Pattern FILE_URL = Pattern.compile("^file:/[^\\s]*$");

  /** 只有受网络保护覆盖的 http/https 与本地直读 file 允许进入 Git 网络层。 */
  private static final Set<String> SUPPORTED_REPOSITORY_SCHEMES = Set.of("http", "https", "file");

  private static final Pattern USERINFO = Pattern.compile("://[^/\\s]*@");

  private static final int MAX_REPOSITORY_URL_CHARS = 2048;
  private static final int MAX_BRANCH_CHARS = 255;
  private static final int MAX_VERSION_CHARS = 128;
  private static final int MAX_CHECK_ERROR_BYTES = 4096;

  private final SkillPackageRepository skillPackageRepository;
  private final AgentDefinitionRepository agentDefinitionRepository;
  private final SkillGitRepository skillGitRepository;
  private final AgentEditableSupport editableSupport;
  private final SkillCatalogWrites writes;
  private final StorageUploadService uploadService;
  private final StorageBlobContentService blobContentService;
  private final StorageBlobManager blobManager;
  private final StorageBlobRepository blobRepository;
  private final EnvironmentRegistry environmentRegistry;

  public SkillCatalogService(
      SkillPackageRepository skillPackageRepository,
      AgentDefinitionRepository agentDefinitionRepository,
      SkillGitRepository skillGitRepository,
      AgentEditableSupport editableSupport,
      SkillCatalogWrites writes,
      StorageUploadService uploadService,
      StorageBlobContentService blobContentService,
      StorageBlobManager blobManager,
      StorageBlobRepository blobRepository,
      EnvironmentRegistry environmentRegistry) {
    this.skillPackageRepository =
        Objects.requireNonNull(skillPackageRepository, "skillPackageRepository");
    this.agentDefinitionRepository =
        Objects.requireNonNull(agentDefinitionRepository, "agentDefinitionRepository");
    this.skillGitRepository = Objects.requireNonNull(skillGitRepository, "skillGitRepository");
    this.editableSupport = Objects.requireNonNull(editableSupport, "editableSupport");
    this.writes = Objects.requireNonNull(writes, "writes");
    this.uploadService = Objects.requireNonNull(uploadService, "uploadService");
    this.blobContentService = Objects.requireNonNull(blobContentService, "blobContentService");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
    this.blobRepository = Objects.requireNonNull(blobRepository, "blobRepository");
    this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry");
  }

  /** 全部 Package 摘要，按 {@code packageName} 升序。 */
  public List<SkillPackageSummaryDTO> listPackages() {
    return skillPackageRepository.listPackages().stream()
        .map(SkillCatalogService::toSummary)
        .toList();
  }

  /** 单个 Package 详情；不存在时 404。 */
  public SkillPackageDetailDTO getPackage(String packageName) {
    return toDetail(requirePackage(packageName));
  }

  /** 全部环境对该 Package 的安装摘要投影。 */
  public List<SkillEnvironmentSyncDTO> listEnvironmentSync(String packageName) {
    SkillPackage skillPackage = requirePackage(packageName);
    StorageBlob artifact = requireArtifact(skillPackage);
    return environmentRegistry.list().stream()
        .map(connection -> toEnvironmentSync(connection, skillPackage, artifact))
        .toList();
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
    if (createDTO == null) {
      throw DomainException.validation(RESOURCE, "request body must not be null");
    }
    String packageName = requirePackageName(createDTO.getPackageName());
    String description = requireDescription(createDTO.getDescription());
    String repositoryUrl = requireRepositoryUrl(createDTO.getRepositoryUrl());
    String branch = requireBranch(createDTO.getBranch());
    String token = tokenOf(createDTO.getToken());
    if (skillPackageRepository.getPackage(packageName) != null) {
      throw DomainException.duplicate(RESOURCE, "skill package already exists: " + packageName);
    }
    String commit = git(() -> skillGitRepository.resolveBranchHead(repositoryUrl, branch, token));
    SkillArchiveBuilder.Result archive =
        git(() -> skillGitRepository.buildArchive(repositoryUrl, commit, token));
    UUID stagingUploadId = stageArchive(packageName, archive);
    try {
      return writes.execute(
          () -> {
            SkillPackage created =
                new SkillPackage(
                    packageName,
                    description,
                    commit,
                    null,
                    archive.skills(),
                    Instant.now(),
                    0L,
                    null,
                    null,
                    new SkillPackageGitSource(
                        repositoryUrl, branch, token, commit, Instant.now(), null));
            UUID artifactBlobId = consumeStaging(stagingUploadId);
            SkillPackage withArtifact =
                new SkillPackage(
                    created.packageName(),
                    created.description(),
                    created.packageVersion(),
                    artifactBlobId,
                    created.skills(),
                    created.publishedAt(),
                    created.version(),
                    created.createdAt(),
                    created.updatedAt(),
                    created.gitSource());
            try {
              if (!skillPackageRepository.insertPackage(withArtifact)) {
                throw new IllegalStateException("create skill package failed");
              }
            } catch (DuplicateKeyException error) {
              throw DomainException.duplicate(
                  RESOURCE, "skill package already exists: " + packageName, error);
            }
            return getPackage(packageName);
          });
    } catch (RuntimeException error) {
      cancelStagingBestEffort(stagingUploadId);
      throw error;
    }
  }

  /** 只观察 branch HEAD，更新检查事实；失败时保留当前版本与上一次成功观察值。 */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public SkillPackageDetailDTO checkGitPackage(String packageName, SkillPackageCheckDTO checkDTO) {
    if (checkDTO == null) {
      throw DomainException.validation(RESOURCE, "request body must not be null");
    }
    String rawExpected = checkDTO.getExpectedVersion();
    long expected = requireExpectedVersion(rawExpected);
    SkillPackage current = requireGitPackage(packageName);
    requireCurrentVersion(current, rawExpected, expected);
    String headCommit;
    String checkError;
    try {
      headCommit =
          skillGitRepository.resolveBranchHead(
              current.gitSource().repositoryUrl(),
              current.gitSource().branch(),
              current.gitSource().token());
      checkError = null;
    } catch (SkillGitException error) {
      // 检查失败只记录有界错误：当前版本与上一次成功观察值全部保持不变。
      headCommit = current.gitSource().headCommit();
      checkError = boundCheckError(error);
    }
    String preparedHead = headCommit;
    String preparedError = checkError;
    return writes.execute(
        () -> {
          SkillPackage locked = requireGitPackageForUpdate(packageName);
          requireCurrentVersion(locked, rawExpected, expected);
          Instant checkedAt = Instant.now();
          if (!Objects.equals(preparedHead, locked.gitSource().headCommit())
              || !Objects.equals(preparedError, locked.gitSource().checkError())) {
            if (!skillPackageRepository.updateGitObservation(
                packageName, preparedHead, checkedAt, preparedError)) {
              throw DomainException.notFound(RESOURCE);
            }
          }
          return getPackage(packageName);
        });
  }

  /** 发布最近一次检查到的 exact commit，并原子切换当前版本与归档。 */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public SkillPackageDetailDTO publishGitPackage(
      String packageName, SkillPackagePublishDTO publishDTO) {
    if (publishDTO == null) {
      throw DomainException.validation(RESOURCE, "request body must not be null");
    }
    String rawExpected = publishDTO.getExpectedVersion();
    long expected = requireExpectedVersion(rawExpected);
    String targetCommit = requireCommit(publishDTO.getTargetCommit(), "targetCommit");
    SkillPackage current = requireGitPackage(packageName);
    requireCurrentVersion(current, rawExpected, expected);
    if (!targetCommit.equals(current.gitSource().headCommit())) {
      throw DomainException.validation(
          RESOURCE,
          "targetCommit must equal the checked branch head of this package: "
              + current.gitSource().headCommit());
    }
    SkillPackageGitSource gitSource = current.gitSource();
    SkillArchiveBuilder.Result archive =
        git(
            () ->
                skillGitRepository.buildArchive(
                    gitSource.repositoryUrl(), targetCommit, gitSource.token()));
    UUID stagingUploadId = stageArchive(packageName, archive);
    try {
      return writes.execute(
          () -> {
            SkillPackage locked = requireGitPackageForUpdate(packageName);
            requireCurrentVersion(locked, rawExpected, expected);
            if (!targetCommit.equals(locked.gitSource().headCommit())) {
              throw DomainException.versionConflict(
                  RESOURCE, rawExpected, CatalogVersions.format(locked.version()));
            }
            ensureSkillsRemovable(packageName, removedSkillNames(locked, archive.skills()));
            UUID artifactBlobId = consumeStaging(stagingUploadId);
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
              throw conflictOrMissing(packageName, rawExpected);
            }
            if (!skillPackageRepository.updateGitObservation(
                packageName, targetCommit, Instant.now(), null)) {
              throw DomainException.notFound(RESOURCE);
            }
            releaseArtifact(locked);
            return getPackage(packageName);
          });
    } catch (RuntimeException error) {
      cancelStagingBestEffort(stagingUploadId);
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
    return SkillArchiveIndex.decode(encodedIndex).entries().stream()
        .sorted(Comparator.comparing(SkillArchiveIndex.Entry::path))
        .map(SkillCatalogService::toFileDTO)
        .toList();
  }

  /** 编辑技能包的描述与 Git 渠道设置；上传渠道只能修改描述。 */
  @Transactional
  public SkillPackageDetailDTO editPackage(String packageName, SkillPackageEditDTO editDTO) {
    if (editDTO == null) {
      throw DomainException.validation(RESOURCE, "request body must not be null");
    }
    String rawExpected = editDTO.getExpectedVersion();
    long expected = requireExpectedVersion(rawExpected);
    SkillPackage current = requirePackageForUpdate(packageName);
    requireCurrentVersion(current, rawExpected, expected);
    String description = requireDescription(editDTO.getDescription());
    if (!current.isGit()) {
      if (editDTO.getBranch() != null || editDTO.getToken() != null) {
        throw DomainException.validation(
            RESOURCE, "branch and token are only valid for Git packages");
      }
      if (!Objects.equals(description, current.description())) {
        if (!skillPackageRepository.updateDescription(packageName, description, expected)) {
          throw conflictOrMissing(packageName, rawExpected);
        }
      }
      return getPackage(packageName);
    }
    String branch = requireBranch(editDTO.getBranch());
    boolean tokenChanged = false;
    String storedToken = current.gitSource().token();
    SecretUpdate tokenUpdate = editDTO.getToken();
    if (tokenUpdate != null) {
      switch (tokenUpdate.operation()) {
        case KEEP -> {}
        case CLEAR -> {
          tokenChanged = storedToken != null;
          storedToken = null;
        }
        case SET -> {
          storedToken = requireToken(tokenUpdate.value());
          tokenChanged = !storedToken.equals(current.gitSource().token());
        }
      }
    }
    if (!Objects.equals(description, current.description())) {
      if (!skillPackageRepository.updateDescription(packageName, description, expected)) {
        throw conflictOrMissing(packageName, rawExpected);
      }
    }
    if (!Objects.equals(branch, current.gitSource().branch()) || tokenChanged) {
      if (!skillPackageRepository.updateGitSettings(packageName, branch, storedToken)) {
        throw DomainException.notFound(RESOURCE);
      }
    }
    return getPackage(packageName);
  }

  /** 用已完成的原始上传构建预览；原始上传被消费，规范化归档作为暂存上传保留。 */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public SkillPackageStagingDTO previewUpload(SkillPackageStagingCreateDTO previewDTO) {
    if (previewDTO == null) {
      throw DomainException.validation(RESOURCE, "request body must not be null");
    }
    String packageName = requirePackageName(previewDTO.getPackageName());
    String packageVersion = requireVersionText(previewDTO.getPackageVersion());
    String description = requireDescription(previewDTO.getDescription());
    UUID uploadId = requireUploadId(previewDTO.getUploadId(), "uploadId");
    StorageUploadService.ReadyUpload ready = uploadService.peekReady(uploadId);
    StorageBlobContent content;
    try {
      content =
          blobContentService.readBlobContent(ready.blobId(), SkillArchiveBuilder.MAX_ARCHIVE_BYTES);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation(
          RESOURCE, "uploaded archive exceeds the size limit: " + error.getMessage(), error);
    }
    SkillArchiveBuilder.Result archive = build(ready.filename(), content.getBytes());
    UUID stagingUploadId = stageArchive(packageName, archive);
    try {
      uploadService.delete(uploadId);
    } catch (RuntimeException error) {
      cancelStagingBestEffort(stagingUploadId);
      throw error;
    }
    StorageBlob staged = requireArtifact(stagingUploadId);
    SkillPackageStagingDTO dto = new SkillPackageStagingDTO();
    dto.setId(stagingUploadId.toString());
    dto.setPackageName(packageName);
    dto.setPackageVersion(packageVersion);
    dto.setDescription(description);
    dto.setSkills(archive.skills().stream().map(SkillCatalogService::toDto).toList());
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
    if (publishDTO == null) {
      throw DomainException.validation(RESOURCE, "request body must not be null");
    }
    String packageName = requirePackageName(publishDTO.getPackageName());
    String packageVersion = requireVersionText(publishDTO.getPackageVersion());
    String description = requireDescription(publishDTO.getDescription());
    String rawExpected = publishDTO.getExpectedVersion();
    SkillPackage existing = skillPackageRepository.getPackage(packageName);
    if (existing != null && existing.isGit()) {
      throw DomainException.validation(
          RESOURCE, "skill package is a Git package and cannot be replaced by an upload");
    }
    if (existing == null && rawExpected != null) {
      throw DomainException.versionConflict(RESOURCE, rawExpected, "0");
    }
    if (existing != null && rawExpected == null) {
      throw DomainException.validation(
          RESOURCE, "expectedVersion is required for an existing package");
    }
    long expected = rawExpected == null ? 0L : requireExpectedVersion(rawExpected);
    if (existing != null) {
      requireCurrentVersion(existing, rawExpected, expected);
    }
    StagedArchive staged = readStagedArchive(stagingUploadId);
    List<SkillManifestEntry> skills = staged.archive().skills();
    try {
      return writes.execute(
          () -> {
            SkillPackage locked = existing == null ? null : requirePackageForUpdate(packageName);
            if (locked != null) {
              requireCurrentVersion(locked, rawExpected, expected);
            }
            if (locked != null) {
              ensureSkillsRemovable(packageName, removedSkillNames(locked, skills));
            }
            UUID artifactBlobId = consumeStaging(stagingUploadId);
            if (locked == null) {
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
                    RESOURCE, "skill package already exists: " + packageName, error);
              }
            } else {
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
                throw conflictOrMissing(packageName, rawExpected);
              }
              releaseArtifact(locked);
            }
            return getPackage(packageName);
          });
    } catch (RuntimeException error) {
      cancelStagingBestEffort(stagingUploadId);
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
  public void deletePackage(String packageName, String expectedVersion) {
    long expected = requireExpectedVersion(expectedVersion);
    SkillPackage current = requirePackageForUpdate(packageName);
    requireCurrentVersion(current, expectedVersion, expected);
    ensureSkillsRemovable(packageName, manifestNames(current.skills()));
    if (!skillPackageRepository.deletePackage(packageName, expected)) {
      throw conflictOrMissing(packageName, expectedVersion);
    }
    releaseArtifact(current);
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
    String name = requirePackageName(packageName);
    String canonicalDescription = requireDescription(description);
    String url = requireRepositoryUrl(repositoryUrl);
    String canonicalBranch = requireBranch(branch);
    String canonicalToken = canonicalImportToken(token);
    String commit =
        git(() -> skillGitRepository.resolveBranchHead(url, canonicalBranch, canonicalToken));
    SkillArchiveBuilder.Result archive =
        git(() -> skillGitRepository.buildArchive(url, commit, canonicalToken));
    UUID stagingUploadId = stageArchive(name, archive);
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
    SkillPackage current = requirePackageForUpdateOrNull(name);
    if (current != null && !current.isGit()) {
      throw DomainException.validation(
          RESOURCE, "uploaded skill package cannot be replaced by a Git import: " + name);
    }
    if (current != null && !prepared.repositoryUrl().equals(current.gitSource().repositoryUrl())) {
      throw DomainException.validation(
          RESOURCE, "skill package repositoryUrl is immutable: " + name);
    }
    if (current != null) {
      ensureSkillsRemovable(name, removedSkillNames(current, prepared.skills()));
    }
    UUID artifactBlobId = consumeStaging(prepared.stagingUploadId());
    if (current == null) {
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
    } else {
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
      if (!skillPackageRepository.updatePublication(published, current.version())) {
        throw conflictOrMissing(name, CatalogVersions.format(current.version()));
      }
      if (!skillPackageRepository.updateGitSettings(name, prepared.branch(), prepared.token())
          || !skillPackageRepository.updateGitObservation(
              name, prepared.commit(), Instant.now(), null)) {
        throw DomainException.notFound(RESOURCE);
      }
      releaseArtifact(current);
    }
  }

  /** 导入失败后在回滚事务之外尽力取消暂存上传；剩余上传由过期清理兜底。 */
  @Transactional(propagation = Propagation.NEVER)
  public void cancelPreparedGitImport(PreparedGitImport prepared) {
    cancelStagingBestEffort(prepared.stagingUploadId());
  }

  /** 只校验导入字段，不访问 Git 或写入数据库。 */
  public void validateImport(
      String packageName, String description, String repositoryUrl, String branch, String token) {
    requirePackageName(packageName);
    requireDescription(description);
    requireRepositoryUrl(repositoryUrl);
    requireBranch(branch);
    if (token != null && !token.isBlank()) {
      requireToken(token);
    }
  }

  /** 读取并重建暂存的规范化归档；摘要必须与暂存 blob 一致。 */
  private StagedArchive readStagedArchive(UUID stagingUploadId) {
    StorageUploadService.ReadyUpload ready = uploadService.peekReady(stagingUploadId);
    StorageBlob blob = requireActiveBlob(ready.blobId());
    if (blob.getArchiveIndex() == null) {
      throw DomainException.validation(RESOURCE, "staged archive has no server index");
    }
    StorageBlobContent content =
        blobContentService.readBlobContent(blob.getId(), SkillArchiveBuilder.MAX_ARCHIVE_BYTES);
    SkillArchiveBuilder.Result archive = build(ready.filename(), content.getBytes());
    return new StagedArchive(blob, archive);
  }

  private record StagedArchive(StorageBlob blob, SkillArchiveBuilder.Result archive) {}

  /** 在事务内 retain 暂存归档并消费暂存上传（释放上传占用的引用）。 */
  private UUID consumeStaging(UUID stagingUploadId) {
    StorageUploadService.ReadyUpload ready = uploadService.lockReady(stagingUploadId);
    blobManager.retain(ready.blobId());
    uploadService.delete(stagingUploadId);
    return ready.blobId();
  }

  /** 规范化归档先作为暂存上传对象保留，并在 blob 上记录服务端读出的条目索引。 */
  private UUID stageArchive(String packageName, SkillArchiveBuilder.Result archive) {
    SkillArchiveIndex index = SkillArchiveIndex.from(archive.index());
    StorageUploadService.StagedUpload staged =
        uploadService.stage(
            packageName + ".zip",
            "application/zip",
            new ByteArrayInputStream(archive.zip()),
            SkillArchiveBuilder.MAX_ARCHIVE_BYTES);
    try {
      if (!blobRepository.updateArchiveIndex(staged.blobId(), index.encode())) {
        throw new IllegalStateException(
            "cannot record the archive index for a staged skill archive");
      }
    } catch (RuntimeException | Error error) {
      cancelStagingBestEffort(staged.uploadId());
      throw error;
    }
    return staged.uploadId();
  }

  private static SkillArchiveBuilder.Result build(String filename, byte[] bytes) {
    try {
      boolean gzip = bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
      SkillArchiveBuilder builder = new SkillArchiveBuilder();
      return gzip
          ? builder.fromTarGz(new ByteArrayInputStream(bytes))
          : builder.fromZip(new ByteArrayInputStream(bytes));
    } catch (IOException error) {
      throw DomainException.validation(
          RESOURCE,
          "invalid skill archive "
              + (filename == null ? "" : filename + " ")
              + ": "
              + error.getMessage(),
          error);
    }
  }

  /** 释放旧归档引用；blob 行本身由后台清理回收。 */
  private void releaseArtifact(SkillPackage skillPackage) {
    if (skillPackage.artifactBlobId() != null) {
      blobManager.release(skillPackage.artifactBlobId());
    }
  }

  private void cancelStagingBestEffort(UUID stagingUploadId) {
    try {
      uploadService.delete(stagingUploadId);
    } catch (RuntimeException error) {
      log.warn("failed to cancel a staged skill archive upload {}", stagingUploadId);
    }
  }

  private SkillPackage requirePackage(String packageName) {
    SkillPackage current = skillPackageRepository.getPackage(packageName);
    if (current == null) {
      throw DomainException.notFound(RESOURCE);
    }
    return current;
  }

  private SkillPackage requireGitPackage(String packageName) {
    SkillPackage current = requirePackage(packageName);
    if (!current.isGit()) {
      throw DomainException.validation(
          RESOURCE, "skill package is not a Git package: " + packageName);
    }
    return current;
  }

  private SkillPackage requirePackageForUpdate(String packageName) {
    SkillPackage current = skillPackageRepository.lockPackage(packageName);
    if (current == null) {
      throw DomainException.notFound(RESOURCE);
    }
    return current;
  }

  private SkillPackage requirePackageForUpdateOrNull(String packageName) {
    return skillPackageRepository.lockPackage(packageName);
  }

  private SkillPackage requireGitPackageForUpdate(String packageName) {
    SkillPackage current = requirePackageForUpdate(packageName);
    if (!current.isGit()) {
      throw DomainException.validation(
          RESOURCE, "skill package is not a Git package: " + packageName);
    }
    return current;
  }

  private StorageBlob requireArtifact(SkillPackage skillPackage) {
    StorageBlob blob = blobManager.getBlob(skillPackage.artifactBlobId());
    if (blob == null) {
      throw DomainException.notFound(RESOURCE);
    }
    return blob;
  }

  private StorageBlob requireArtifact(UUID uploadId) {
    StorageUploadService.ReadyUpload ready = uploadService.peekReady(uploadId);
    return requireActiveBlob(ready.blobId());
  }

  private StorageBlob requireActiveBlob(UUID blobId) {
    StorageBlob blob = blobManager.getBlob(blobId);
    if (blob == null) {
      throw DomainException.notFound("storage_blob");
    }
    return blob;
  }

  /** 拒绝让仍被任何 Agent {@code SkillRef} 引用的 Skill 从 Package 中消失。 */
  private void ensureSkillsRemovable(String packageName, Collection<String> removedSkillNames) {
    List<String> referenced =
        removedSkillNames.stream()
            .filter(name -> agentDefinitionRepository.existsReferencingSkill(packageName, name))
            .sorted()
            .toList();
    if (!referenced.isEmpty()) {
      throw DomainException.inUse(
          RESOURCE,
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

  private RuntimeException conflictOrMissing(String packageName, String expectedVersion) {
    SkillPackage reread = skillPackageRepository.getPackage(packageName);
    if (reread == null) {
      return DomainException.notFound(RESOURCE);
    }
    return DomainException.versionConflict(
        RESOURCE, expectedVersion, CatalogVersions.format(reread.version()));
  }

  private static void requireCurrentVersion(
      SkillPackage skillPackage, String rawExpected, long expected) {
    if (skillPackage.version() != expected) {
      throw DomainException.versionConflict(
          RESOURCE, rawExpected, CatalogVersions.format(skillPackage.version()));
    }
  }

  private static <T> T git(Supplier<T> action) {
    try {
      return action.get();
    } catch (SkillGitException error) {
      if (error.authenticationFailed()) {
        // 权限失败给出明确指导，且不回显 URL、令牌或远端自由文本；绝不静默尝试公共访问。
        throw DomainException.validation(
            RESOURCE,
            "repository authentication failed; verify the repository URL and, for a private repository, configure a valid access token",
            error);
      }
      throw DomainException.validation(
          RESOURCE, "cannot build the skill archive: " + error.getMessage(), error);
    }
  }

  /** 秘密更新到存储令牌：省略、KEEP 或 CLEAR 表示无令牌，SET 使用其值。 */
  private static String tokenOf(SecretUpdate update) {
    if (update == null || update.operation() != SecretUpdate.Operation.SET) {
      return null;
    }
    return requireToken(update.value());
  }

  private static String requireToken(String raw) {
    String token = raw == null ? null : raw.strip();
    if (token == null || token.isEmpty()) {
      throw DomainException.validation(RESOURCE, "token must be a non-blank string when provided");
    }
    if (token.codePoints().anyMatch(Character::isISOControl)) {
      throw DomainException.validation(RESOURCE, "token must not contain control characters");
    }
    return token;
  }

  private static String canonicalImportToken(String raw) {
    if (raw == null) {
      return null;
    }
    String token = raw.strip();
    return token.isEmpty() ? null : token;
  }

  private static long requireExpectedVersion(String raw) {
    if (raw == null) {
      throw DomainException.validation(RESOURCE, "expectedVersion is required");
    }
    return CatalogVersions.parse(raw, "expectedVersion");
  }

  private static UUID requireUploadId(String raw, String field) {
    try {
      return Uuids.parseCanonical(raw, field);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation(RESOURCE, field + " must be a canonical UUID", error);
    }
  }

  private static String requirePackageName(String raw) {
    try {
      return SkillNames.canonicalPackageName(raw);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation(
          RESOURCE, "invalid package name: " + error.getMessage(), error);
    }
  }

  private String requireDescription(String raw) {
    String description = editableSupport.trimToNull(raw);
    if (description == null) {
      return null;
    }
    try {
      return SkillNames.canonicalDescription(description);
    } catch (IllegalArgumentException error) {
      throw DomainException.validation(
          RESOURCE, "invalid description: " + error.getMessage(), error);
    }
  }

  /** 上传渠道版本号：去环绕空白后 1–128 字符，不得包含控制字符。 */
  private static String requireVersionText(String raw) {
    String version = raw == null ? null : raw.strip();
    if (version == null || version.isEmpty()) {
      throw DomainException.validation(RESOURCE, "packageVersion must not be blank");
    }
    if (version.length() > MAX_VERSION_CHARS
        || version.codePoints().anyMatch(Character::isISOControl)) {
      throw DomainException.validation(
          RESOURCE,
          "packageVersion must be at most "
              + MAX_VERSION_CHARS
              + " characters without control characters");
    }
    return version;
  }

  private static String requireRepositoryUrl(String raw) {
    if (raw == null || raw.isBlank() || !raw.equals(raw.strip())) {
      throw DomainException.validation(RESOURCE, "repositoryUrl must be a non-blank, unpadded URL");
    }
    if (raw.length() > MAX_REPOSITORY_URL_CHARS
        || !(REPOSITORY_URL.matcher(raw).matches() || FILE_URL.matcher(raw).matches())
        || USERINFO.matcher(raw).find()
        || raw.codePoints().anyMatch(Character::isISOControl)) {
      throw DomainException.validation(
          RESOURCE,
          "repositoryUrl must be a supported http/https/file URL without userinfo and be at most "
              + MAX_REPOSITORY_URL_CHARS
              + " characters");
    }
    String scheme = schemeOf(raw);
    if (!SUPPORTED_REPOSITORY_SCHEMES.contains(scheme)) {
      throw DomainException.validation(RESOURCE, "unsupported repositoryUrl scheme: " + scheme);
    }
    return raw;
  }

  private static String schemeOf(String raw) {
    int index = raw.indexOf(':');
    return index <= 0 ? "" : raw.substring(0, index).toLowerCase(Locale.ROOT);
  }

  private static String requireBranch(String raw) {
    if (raw == null || raw.isBlank() || !raw.equals(raw.strip())) {
      throw DomainException.validation(RESOURCE, "branch must be a non-blank, unpadded name");
    }
    if (raw.length() > MAX_BRANCH_CHARS || raw.codePoints().anyMatch(Character::isISOControl)) {
      throw DomainException.validation(
          RESOURCE,
          "branch must not contain control characters and be at most "
              + MAX_BRANCH_CHARS
              + " characters");
    }
    return raw;
  }

  private static String requireCommit(String raw, String field) {
    if (raw == null || !COMMIT.matcher(raw).matches()) {
      throw DomainException.validation(
          RESOURCE, field + " must be a 40 or 64 characters long lowercase hex object id");
    }
    return raw;
  }

  /** 检查错误必须有界、非空且无环绕空白；只保留首个错误行以内的可读摘要。 */
  private static String boundCheckError(SkillGitException error) {
    String message =
        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    StringBuilder bounded = new StringBuilder();
    for (int index = 0; index < message.length(); index++) {
      char current = message.charAt(index);
      if (Character.isISOControl(current)) {
        break;
      }
      bounded.append(current);
    }
    String summary = bounded.toString().strip();
    if (summary.isEmpty()) {
      summary = error.getClass().getSimpleName();
    }
    while (summary.getBytes(StandardCharsets.UTF_8).length > MAX_CHECK_ERROR_BYTES) {
      summary = summary.substring(0, summary.length() - 1);
    }
    return summary.strip();
  }

  private static String archiveFilename(SkillPackage skillPackage) {
    String version = skillPackage.packageVersion().replaceAll("[^A-Za-z0-9._-]", "_");
    return skillPackage.packageName() + "-" + version + ".zip";
  }

  private static SkillPackageFileDTO toFileDTO(SkillArchiveIndex.Entry entry) {
    SkillPackageFileDTO dto = new SkillPackageFileDTO();
    dto.setPath(entry.path());
    dto.setSize(entry.size());
    return dto;
  }

  private static SkillPackageSummaryDTO toSummary(SkillPackage skillPackage) {
    SkillPackageSummaryDTO dto = new SkillPackageSummaryDTO();
    dto.setPackageName(skillPackage.packageName());
    dto.setDescription(skillPackage.description());
    dto.setPackageVersion(skillPackage.packageVersion());
    dto.setPublishedAt(skillPackage.publishedAt());
    dto.setSkills(
        skillPackage.skills() == null
            ? List.of()
            : skillPackage.skills().stream().map(SkillCatalogService::toDto).toList());
    dto.setSource(sourceOf(skillPackage));
    dto.setUpdateAvailable(updateAvailable(skillPackage));
    dto.setVersion(CatalogVersions.format(skillPackage.version()));
    dto.setCreatedAt(skillPackage.createdAt());
    dto.setUpdatedAt(skillPackage.updatedAt());
    return dto;
  }

  private static boolean updateAvailable(SkillPackage skillPackage) {
    return skillPackage.isGit()
        && skillPackage.gitSource().headCommit() != null
        && !skillPackage.gitSource().headCommit().equals(skillPackage.packageVersion());
  }

  private SkillPackageDetailDTO toDetail(SkillPackage skillPackage) {
    StorageBlob artifact = requireArtifact(skillPackage);
    SkillPackageDetailDTO dto = new SkillPackageDetailDTO();
    dto.setPackageName(skillPackage.packageName());
    dto.setDescription(skillPackage.description());
    dto.setPackageVersion(skillPackage.packageVersion());
    dto.setPublishedAt(skillPackage.publishedAt());
    dto.setSkills(
        skillPackage.skills() == null
            ? List.of()
            : skillPackage.skills().stream().map(SkillCatalogService::toDto).toList());
    dto.setSource(sourceOf(skillPackage));
    if (skillPackage.isGit()) {
      SkillGitSourceDTO git = new SkillGitSourceDTO();
      git.setRepositoryUrl(skillPackage.gitSource().repositoryUrl());
      git.setBranch(skillPackage.gitSource().branch());
      git.setToken(SecretConfiguredDTO.of(skillPackage.gitSource().token() != null));
      dto.setGit(git);
      dto.setHeadCommit(skillPackage.gitSource().headCommit());
      dto.setHeadCheckedAt(skillPackage.gitSource().checkedAt());
      dto.setHeadCheckError(skillPackage.gitSource().checkError());
    }
    dto.setUpdateAvailable(updateAvailable(skillPackage));
    dto.setArtifactSha256(artifact.getSha256());
    dto.setArtifactSizeBytes(artifact.getSizeBytes());
    dto.setVersion(CatalogVersions.format(skillPackage.version()));
    dto.setCreatedAt(skillPackage.createdAt());
    dto.setUpdatedAt(skillPackage.updatedAt());
    return dto;
  }

  private static SkillPackageSource sourceOf(SkillPackage skillPackage) {
    return skillPackage.isGit() ? SkillPackageSource.GIT : SkillPackageSource.UPLOAD;
  }

  private static SkillEnvironmentSyncDTO toEnvironmentSync(
      EnvironmentConnection connection, SkillPackage skillPackage, StorageBlob artifact) {
    SkillEnvironmentSyncDTO dto = new SkillEnvironmentSyncDTO();
    dto.setEnvironmentId(connection.environmentId().value().toString());
    EnvironmentSkillState state = connection.skillStateOf(skillPackage.packageName()).orElse(null);
    if (state != null) {
      dto.setInstalledSha256(state.installedSha256());
      dto.setLocalPath(state.localPath());
      dto.setError(state.error());
    }
    dto.setUpToDate(
        state != null
            && state.installedSha256() != null
            && state.installedSha256().equals(artifact.getSha256()));
    return dto;
  }

  private static SkillManifestEntryDTO toDto(SkillManifestEntry entry) {
    SkillManifestEntryDTO dto = new SkillManifestEntryDTO();
    dto.setName(entry.name());
    dto.setDescription(entry.description());
    return dto;
  }
}
