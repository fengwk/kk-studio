package fun.fengwk.kkstudio.platform.catalog.skill;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.harness.common.skill.SkillNames;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitCache;
import fun.fengwk.kkstudio.platform.catalog.skill.git.SkillGitException;
import fun.fengwk.kkstudio.platform.catalog.support.AgentEditableSupport;
import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.error.CatalogVersions;
import fun.fengwk.kkstudio.share.ai.skill.SkillManifestEntryDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageCheckDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageCreateDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackageEditDTO;
import fun.fengwk.kkstudio.share.ai.skill.SkillPackagePublishDTO;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Platform 全局 Git Skill Package 的应用服务：单表 CRUD、Git 发布编排与 Agent 引用保护。
 *
 * <p>package 以不可变 {@code packageName} 键控一个不可变 repository URL，发布内容由人工确认的 exact commit 决定：Create
 * 发布当时解析出的 branch HEAD；Check 只观察 branch HEAD，失败时完整保留 current commit 与 manifest；Update 只接受等于当前观察值的
 * exact commit，并在一次 CAS 中原子切换 current commit 与 manifest。Git 网络准备在事务之外完成，随后由 {@link
 * SkillCatalogWrites} 开启短写事务重检提案事实。每条写路径都在锁行后比较 「提案事实」与当前事实，只有真正变化才推进 {@code version}，因此陈旧 Card
 * 统一得到 version conflict。
 */
@Service
public class SkillCatalogService {

  private static final String RESOURCE = "skill_package";

  /** Card 状态：从未检查。 */
  private static final String CHECK_STATUS_UNCHECKED = "UNCHECKED";

  /** Card 状态：观察值等于当前值。 */
  private static final String CHECK_STATUS_UP_TO_DATE = "UP_TO_DATE";

  /** Card 状态：观察值不同于当前值。 */
  private static final String CHECK_STATUS_UPDATE_AVAILABLE = "UPDATE_AVAILABLE";

  /** Card 状态：最近检查失败。 */
  private static final String CHECK_STATUS_CHECK_FAILED = "CHECK_FAILED";

  /** 40 或 64 位小写 hex object id，与 {@code skill_package} 的列约束一致。 */
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
  private static final int MAX_CHECK_ERROR_BYTES = 4096;

  private final SkillPackageRepository skillPackageRepository;
  private final AgentDefinitionRepository agentDefinitionRepository;
  private final SkillGitCache skillGitCache;
  private final AgentEditableSupport editableSupport;
  private final SkillCatalogWrites writes;

  public SkillCatalogService(
      SkillPackageRepository skillPackageRepository,
      AgentDefinitionRepository agentDefinitionRepository,
      SkillGitCache skillGitCache,
      AgentEditableSupport editableSupport,
      SkillCatalogWrites writes) {
    this.skillPackageRepository =
        Objects.requireNonNull(skillPackageRepository, "skillPackageRepository");
    this.agentDefinitionRepository =
        Objects.requireNonNull(agentDefinitionRepository, "agentDefinitionRepository");
    this.skillGitCache = Objects.requireNonNull(skillGitCache, "skillGitCache");
    this.editableSupport = Objects.requireNonNull(editableSupport, "editableSupport");
    this.writes = Objects.requireNonNull(writes, "writes");
  }

  /** 全部 Package，按 {@code packageName} 升序。 */
  public List<SkillPackageDTO> listPackages() {
    return skillPackageRepository.listPackages().stream().map(SkillCatalogService::toDto).toList();
  }

  /** 单个 Package；不存在时 404。 */
  public SkillPackageDTO getPackage(String packageName) {
    SkillPackage skillPackage = skillPackageRepository.getPackage(packageName);
    if (skillPackage == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    return toDto(skillPackage);
  }

  /** 创建 Package 并发布当时解析出的 branch HEAD。 */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public SkillPackageDTO createPackage(SkillPackageCreateDTO createDTO) {
    if (createDTO == null) {
      throw new AiValidationException(RESOURCE, "request body must not be null");
    }
    String packageName = requirePackageName(createDTO.getPackageName());
    String description = requireDescription(createDTO.getDescription());
    String repositoryUrl = requireRepositoryUrl(createDTO.getRepositoryUrl());
    String branch = requireBranch(createDTO.getBranch());
    String token = requireTokenOrNull(createDTO.getToken());
    if (skillPackageRepository.getPackage(packageName) != null) {
      throw new AiDuplicateException(RESOURCE, "skill package already exists: " + packageName);
    }
    // Create 发布当时解析出的 exact branch HEAD：请求本身不接收 commit。
    String commit =
        git(
            () -> skillGitCache.resolveBranchHead(repositoryUrl, branch, token),
            "cannot resolve branch head: " + branch);
    List<SkillManifestEntry> skills =
        git(
            () -> {
              skillGitCache.ensureCommit(packageName, repositoryUrl, commit, token);
              return skillGitCache.scanManifest(packageName, commit);
            },
            "cannot publish commit: " + commit);
    // 创建即已观察并发布了同一个 commit，因此 Card 初始状态是 UP_TO_DATE。
    SkillPackage created =
        SkillPackage.builder()
            .packageName(packageName)
            .description(description)
            .repositoryUrl(repositoryUrl)
            .branch(branch)
            .currentCommit(commit)
            .observedHeadCommit(commit)
            .skills(skills)
            .token(token)
            .build();
    return writes.execute(
        () -> {
          try {
            if (!skillPackageRepository.insertPackage(created)) {
              throw new IllegalStateException("create skill package failed");
            }
          } catch (DuplicateKeyException error) {
            throw new AiDuplicateException(
                RESOURCE, "skill package already exists: " + packageName, error);
          }
          return getPackage(packageName);
        });
  }

  /** 编辑可编辑字段（description / branch），不改变已发布内容。 */
  @Transactional
  public SkillPackageDTO editPackage(String packageName, SkillPackageEditDTO editDTO) {
    if (editDTO == null) {
      throw new AiValidationException(RESOURCE, "request body must not be null");
    }
    String rawExpected = editDTO.getExpectedVersion();
    long expected = requireExpectedVersion(rawExpected);
    SkillPackage current = requirePackageForUpdate(packageName);
    requireCurrentVersion(current, rawExpected, expected);
    String description = requireDescription(editDTO.getDescription());
    String branch = requireBranch(editDTO.getBranch());
    // token 三态：省略保留、显式 null 清除、非空替换；非法空白串拒绝。
    boolean tokenChanged = false;
    String storedToken = current.token();
    if (editDTO.isTokenProvided()) {
      String token = editDTO.getToken();
      if (token == null) {
        tokenChanged = current.token() != null;
        storedToken = null;
      } else {
        storedToken = requireToken(token);
        tokenChanged = !storedToken.equals(current.token());
      }
    }
    // repository URL 是不可变身份：编辑只可能改变 description、branch 与访问令牌，事实未变化就不推进 version。
    if (Objects.equals(description, current.description())
        && Objects.equals(branch, current.branch())
        && !tokenChanged) {
      return toDto(current);
    }
    casUpdate(
        current.toBuilder().description(description).branch(branch).token(storedToken).build(),
        expected);
    return getPackage(packageName);
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
  }

  /** 只观察 branch HEAD，更新观察三元组；失败时保留 current commit 与 manifest。 */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public SkillPackageDTO checkPackage(String packageName, SkillPackageCheckDTO checkDTO) {
    if (checkDTO == null) {
      throw new AiValidationException(RESOURCE, "request body must not be null");
    }
    String rawExpected = checkDTO.getExpectedVersion();
    long expected = requireExpectedVersion(rawExpected);
    SkillPackage current = requirePackage(packageName);
    requireCurrentVersion(current, rawExpected, expected);
    String observedHeadCommit;
    String headCheckError;
    try {
      observedHeadCommit =
          skillGitCache.resolveBranchHead(
              current.repositoryUrl(), current.branch(), current.token());
      headCheckError = null;
    } catch (SkillGitException error) {
      // 检查失败只记录有界错误：current commit、manifest 与上一次成功观察值全部保持不变。
      observedHeadCommit = current.observedHeadCommit();
      headCheckError = boundCheckError(error);
    }
    String preparedHead = observedHeadCommit;
    String preparedError = headCheckError;
    return writes.execute(
        () -> {
          SkillPackage locked = recheck(current, rawExpected, expected);
          // 观察结果未变化时既不写行也不推进 version，避免只读检查造成写放大；
          // 因此在同一观察结果下反复 Check 不会让 Card 失效。
          if (Objects.equals(preparedHead, locked.observedHeadCommit())
              && Objects.equals(preparedError, locked.headCheckError())) {
            return toDto(locked);
          }
          casUpdate(
              locked.toBuilder()
                  .observedHeadCommit(preparedHead)
                  .headCheckError(preparedError)
                  .build(),
              expected);
          return getPackage(packageName);
        });
  }

  /** 发布等于当前观察值的 exact commit，并原子替换 current commit 与 manifest。 */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public SkillPackageDTO updatePackage(String packageName, SkillPackagePublishDTO publishDTO) {
    if (publishDTO == null) {
      throw new AiValidationException(RESOURCE, "request body must not be null");
    }
    String rawExpected = publishDTO.getExpectedVersion();
    long expected = requireExpectedVersion(rawExpected);
    String targetCommit = requireCommit(publishDTO.getTargetCommit(), "targetCommit");
    SkillPackage current = requirePackage(packageName);
    requireCurrentVersion(current, rawExpected, expected);
    // 只接受该 CAS 快照展示过的 exact commit：branch 在检查之后又前进也不会暗中切换发布内容。
    if (!targetCommit.equals(current.observedHeadCommit())) {
      throw new AiValidationException(
          RESOURCE,
          "targetCommit must equal the observed branch head of this package version: "
              + current.observedHeadCommit());
    }
    String token = current.token();
    List<SkillManifestEntry> skills =
        git(
            () -> {
              skillGitCache.ensureCommit(packageName, current.repositoryUrl(), targetCommit, token);
              return skillGitCache.scanManifest(packageName, targetCommit);
            },
            "cannot publish commit: " + targetCommit);
    return writes.execute(
        () -> {
          SkillPackage locked = recheck(current, rawExpected, expected);
          Set<String> published = new LinkedHashSet<>(manifestNames(skills));
          List<String> removed =
              manifestNames(locked.skills()).stream()
                  .filter(name -> !published.contains(name))
                  .toList();
          ensureSkillsRemovable(packageName, removed);
          if (targetCommit.equals(locked.currentCommit())
              && Objects.equals(skills, locked.skills())) {
            return toDto(locked);
          }
          casUpdate(
              locked.toBuilder()
                  .currentCommit(targetCommit)
                  .skills(skills)
                  .observedHeadCommit(targetCommit)
                  .headCheckError(null)
                  .build(),
              expected);
          return getPackage(packageName);
        });
  }

  /** 以 {@code FOR UPDATE} 锁定某 Package；缺失则 404。 */
  private SkillPackage requirePackageForUpdate(String packageName) {
    SkillPackage skillPackage = skillPackageRepository.lockPackage(packageName);
    if (skillPackage == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    return skillPackage;
  }

  /**
   * 拒绝让仍被任何 Agent {@code SkillRef} 引用的 Skill 从 Package 中消失。
   *
   * <p>引用保护按精确的 {@code (packageName, name)} 判定：保留同名 Skill（内容随 commit 变化）不构成移除。
   */
  private void ensureSkillsRemovable(String packageName, Collection<String> removedSkillNames) {
    List<String> referenced =
        removedSkillNames.stream()
            .filter(name -> agentDefinitionRepository.existsReferencingSkill(packageName, name))
            .sorted()
            .toList();
    if (!referenced.isEmpty()) {
      throw new AiInUseException(
          RESOURCE,
          "skills are still referenced by agents: "
              + packageName
              + "/"
              + String.join(", ", referenced));
    }
  }

  private static List<String> manifestNames(List<SkillManifestEntry> skills) {
    if (skills == null) {
      return List.of();
    }
    return skills.stream().map(SkillManifestEntry::name).toList();
  }

  private SkillPackage requirePackage(String packageName) {
    SkillPackage current = skillPackageRepository.getPackage(packageName);
    if (current == null) {
      throw new AiResourceNotFoundException(RESOURCE);
    }
    return current;
  }

  /** 网络准备没有行锁：短事务中重检决定准备内容的全部提案事实。 */
  private SkillPackage recheck(SkillPackage observed, String rawExpected, long expected) {
    SkillPackage current = requirePackageForUpdate(observed.packageName());
    requireCurrentVersion(current, rawExpected, expected);
    if (!Objects.equals(current.branch(), observed.branch())
        || !Objects.equals(current.repositoryUrl(), observed.repositoryUrl())
        || !Objects.equals(current.observedHeadCommit(), observed.observedHeadCommit())) {
      throw new AiVersionConflictException(
          RESOURCE, rawExpected, CatalogVersions.format(current.version()));
    }
    return current;
  }

  /**
   * 配置同步导入：按名称新增或更新 Package，并发布调用方在事务外按 exact commit 准备好的 manifest。
   *
   * <p>repositoryUrl 是不可变身份：同名但 URL 不同拒绝导入。Git 对象补齐与 manifest 扫描必须已在事务外完成，
   * 本方法只写数据库事实并复用既有名称/URL/branch/commit 校验与 Skill 引用保护。token 为 null/空白表示清除既有令牌，非空表示替换。
   */
  @Transactional
  public SkillPackageDTO importPackage(
      String packageName,
      String description,
      String repositoryUrl,
      String branch,
      String currentCommit,
      List<SkillManifestEntry> skills,
      String token) {
    String name = requirePackageName(packageName);
    String canonicalDescription = requireDescription(description);
    String url = requireRepositoryUrl(repositoryUrl);
    String canonicalBranch = requireBranch(branch);
    String commit = requireCommit(currentCommit, "currentCommit");
    if (skills == null) {
      throw new AiValidationException(RESOURCE, "manifest must not be null");
    }
    List<SkillManifestEntry> manifest = List.copyOf(skills);
    // 导入按文件事实整体覆盖：null/空白令牌表示清除既有令牌，非空表示替换。
    String canonicalToken = canonicalImportToken(token);
    SkillPackage current = skillPackageRepository.lockPackage(name);
    if (current == null) {
      SkillPackage created =
          SkillPackage.builder()
              .packageName(name)
              .description(canonicalDescription)
              .repositoryUrl(url)
              .branch(canonicalBranch)
              .currentCommit(commit)
              .observedHeadCommit(commit)
              .skills(manifest)
              .token(canonicalToken)
              .build();
      try {
        if (!skillPackageRepository.insertPackage(created)) {
          throw new IllegalStateException("create skill package failed");
        }
      } catch (DuplicateKeyException error) {
        throw new AiDuplicateException(RESOURCE, "skill package already exists: " + name, error);
      }
      return getPackage(name);
    }
    // repository URL 是不可变身份：同名换仓库拒绝导入。
    if (!url.equals(current.repositoryUrl())) {
      throw new AiValidationException(
          RESOURCE, "skill package repositoryUrl is immutable: " + name);
    }
    Set<String> published = new LinkedHashSet<>(manifestNames(manifest));
    List<String> removed =
        manifestNames(current.skills()).stream()
            .filter(skillName -> !published.contains(skillName))
            .toList();
    ensureSkillsRemovable(name, removed);
    boolean tokenChanged = !Objects.equals(current.token(), canonicalToken);
    if (commit.equals(current.currentCommit())
        && Objects.equals(manifest, current.skills())
        && Objects.equals(canonicalDescription, current.description())
        && Objects.equals(canonicalBranch, current.branch())
        && !tokenChanged) {
      return toDto(current);
    }
    casUpdate(
        current.toBuilder()
            .description(canonicalDescription)
            .branch(canonicalBranch)
            .currentCommit(commit)
            .skills(manifest)
            .observedHeadCommit(commit)
            .headCheckError(null)
            .token(canonicalToken)
            .build(),
        current.version());
    return getPackage(name);
  }

  /** 只校验导入字段，不访问 Git 或写入数据库。 */
  public void validateImport(
      String packageName,
      String description,
      String repositoryUrl,
      String branch,
      String commit,
      String token) {
    requirePackageName(packageName);
    requireDescription(description);
    requireRepositoryUrl(repositoryUrl);
    requireBranch(branch);
    requireCommit(commit, "currentCommit");
    if (token != null && !token.isBlank()) {
      requireToken(token);
    }
  }

  /** 以锁定时读到的 {@code expected} 为条件整体更新事实；影响行数为 0 时重读判定 404 或 version conflict。 */
  private void casUpdate(SkillPackage skillPackage, long expected) {
    if (!skillPackageRepository.updatePackage(skillPackage, expected)) {
      throw conflictOrMissing(skillPackage.packageName(), CatalogVersions.format(expected));
    }
  }

  private RuntimeException conflictOrMissing(String packageName, String expectedVersion) {
    SkillPackage reread = skillPackageRepository.getPackage(packageName);
    if (reread == null) {
      return new AiResourceNotFoundException(RESOURCE);
    }
    return new AiVersionConflictException(
        RESOURCE, expectedVersion, CatalogVersions.format(reread.version()));
  }

  private void requireCurrentVersion(SkillPackage skillPackage, String rawExpected, long expected) {
    if (skillPackage.version() != expected) {
      throw new AiVersionConflictException(
          RESOURCE, rawExpected, CatalogVersions.format(skillPackage.version()));
    }
  }

  private <T> T git(Supplier<T> action, String failure) {
    try {
      return action.get();
    } catch (SkillGitException error) {
      if (error.authenticationFailed()) {
        // 权限失败给出明确指导，且不回显 URL、令牌或远端自由文本；绝不静默尝试公共访问。
        throw new AiValidationException(
            RESOURCE,
            "repository authentication failed; verify the repository URL and, for a private repository, configure a valid access token",
            error);
      }
      throw new AiValidationException(RESOURCE, failure + ": " + error.getMessage(), error);
    }
  }

  /** 请求或导入携带的非空白令牌；null 表示未提供。 */
  private static String requireTokenOrNull(String raw) {
    return raw == null ? null : requireToken(raw);
  }

  /** 令牌必须非空白；返回去除环绕空白后的规范值。诊断绝不回显令牌内容。 */
  private static String requireToken(String raw) {
    String token = raw == null ? null : raw.strip();
    if (token == null || token.isEmpty()) {
      throw new AiValidationException(RESOURCE, "token must be a non-blank string when provided");
    }
    if (token.codePoints().anyMatch(SkillCatalogService::isControl)) {
      throw new AiValidationException(RESOURCE, "token must not contain control characters");
    }
    return token;
  }

  /** 导入令牌按文件事实归一：null/空白表示清除，其余取去空白值。 */
  private static String canonicalImportToken(String raw) {
    if (raw == null) {
      return null;
    }
    String token = raw.strip();
    return token.isEmpty() ? null : token;
  }

  private static long requireExpectedVersion(String raw) {
    if (raw == null) {
      throw new AiValidationException(RESOURCE, "expectedVersion is required");
    }
    return CatalogVersions.parse(raw, "expectedVersion");
  }

  private static String requirePackageName(String raw) {
    try {
      return SkillNames.canonicalPackageName(raw);
    } catch (IllegalArgumentException error) {
      throw new AiValidationException(
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
      throw new AiValidationException(
          RESOURCE, "invalid description: " + error.getMessage(), error);
    }
  }

  private static String requireRepositoryUrl(String raw) {
    if (raw == null || raw.isBlank() || !raw.equals(raw.strip())) {
      throw new AiValidationException(RESOURCE, "repositoryUrl must be a non-blank, unpadded URL");
    }
    if (raw.length() > MAX_REPOSITORY_URL_CHARS
        || !(REPOSITORY_URL.matcher(raw).matches() || FILE_URL.matcher(raw).matches())
        || USERINFO.matcher(raw).find()
        || raw.codePoints().anyMatch(SkillCatalogService::isControl)) {
      // 非法输入可能内嵌凭据，诊断不得回显原始 URL。
      throw new AiValidationException(
          RESOURCE,
          "repositoryUrl must be a supported http/https/file URL without userinfo and be at most "
              + MAX_REPOSITORY_URL_CHARS
              + " characters");
    }
    String scheme = schemeOf(raw);
    if (!SUPPORTED_REPOSITORY_SCHEMES.contains(scheme)) {
      throw new AiValidationException(RESOURCE, "unsupported repositoryUrl scheme: " + scheme);
    }
    return raw;
  }

  private static String schemeOf(String raw) {
    int index = raw.indexOf(':');
    return index <= 0 ? "" : raw.substring(0, index).toLowerCase(Locale.ROOT);
  }

  private static String requireBranch(String raw) {
    if (raw == null || raw.isBlank() || !raw.equals(raw.strip())) {
      throw new AiValidationException(RESOURCE, "branch must be a non-blank, unpadded name");
    }
    if (raw.length() > MAX_BRANCH_CHARS
        || raw.codePoints().anyMatch(SkillCatalogService::isControl)) {
      throw new AiValidationException(
          RESOURCE,
          "branch must not contain control characters and be at most "
              + MAX_BRANCH_CHARS
              + " characters");
    }
    return raw;
  }

  private static String requireCommit(String raw, String field) {
    if (raw == null || !COMMIT.matcher(raw).matches()) {
      throw new AiValidationException(
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

  /** Package 权威事实到对外投影：令牌只暴露 {@code hasToken}，Card 状态由检查错误与观察值派生。 */
  private static SkillPackageDTO toDto(SkillPackage skillPackage) {
    SkillPackageDTO dto = new SkillPackageDTO();
    dto.setPackageName(skillPackage.packageName());
    dto.setDescription(skillPackage.description());
    dto.setRepositoryUrl(skillPackage.repositoryUrl());
    dto.setBranch(skillPackage.branch());
    dto.setHasToken(skillPackage.token() != null);
    dto.setCurrentCommit(skillPackage.currentCommit());
    dto.setObservedHeadCommit(skillPackage.observedHeadCommit());
    dto.setHeadCheckedAt(skillPackage.headCheckedAt());
    dto.setHeadCheckError(skillPackage.headCheckError());
    dto.setCheckStatus(checkStatus(skillPackage));
    dto.setSkills(
        skillPackage.skills() == null
            ? List.of()
            : skillPackage.skills().stream().map(SkillCatalogService::toDto).toList());
    dto.setVersion(CatalogVersions.format(skillPackage.version()));
    dto.setCreateTime(skillPackage.createTime());
    dto.setUpdateTime(skillPackage.updateTime());
    return dto;
  }

  private static SkillManifestEntryDTO toDto(SkillManifestEntry entry) {
    SkillManifestEntryDTO dto = new SkillManifestEntryDTO();
    dto.setName(entry.name());
    dto.setDescription(entry.description());
    return dto;
  }

  /** Card 状态只由这组事实派生：检查错误优先，其次比较观察值与当前值。 */
  private static String checkStatus(SkillPackage skillPackage) {
    if (skillPackage.headCheckError() != null) {
      return CHECK_STATUS_CHECK_FAILED;
    }
    if (skillPackage.observedHeadCommit() == null) {
      return CHECK_STATUS_UNCHECKED;
    }
    return skillPackage.observedHeadCommit().equals(skillPackage.currentCommit())
        ? CHECK_STATUS_UP_TO_DATE
        : CHECK_STATUS_UPDATE_AVAILABLE;
  }
}
