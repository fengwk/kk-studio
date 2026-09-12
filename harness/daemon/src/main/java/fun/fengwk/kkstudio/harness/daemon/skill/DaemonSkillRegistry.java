package fun.fengwk.kkstudio.harness.daemon.skill;

import fun.fengwk.kkstudio.harness.daemon.skill.DaemonSkillManifest.RetainedSkill;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDiagnostic;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceConfig;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceSnapshot;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Daemon 的持久 Skill 目录：按来源扫描/安装/更新，校验后原子发布，并按精确 revision 提供正文。
 *
 * <p><b>发布模型：</b>每次操作先构建完整候选快照（扫描、解析、来源内与全局同名检查、Git checkout 发布），全部成功后才在保护区内 替换当前快照并原子写
 * manifest。任何失败都让当前 registry 保持不变，因此 READY 与 {@code skill.load} 永远看不到半成品。Git/网络与目录扫描都在保护区
 * 之外执行，因此不同来源的并发操作不会互相阻塞。
 *
 * <p><b>陈旧拒绝：</b>保护区内对<strong>已经完成</strong>的候选做单调性检查：{@code sourceSetVersion} 小于当前集合版本，或同一来源的
 * {@code sourceVersion} 小于已有快照时，候选被拒绝。这保证延迟到达的旧操作无法用旧 {@code activeSourceIds} 裁剪掉更新操作引入的来源。
 *
 * <p><b>不可变历史：</b>正文按内容 revision 存放在不可变 blob 中，manifest 额外保存 {@code (sourceId, name, revision)}
 * 索引，使旧冻结调用仍能加载它当初选择的确切版本；版本已不保留时返回 {@code RESOURCE_CHANGED} 语义的空结果，绝不用新正文冒充旧版本。
 *
 * <p><b>裁剪：</b>只有当前来源快照按 {@code activeSourceIds} 裁剪；不可变正文与受管 checkout 可以保留，删除来源也不会触碰用户的 PATH 目录。
 * 保留记录的数量上限只约束<strong>非当前</strong>历史：当前全部快照中的每个 descriptor 都必须有对应的保留记录，因此当前 skill 永远 可加载，与总数无关。
 */
public final class DaemonSkillRegistry {

  /** 非当前历史保留记录的上限：超限时淘汰最早的非当前记录。 */
  static final int MAX_RETAINED_SKILLS = 2048;

  private final DaemonSkillStore store;
  private final DaemonGitManager gitManager;
  private final Path userHome;
  private final Object publicationLock = new Object();
  private final Map<UUID, Long> lastPublishedTickets = new HashMap<>();

  /** 进程内来源操作的严格递增启动序号；只在 {@link #publicationLock} 内访问。 */
  private long nextPublicationTicket;

  private volatile Map<UUID, DaemonSkillSourceSnapshot> sources;
  private volatile Map<String, RetainedSkill> retained;
  private volatile DaemonSkillManifest manifest;

  private DaemonSkillRegistry(
      DaemonSkillStore store,
      Path userHome,
      DaemonSkillManifest manifest,
      List<String> gitCommand) {
    this.store = store;
    this.gitManager =
        gitCommand == null ? new DaemonGitManager(store) : new DaemonGitManager(store, gitCommand);
    this.userHome = userHome;
    this.manifest = manifest;
    this.sources = indexSources(manifest.sources());
    this.retained = indexRetained(manifest.retained());
    requireRetainedBodiesAreReadable(manifest, store);
  }

  /** 打开持久 registry：创建数据目录布局并恢复上次成功发布的来源快照与历史索引。 */
  public static DaemonSkillRegistry open(Path dataDir, Path userHome) {
    return open(dataDir, userHome, null);
  }

  /**
   * 打开持久 registry，并允许测试基座注入确定性的 git 命令前缀。
   *
   * <p>生产调用恒为 {@code gitCommand == null}（直接执行 argv {@code git}）；注入只影响“如何启动 git”，不改变任何取消与发布语义。
   */
  static DaemonSkillRegistry open(Path dataDir, Path userHome, List<String> gitCommand) {
    Objects.requireNonNull(dataDir, "dataDir");
    Objects.requireNonNull(userHome, "userHome");
    if (!dataDir.isAbsolute()) {
      throw new IllegalArgumentException("dataDir must be an absolute path");
    }
    DaemonSkillStore store;
    try {
      store = DaemonSkillStore.open(dataDir);
    } catch (IOException error) {
      throw new DaemonSkillException("cannot create skill data directory", error);
    }
    DaemonSkillManifest manifest;
    try {
      manifest = store.readManifest();
    } catch (IOException | IllegalStateException error) {
      throw new DaemonSkillException("cannot restore skill manifest", error);
    }
    return new DaemonSkillRegistry(store, userHome, manifest, gitCommand);
  }

  /** 当前已发布的来源快照，按 sourceId 稳定排序。 */
  public List<DaemonSkillSourceSnapshot> snapshots() {
    List<DaemonSkillSourceSnapshot> result = new ArrayList<>(sources.values());
    result.sort((left, right) -> left.sourceId().compareTo(right.sourceId()));
    return List.copyOf(result);
  }

  /** 展平当前快照中的全部 skill 描述，供 READY 与目录展示使用。 */
  public List<DaemonSkillDescriptor> descriptors() {
    List<DaemonSkillDescriptor> result = new ArrayList<>();
    for (DaemonSkillSourceSnapshot snapshot : snapshots()) {
      result.addAll(snapshot.skills());
    }
    return List.copyOf(result);
  }

  /**
   * 刷新来源：只做本地扫描，绝不联网。
   *
   * <p>PATH 来源重新扫描目录；GIT 来源重新扫描已应用的不可变 checkout（缺少已应用 revision 时失败，而不是访问远端）。
   */
  public DaemonSkillSourceSnapshot refresh(DaemonSkillSourceConfig config) {
    Objects.requireNonNull(config, "config");
    long publicationTicket = beginPublication(config);
    return switch (config.type()) {
      case PATH -> publishPathSource(config, publicationTicket);
      case GIT -> publishGitSource(config, refreshGitCheckout(config), publicationTicket);
    };
  }

  /**
   * 安装来源：解析一次固定 commit 并发布不可变 checkout。
   *
   * <p>只有 GIT 来源有安装语义：PATH 来源的目录是用户权威状态，没有“安装”动作，因此显式拒绝而不是静默退化为扫描。
   */
  public DaemonSkillSourceSnapshot install(DaemonSkillSourceConfig config) {
    Objects.requireNonNull(config, "config");
    if (config.type() != DaemonSkillSourceType.GIT) {
      throw new DaemonSkillException("install is only valid for git sources");
    }
    long publicationTicket = beginPublication(config);
    return publishGitSource(config, gitManager.install(config), publicationTicket);
  }

  /**
   * 更新来源：GIT 来源要么解析当前远端默认 HEAD，要么复用固定已应用 revision。
   *
   * <p>与 {@link #install} 同理，PATH 来源没有“更新”动作：局部刷新由 {@link #refresh} 承担。
   */
  public DaemonSkillSourceSnapshot update(DaemonSkillSourceConfig config) {
    Objects.requireNonNull(config, "config");
    if (config.type() != DaemonSkillSourceType.GIT) {
      throw new DaemonSkillException("update is only valid for git sources");
    }
    long publicationTicket = beginPublication(config);
    return publishGitSource(config, gitManager.update(config), publicationTicket);
  }

  /**
   * 按精确 {@code (sourceId, name, revision)} 加载保留的正文。
   *
   * @return 命中保留版本时返回正文与 baseDirectory；未保留该版本时返回 empty（调用方必须返回 RESOURCE_CHANGED）
   */
  public Optional<LoadedSkill> load(UUID sourceId, String name, String revision) {
    Objects.requireNonNull(sourceId, "sourceId");
    if (name == null || name.isBlank() || revision == null || revision.isBlank()) {
      return Optional.empty();
    }
    RetainedSkill record = retained.get(retentionKey(sourceId, name, revision));
    if (record == null) {
      return Optional.empty();
    }
    Optional<String> body = store.readBody(revision);
    if (body.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new LoadedSkill(record.baseDirectory(), body.get()));
  }

  private String refreshGitCheckout(DaemonSkillSourceConfig config) {
    String applied = config.currentlyAppliedRevision();
    if (applied == null) {
      throw new DaemonSkillException(
          "git skill source has no applied revision; install is required before refresh");
    }
    if (!Files.isDirectory(store.checkout(config.sourceId(), applied))) {
      throw new DaemonSkillException("git skill source checkout is not available locally");
    }
    return applied;
  }

  private DaemonSkillSourceSnapshot publishPathSource(
      DaemonSkillSourceConfig config, long publicationTicket) {
    Path root = DaemonSkillSourcePaths.resolve(config.path(), userHome);
    if (!Files.isDirectory(root)) {
      if (config.defaultSource()) {
        // 缺省默认目录不存在等价于“当前没有可发现的 skill”，是成功状态而不是错误。
        return publish(
            config, aggregateRevision(List.of()), List.of(), List.of(), publicationTicket);
      }
      throw new DaemonSkillException("skill source path is not an existing directory");
    }
    SkillPathScanner.ScanResult scan = SkillPathScanner.scan(root);
    return publish(
        config,
        aggregateRevision(scan.skills()),
        scan.skills(),
        scan.diagnostics(),
        publicationTicket);
  }

  private DaemonSkillSourceSnapshot publishGitSource(
      DaemonSkillSourceConfig config, String revision, long publicationTicket) {
    Path checkoutRoot = store.checkout(config.sourceId(), revision);
    if (!Files.isDirectory(checkoutRoot)) {
      throw new DaemonSkillException("git skill source checkout is not available locally");
    }
    Path scanRoot = checkoutRoot;
    if (config.scanPath() != null) {
      scanRoot = checkoutRoot.resolve(config.scanPath()).normalize();
      if (!scanRoot.startsWith(checkoutRoot)) {
        throw new DaemonSkillException("git scanPath escapes the repository checkout");
      }
    }
    if (!Files.isDirectory(scanRoot)) {
      throw new DaemonSkillException("git scanPath is not an existing directory");
    }
    SkillPathScanner.ScanResult scan = SkillPathScanner.scan(scanRoot);
    return publish(config, revision, scan.skills(), scan.diagnostics(), publicationTicket);
  }

  /**
   * 校验并发布候选快照。
   *
   * <p>失败路径（同名冲突、陈旧版本、manifest 写入失败）抛出且不改变当前 registry；成功路径先写不可变正文，再原子替换 manifest，最后替换内存 快照，因此任何时刻的
   * READY 都是完整目录。
   *
   * <p>取消检查点在 manifest 提交之前，因此被观察到的取消通常不会发布任何内容；manifest 提交之后才到达的取消由 READY 对账，不承诺跨进程原子性。
   */
  private DaemonSkillSourceSnapshot publish(
      DaemonSkillSourceConfig config,
      String sourceRevision,
      List<DaemonSkill> scannedSkills,
      List<DaemonSkillDiagnostic> diagnostics,
      long publicationTicket) {
    Set<String> namesWithinSource = new HashSet<>();
    for (DaemonSkill skill : scannedSkills) {
      if (!namesWithinSource.add(skill.name())) {
        throw new DaemonSkillException("duplicate skill name within one source: " + skill.name());
      }
    }
    List<DaemonSkillDescriptor> descriptors = new ArrayList<>(scannedSkills.size());
    for (DaemonSkill skill : scannedSkills) {
      descriptors.add(skill.descriptor(config.sourceId(), config.sourceVersion()));
    }
    DaemonSkillSourceSnapshot candidate =
        new DaemonSkillSourceSnapshot(
            config.sourceId(), config.sourceVersion(), sourceRevision, descriptors, diagnostics);

    synchronized (publicationLock) {
      requireNotStale(config);
      Long lastPublishedTicket = lastPublishedTickets.get(config.sourceId());
      DaemonSkillSourceSnapshot current = sources.get(config.sourceId());
      if (lastPublishedTicket != null
          && publicationTicket < lastPublishedTicket
          && current != null
          && config.sourceVersion() == current.sourceVersion()
          && config.sourceSetVersion() == manifest.sourceSetVersion()) {
        if (candidate.equals(current)) {
          // 同一候选已由较新的重叠操作发布；重复成功是安全、幂等的。
          return candidate;
        }
        // ticket 只裁决完全相同配置代际的重叠操作。更高行版本或集合版本即使更早启动也必须优先。
        throw new DaemonSkillException("skill source operation was superseded");
      }
      List<DaemonSkillSourceSnapshot> next = new ArrayList<>();
      for (DaemonSkillSourceSnapshot existing : sources.values()) {
        if (existing.sourceId().equals(config.sourceId())) {
          continue;
        }
        if (!config.activeSourceIds().contains(existing.sourceId())) {
          continue;
        }
        next.add(existing);
      }
      next.add(candidate);
      next.sort((left, right) -> left.sourceId().compareTo(right.sourceId()));
      requireGloballyUniqueNames(next);
      requireReadyInventoryFits(next);

      for (DaemonSkill skill : scannedSkills) {
        writeBody(skill);
      }
      // 保留顺序即“最近发布在前”：本次发布的记录在前，其余保持既有相对顺序，因此淘汰尾部就是淘汰最旧的历史。
      Map<String, RetainedSkill> nextRetained = new LinkedHashMap<>();
      for (DaemonSkill skill : scannedSkills) {
        nextRetained.put(
            retentionKey(config.sourceId(), skill.name(), skill.contentRevision()),
            new RetainedSkill(
                config.sourceId(),
                skill.name(),
                skill.contentRevision(),
                skill.baseDirectory().toString()));
      }
      for (Map.Entry<String, RetainedSkill> entry : retained.entrySet()) {
        // 同一身份以本次发布的记录为准（baseDirectory 必须与当前 descriptor 一致）。
        nextRetained.putIfAbsent(entry.getKey(), entry.getValue());
      }
      List<RetainedSkill> boundedRetained = selectRetained(next, nextRetained);
      // manifest 构造即校验全部交叉不变量（唯一性、当前→保留、来源∈生效集合），因此非法候选在写正文之前就失败。
      DaemonSkillManifest nextManifest =
          new DaemonSkillManifest(
              DaemonSkillManifest.VERSION,
              config.sourceSetVersion(),
              List.copyOf(config.activeSourceIds()),
              next,
              boundedRetained);
      // 提交点前最后一次取消检查：此前被观察到的取消绝不发布。
      DaemonSkillException.requireNotInterrupted();
      try {
        store.writeManifest(nextManifest);
      } catch (IOException error) {
        throw new DaemonSkillException("cannot publish skill manifest", error);
      }
      Map<UUID, DaemonSkillSourceSnapshot> nextSources = indexSources(next);
      Map<String, RetainedSkill> nextRetainedIndex = indexRetained(boundedRetained);
      this.manifest = nextManifest;
      this.sources = nextSources;
      this.retained = nextRetainedIndex;
      lastPublishedTickets.put(config.sourceId(), publicationTicket);
      return candidate;
    }
  }

  /**
   * 为一次来源操作分配进程内启动序号，并在昂贵扫描/Git 获取之前拒绝已经可判定的陈旧配置。
   *
   * <p>序号只解决同一来源、同一配置版本的重叠操作：较新的操作若先发布，较早操作即被取代，不能把候选覆盖回来。不同来源仍由 {@code sourceSetVersion}
   * 并发合并，不引入环境级串行化。
   */
  private long beginPublication(DaemonSkillSourceConfig config) {
    synchronized (publicationLock) {
      requireNotStale(config);
      if (nextPublicationTicket == Long.MAX_VALUE) {
        throw new DaemonSkillException("skill source publication sequence is exhausted");
      }
      return ++nextPublicationTicket;
    }
  }

  /** 当前 inventory 必须能进入下一次 READY payload；超限候选在写正文与 manifest 前拒绝。 */
  private static void requireReadyInventoryFits(List<DaemonSkillSourceSnapshot> snapshots) {
    int skillCount = 0;
    for (DaemonSkillSourceSnapshot snapshot : snapshots) {
      skillCount += snapshot.skills().size();
      if (skillCount > DaemonCapabilities.MAX_SKILLS) {
        throw new DaemonSkillException(
            "skill inventory exceeds the READY protocol limit of " + DaemonCapabilities.MAX_SKILLS);
      }
    }
  }

  /**
   * 拒绝被更新事实超越或与当前集合版本自相矛盾的候选。
   *
   * <p>两条规则共同保证延迟到达的旧操作无法裁剪掉新操作引入的来源：
   *
   * <ul>
   *   <li>{@code sourceSetVersion} 不得后退：更小的集合版本意味着候选携带的是过去的有效集合。
   *   <li>同一集合版本必须描述同一个集合：集合内容变化必须前进版本，因此同版本但内容不同（含更窄的集合）的候选一律拒绝。
   * </ul>
   *
   * <p>另外，同一来源的 {@code sourceVersion} 也不得后退，避免用旧扫描结果覆盖新扫描结果。尚未发布任何来源时不存在集合事实，首次发布建立集合。
   */
  private void requireNotStale(DaemonSkillSourceConfig config) {
    long currentSetVersion = manifest.sourceSetVersion();
    if (config.sourceSetVersion() < currentSetVersion) {
      throw new DaemonSkillException("skill source set version is stale");
    }
    if (!manifest.activeSourceIds().isEmpty()
        && config.sourceSetVersion() == currentSetVersion
        && !manifest.activeSourceIds().equals(canonicalSourceIds(config.activeSourceIds()))) {
      throw new DaemonSkillException("skill source set version does not match its source set");
    }
    for (DaemonSkillSourceSnapshot existing : sources.values()) {
      if (existing.sourceId().equals(config.sourceId())
          && config.sourceVersion() < existing.sourceVersion()) {
        throw new DaemonSkillException("skill source version is stale");
      }
    }
  }

  /** 来源集合的 canonical 形式（升序、去重）；与 manifest 中持久化的顺序一致，便于按集合相等比较。 */
  private static List<UUID> canonicalSourceIds(Set<UUID> activeSourceIds) {
    return activeSourceIds.stream().sorted().toList();
  }

  /** 跨来源同名 skill 必须被拒绝：READY 与 load 都按名称唯一定位，重复名称使目标不可判定。 */
  private static void requireGloballyUniqueNames(List<DaemonSkillSourceSnapshot> snapshots) {
    Map<String, UUID> owners = new HashMap<>();
    for (DaemonSkillSourceSnapshot snapshot : snapshots) {
      Set<String> withinSource = new HashSet<>();
      for (DaemonSkillDescriptor skill : snapshot.skills()) {
        if (!withinSource.add(skill.name())) {
          throw new DaemonSkillException("duplicate skill name within one source: " + skill.name());
        }
        UUID previous = owners.putIfAbsent(skill.name(), snapshot.sourceId());
        if (previous != null && !previous.equals(snapshot.sourceId())) {
          throw new DaemonSkillException("duplicate skill name across sources: " + skill.name());
        }
      }
    }
  }

  /**
   * 打开时验证每个保留记录都有可读正文。
   *
   * <p>manifest 与 blob 是两个文件系统事实；若保留记录指向缺失/不可读的正文，READY 会广告一个必然无法加载的当前 skill，因此这种状态必须在
   * 恢复阶段显式失败，而不是进入半可用的 READY。
   */
  private static void requireRetainedBodiesAreReadable(
      DaemonSkillManifest manifest, DaemonSkillStore store) {
    for (RetainedSkill record : manifest.retained()) {
      if (!store.isBodyReadable(record.revision())) {
        throw new DaemonSkillException("skill body blob is missing or unreadable");
      }
    }
  }

  private void writeBody(DaemonSkill skill) {
    try {
      store.writeBody(skill.contentRevision(), skill.body());
    } catch (IOException error) {
      throw new DaemonSkillException("cannot persist skill body", error);
    }
  }

  /**
   * 选出参与发布的保留记录。
   *
   * <p>当前快照中的每个 descriptor 都必须保留（否则 READY 广告的 skill 会立即无法 {@code skill.load}）；{@link
   * #MAX_RETAINED_SKILLS} 只约束此外的非当前历史记录数，因此当前 skill 数超过上限时保留集合仍会超过上限——这是为了让“当前即可加载”永远成立。
   *
   * <p>输出顺序确定：先按输入顺序输出命中当前快照的记录，再按输入顺序输出有界的非当前记录，使 manifest 文本可复现且不依赖哈希顺序。输入顺序即
   * “最近发布在前”，因此淘汰尾部就是淘汰最旧的历史。
   */
  static List<RetainedSkill> selectRetained(
      List<DaemonSkillSourceSnapshot> nextSnapshots, Map<String, RetainedSkill> candidates) {
    Set<String> current = new HashSet<>();
    for (DaemonSkillSourceSnapshot snapshot : nextSnapshots) {
      for (DaemonSkillDescriptor skill : snapshot.skills()) {
        current.add(retentionKey(skill.sourceId(), skill.name(), skill.contentRevision()));
      }
    }
    List<RetainedSkill> currentRecords = new ArrayList<>(current.size());
    List<RetainedSkill> historicalRecords = new ArrayList<>();
    for (Map.Entry<String, RetainedSkill> entry : candidates.entrySet()) {
      if (current.contains(entry.getKey())) {
        currentRecords.add(entry.getValue());
      } else {
        historicalRecords.add(entry.getValue());
      }
    }
    if (historicalRecords.size() > MAX_RETAINED_SKILLS) {
      historicalRecords = new ArrayList<>(historicalRecords.subList(0, MAX_RETAINED_SKILLS));
    }
    List<RetainedSkill> result = new ArrayList<>(currentRecords.size() + historicalRecords.size());
    result.addAll(currentRecords);
    result.addAll(historicalRecords);
    return List.copyOf(result);
  }

  /** PATH 来源的聚合 revision：按 (name, contentRevision) 稳定排序后的 SHA-256，与 skill 集合一一对应。 */
  private static String aggregateRevision(List<DaemonSkill> skills) {
    List<String> lines = new ArrayList<>(skills.size());
    for (DaemonSkill skill : skills) {
      lines.add(skill.name() + "\u0000" + skill.contentRevision());
    }
    lines.sort(String::compareTo);
    StringBuilder canonical = new StringBuilder();
    for (String line : lines) {
      canonical.append(line).append('\n');
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  private static Map<UUID, DaemonSkillSourceSnapshot> indexSources(
      List<DaemonSkillSourceSnapshot> snapshots) {
    Map<UUID, DaemonSkillSourceSnapshot> result = new LinkedHashMap<>();
    for (DaemonSkillSourceSnapshot snapshot : snapshots) {
      result.put(snapshot.sourceId(), snapshot);
    }
    return Map.copyOf(result);
  }

  /**
   * 保留记录的查找索引。
   *
   * <p>顺序是 manifest 语义的一部分（“最近发布在前”决定淘汰顺序），因此必须用保序 map 而不是 {@code Map.copyOf}（后者不保证迭代顺序）。
   */
  private static Map<String, RetainedSkill> indexRetained(List<RetainedSkill> retained) {
    Map<String, RetainedSkill> result = new LinkedHashMap<>();
    for (RetainedSkill skill : retained) {
      result.put(retentionKey(skill.sourceId(), skill.name(), skill.revision()), skill);
    }
    return Collections.unmodifiableMap(result);
  }

  private static String retentionKey(UUID sourceId, String name, String revision) {
    return DaemonSkillManifest.retentionKey(sourceId, name, revision);
  }

  /** 命中保留版本的加载结果。 */
  public record LoadedSkill(String baseDirectory, String body) {}
}
