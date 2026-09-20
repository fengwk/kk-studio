package fun.fengwk.kkstudio.platform.catalog.skill.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.common.skill.SkillNames;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitCache;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitException;
import fun.fengwk.kkstudio.platform.catalog.skill.repo.SkillPackageRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.service.SkillCatalogService;
import fun.fengwk.kkstudio.platform.catalog.skill.service.converter.SkillCatalogConverter;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillManifestEntry;
import fun.fengwk.kkstudio.platform.catalog.skill.service.model.SkillPackage;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageCheckDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageCreateDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageEditDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackagePublishDTO;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Platform 全局 Git Skill Package 的单表 CRUD。
 *
 * <p>唯一可变的事实都写在 {@code skill_package} 的同一行里：Create 解析并发布 branch HEAD；Check 只观察 branch HEAD，失败时完整保留
 * current commit 与 manifest；Update 只接受等于当前观察值的 exact commit，并在一次 CAS 中原子切换 current commit 与
 * manifest。 每条写路径都在锁行后比较「提案事实」与当前事实，只有真正变化才推进 {@code version}，因此陈旧 Card 统一得到 version conflict。
 */
@AllArgsConstructor
@Service
public class SkillCatalogServiceImpl implements SkillCatalogService {

  /** 40 或 64 位小写 hex object id，与 {@code skill_package} 的列约束一致。 */
  private static final Pattern COMMIT = Pattern.compile("^([0-9a-f]{40}|[0-9a-f]{64})$");

  /** Git repository URL：必须带 scheme、无环绕空白、无控制字符、≤2048、不得内嵌 userinfo。 */
  private static final Pattern REPOSITORY_URL = Pattern.compile("^[a-z][a-z0-9+.-]*://[^\\s]+$");

  private static final Pattern USERINFO = Pattern.compile("://[^/\\s]*@");

  private static final int MAX_REPOSITORY_URL_CHARS = 2048;
  private static final int MAX_BRANCH_CHARS = 255;
  private static final int MAX_CHECK_ERROR_BYTES = 4096;

  private final SkillPackageRepository skillPackageRepository;
  private final SkillGitCache skillGitCache;
  private final SkillCatalogConverter converter;
  private final SkillPackageGuard guard;
  private final AgentEditableSupport editableSupport;

  @Override
  public List<SkillPackageDTO> listPackages() {
    return skillPackageRepository.listPackages().stream().map(converter::convert).toList();
  }

  @Override
  public SkillPackageDTO getPackage(String packageName) {
    SkillPackage skillPackage = skillPackageRepository.getPackage(packageName);
    if (skillPackage == null) {
      throw new AiResourceNotFoundException(SkillPackageGuard.RESOURCE);
    }
    return converter.convert(skillPackage);
  }

  @Override
  @Transactional
  public SkillPackageDTO createPackage(SkillPackageCreateDTO createDTO) {
    if (createDTO == null) {
      throw new AiValidationException(SkillPackageGuard.RESOURCE, "request body must not be null");
    }
    String packageName = requirePackageName(createDTO.getPackageName());
    String description = requireDescription(createDTO.getDescription());
    String repositoryUrl = requireRepositoryUrl(createDTO.getRepositoryUrl());
    String branch = requireBranch(createDTO.getBranch());
    if (skillPackageRepository.getPackage(packageName) != null) {
      throw new AiDuplicateException(
          SkillPackageGuard.RESOURCE, "skill package already exists: " + packageName);
    }
    // Create 发布当时解析出的 exact branch HEAD：请求本身不接收 commit。
    String commit =
        git(
            () -> skillGitCache.resolveBranchHead(repositoryUrl, branch),
            "cannot resolve branch head: " + branch);
    List<SkillManifestEntry> skills =
        git(
            () -> {
              skillGitCache.ensureCommit(packageName, repositoryUrl, commit);
              return skillGitCache.scanManifest(packageName, commit);
            },
            "cannot publish commit: " + commit);
    SkillPackage created = new SkillPackage();
    created.setPackageName(packageName);
    created.setDescription(description);
    created.setRepositoryUrl(repositoryUrl);
    created.setBranch(branch);
    created.setCurrentCommit(commit);
    // 创建即已观察并发布了同一个 commit，因此 Card 初始状态是 UP_TO_DATE。
    created.setObservedHeadCommit(commit);
    created.setHeadCheckError(null);
    created.setSkills(skills);
    created.setVersion(0L);
    try {
      if (!skillPackageRepository.insertPackage(created)) {
        throw new IllegalStateException("create skill package failed");
      }
    } catch (DuplicateKeyException error) {
      throw new AiDuplicateException(
          SkillPackageGuard.RESOURCE, "skill package already exists: " + packageName, error);
    }
    return getPackage(packageName);
  }

  @Override
  @Transactional
  public SkillPackageDTO editPackage(String packageName, SkillPackageEditDTO editDTO) {
    if (editDTO == null) {
      throw new AiValidationException(SkillPackageGuard.RESOURCE, "request body must not be null");
    }
    String rawExpected = editDTO.getExpectedVersion();
    long expected = requireExpectedVersion(rawExpected);
    SkillPackage current = guard.requirePackageForUpdate(packageName);
    requireCurrentVersion(current, rawExpected, expected);
    String description = requireDescription(editDTO.getDescription());
    String branch = requireBranch(editDTO.getBranch());
    // repository URL 是不可变身份：编辑只可能改变 description 与 branch，事实未变化就不推进 version。
    if (Objects.equals(description, current.getDescription())
        && Objects.equals(branch, current.getBranch())) {
      return converter.convert(current);
    }
    current.setDescription(description);
    current.setBranch(branch);
    casUpdate(current, expected);
    return getPackage(packageName);
  }

  @Override
  @Transactional
  public void deletePackage(String packageName, String expectedVersion) {
    long expected = requireExpectedVersion(expectedVersion);
    SkillPackage current = guard.requirePackageForUpdate(packageName);
    requireCurrentVersion(current, expectedVersion, expected);
    guard.ensureSkillsRemovable(packageName, SkillPackageGuard.manifestNames(current.getSkills()));
    if (!skillPackageRepository.deletePackage(packageName, expected)) {
      throw conflictOrMissing(packageName, expectedVersion);
    }
  }

  @Override
  @Transactional
  public SkillPackageDTO checkPackage(String packageName, SkillPackageCheckDTO checkDTO) {
    if (checkDTO == null) {
      throw new AiValidationException(SkillPackageGuard.RESOURCE, "request body must not be null");
    }
    String rawExpected = checkDTO.getExpectedVersion();
    long expected = requireExpectedVersion(rawExpected);
    SkillPackage current = guard.requirePackageForUpdate(packageName);
    requireCurrentVersion(current, rawExpected, expected);
    String observedHeadCommit;
    String headCheckError;
    try {
      observedHeadCommit =
          skillGitCache.resolveBranchHead(current.getRepositoryUrl(), current.getBranch());
      headCheckError = null;
    } catch (SkillGitException error) {
      // 检查失败只记录有界错误：current commit、manifest 与上一次成功观察值全部保持不变。
      observedHeadCommit = current.getObservedHeadCommit();
      headCheckError = boundCheckError(error);
    }
    // 观察结果未变化时既不写行也不推进 version，避免只读检查造成写放大；
    // 因此在同一观察结果下反复 Check 不会让 Card 失效。
    if (Objects.equals(observedHeadCommit, current.getObservedHeadCommit())
        && Objects.equals(headCheckError, current.getHeadCheckError())) {
      return converter.convert(current);
    }
    current.setObservedHeadCommit(observedHeadCommit);
    current.setHeadCheckError(headCheckError);
    casUpdate(current, expected);
    return getPackage(packageName);
  }

  @Override
  @Transactional
  public SkillPackageDTO updatePackage(String packageName, SkillPackagePublishDTO publishDTO) {
    if (publishDTO == null) {
      throw new AiValidationException(SkillPackageGuard.RESOURCE, "request body must not be null");
    }
    String rawExpected = publishDTO.getExpectedVersion();
    long expected = requireExpectedVersion(rawExpected);
    String targetCommit = requireCommit(publishDTO.getTargetCommit(), "targetCommit");
    SkillPackage current = guard.requirePackageForUpdate(packageName);
    requireCurrentVersion(current, rawExpected, expected);
    // 只接受该 CAS 快照展示过的 exact commit：branch 在检查之后又前进也不会暗中切换发布内容。
    if (!targetCommit.equals(current.getObservedHeadCommit())) {
      throw new AiValidationException(
          SkillPackageGuard.RESOURCE,
          "targetCommit must equal the observed branch head of this package version: "
              + current.getObservedHeadCommit());
    }
    List<SkillManifestEntry> skills =
        git(
            () -> {
              skillGitCache.ensureCommit(packageName, current.getRepositoryUrl(), targetCommit);
              return skillGitCache.scanManifest(packageName, targetCommit);
            },
            "cannot publish commit: " + targetCommit);
    Set<String> published = new LinkedHashSet<>(SkillPackageGuard.manifestNames(skills));
    List<String> removed =
        SkillPackageGuard.manifestNames(current.getSkills()).stream()
            .filter(name -> !published.contains(name))
            .toList();
    guard.ensureSkillsRemovable(packageName, removed);
    if (targetCommit.equals(current.getCurrentCommit())
        && Objects.equals(skills, current.getSkills())) {
      return converter.convert(current);
    }
    current.setCurrentCommit(targetCommit);
    current.setSkills(skills);
    current.setObservedHeadCommit(targetCommit);
    current.setHeadCheckError(null);
    casUpdate(current, expected);
    return getPackage(packageName);
  }

  /** 以锁定时读到的 {@code expected} 为条件整体更新事实；影响行数为 0 时重读判定 404 或 version conflict。 */
  private void casUpdate(SkillPackage skillPackage, long expected) {
    if (!skillPackageRepository.updatePackage(skillPackage, expected)) {
      throw conflictOrMissing(skillPackage.getPackageName(), CatalogVersions.format(expected));
    }
  }

  private RuntimeException conflictOrMissing(String packageName, String expectedVersion) {
    SkillPackage reread = skillPackageRepository.getPackage(packageName);
    if (reread == null) {
      return new AiResourceNotFoundException(SkillPackageGuard.RESOURCE);
    }
    return new AiVersionConflictException(
        SkillPackageGuard.RESOURCE, expectedVersion, CatalogVersions.format(reread.getVersion()));
  }

  private void requireCurrentVersion(SkillPackage skillPackage, String rawExpected, long expected) {
    if (skillPackage.getVersion() != expected) {
      throw new AiVersionConflictException(
          SkillPackageGuard.RESOURCE,
          rawExpected,
          CatalogVersions.format(skillPackage.getVersion()));
    }
  }

  private <T> T git(Supplier<T> action, String failure) {
    try {
      return action.get();
    } catch (SkillGitException error) {
      throw new AiValidationException(
          SkillPackageGuard.RESOURCE, failure + ": " + error.getMessage(), error);
    }
  }

  private static long requireExpectedVersion(String raw) {
    if (raw == null) {
      throw new AiValidationException(SkillPackageGuard.RESOURCE, "expectedVersion is required");
    }
    return CatalogVersions.parse(raw, "expectedVersion");
  }

  private static String requirePackageName(String raw) {
    try {
      return SkillNames.canonicalPackageName(raw);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(
          SkillPackageGuard.RESOURCE, "invalid package name: " + error.getMessage(), error);
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
      throw new AiValidationException(
          SkillPackageGuard.RESOURCE, "invalid description: " + error.getMessage(), error);
    }
  }

  private static String requireRepositoryUrl(String raw) {
    if (raw == null || raw.isBlank() || !raw.equals(raw.strip())) {
      throw new AiValidationException(
          SkillPackageGuard.RESOURCE, "repositoryUrl must be a non-blank, unpadded URL");
    }
    if (raw.length() > MAX_REPOSITORY_URL_CHARS
        || !REPOSITORY_URL.matcher(raw).matches()
        || USERINFO.matcher(raw).find()
        || raw.codePoints().anyMatch(SkillCatalogServiceImpl::isControl)) {
      throw new AiValidationException(
          SkillPackageGuard.RESOURCE,
          "repositoryUrl must carry a scheme, contain no userinfo and be at most "
              + MAX_REPOSITORY_URL_CHARS
              + " characters: "
              + raw);
    }
    return raw;
  }

  private static String requireBranch(String raw) {
    if (raw == null || raw.isBlank() || !raw.equals(raw.strip())) {
      throw new AiValidationException(
          SkillPackageGuard.RESOURCE, "branch must be a non-blank, unpadded name");
    }
    if (raw.length() > MAX_BRANCH_CHARS
        || raw.codePoints().anyMatch(SkillCatalogServiceImpl::isControl)) {
      throw new AiValidationException(
          SkillPackageGuard.RESOURCE,
          "branch must not contain control characters and be at most "
              + MAX_BRANCH_CHARS
              + " characters");
    }
    return raw;
  }

  private static String requireCommit(String raw, String field) {
    if (raw == null || !COMMIT.matcher(raw).matches()) {
      throw new AiValidationException(
          SkillPackageGuard.RESOURCE,
          field + " must be a 40 or 64 characters long lowercase hex object id");
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
      if (isControl(current)) {
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

  private static boolean isControl(int codePoint) {
    return Character.isISOControl(codePoint);
  }
}
