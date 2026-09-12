package fun.fengwk.kkstudio.harness.daemon.skill;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Daemon 本地 Skill 数据目录：原子 manifest、不可变内容 blob 与受管 Git checkout。
 *
 * <p>目录布局固定为 {@code <dataDir>/skills/manifest.json}、{@code
 * <dataDir>/skills/bodies/<contentRevision>}、 {@code
 * <dataDir>/skills/checkouts/<sourceId>/<commit>} 与 {@code
 * <dataDir>/skills/staging/<unique>}。所有写入都先写 同目录临时文件再 move，避免半截文件成为可读状态：manifest
 * 发布失败不会破坏上一份有效目录，正文按内容 revision 命名的文件一旦 出现即代表完整内容。
 */
final class DaemonSkillStore {

  private static final String SKILLS_DIRECTORY = "skills";

  private final Path skillsRoot;
  private final Path manifestFile;
  private final Path bodiesRoot;
  private final Path checkoutsRoot;
  private final Path stagingRoot;

  private DaemonSkillStore(Path dataDir) {
    this.skillsRoot = dataDir.resolve(SKILLS_DIRECTORY);
    this.manifestFile = skillsRoot.resolve("manifest.json");
    this.bodiesRoot = skillsRoot.resolve("bodies");
    this.checkoutsRoot = skillsRoot.resolve("checkouts");
    this.stagingRoot = skillsRoot.resolve("staging");
  }

  /** 打开（必要时创建）数据目录布局。 */
  static DaemonSkillStore open(Path dataDir) throws IOException {
    DaemonSkillStore store = new DaemonSkillStore(dataDir);
    Files.createDirectories(store.bodiesRoot);
    Files.createDirectories(store.checkoutsRoot);
    Files.createDirectories(store.stagingRoot);
    return store;
  }

  Path checkoutsRoot() {
    return checkoutsRoot;
  }

  Path stagingRoot() {
    return stagingRoot;
  }

  /**
   * 受管 checkout 目录：{@code checkouts/<sourceId>/<commit>}。
   *
   * <p>revision 直接成为路径段，因此在任何路径运算之前必须复核为完整小写 commit id；非法形状显式失败，杜绝 {@code ..} 或绝对路径把 checkout
   * 解析到数据目录之外。
   */
  Path checkout(UUID sourceId, String revision) {
    Objects.requireNonNull(sourceId, "sourceId");
    if (!DaemonSkillSourceConfig.isCommitId(revision)) {
      throw new DaemonSkillException("git revision is not a lowercase commit id");
    }
    return checkoutsRoot.resolve(sourceId.toString()).resolve(revision);
  }

  /** 读取 manifest；首次启动或文件缺失时返回空 manifest。 */
  DaemonSkillManifest readManifest() throws IOException {
    if (!Files.isRegularFile(manifestFile)) {
      return DaemonSkillManifest.empty();
    }
    String json = Files.readString(manifestFile, StandardCharsets.UTF_8);
    return DaemonSkillManifestCodec.decode(json);
  }

  /** 原子替换 manifest：先在同目录写入临时文件，再以原子 move 发布；文件系统不支持原子 move 时 fail-closed。 */
  void writeManifest(DaemonSkillManifest manifest) throws IOException {
    String json = DaemonSkillManifestCodec.encode(manifest);
    Path temporary = Files.createTempFile(skillsRoot, "manifest-", ".tmp");
    boolean published = false;
    try {
      Files.writeString(
          temporary, json, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
      moveAtomically(temporary, manifestFile);
      published = true;
    } finally {
      if (!published) {
        Files.deleteIfExists(temporary);
      }
    }
  }

  /** 写入不可变正文 blob；同一 revision 的内容必然相同，已存在时保持原文件不变。 */
  void writeBody(String revision, String body) throws IOException {
    Path target = bodyFile(revision);
    if (Files.isRegularFile(target)) {
      return;
    }
    Path temporary = Files.createTempFile(bodiesRoot, "body-", ".tmp");
    boolean published = false;
    try {
      Files.writeString(
          temporary, body, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
      moveAtomically(temporary, target);
      published = true;
    } finally {
      if (!published) {
        Files.deleteIfExists(temporary);
      }
    }
  }

  /** 读取不可变正文 blob；未保留的 revision 返回 empty。 */
  Optional<String> readBody(String revision) {
    Path target = bodyFile(revision);
    if (!Files.isRegularFile(target)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Files.readString(target, StandardCharsets.UTF_8));
    } catch (IOException error) {
      return Optional.empty();
    }
  }

  /** 判断不可变正文 blob 是否可读：内容本身不返回，避免恢复校验把正文带进内存或错误文本。 */
  boolean isBodyReadable(String revision) {
    Path target = bodyFile(revision);
    if (!Files.isRegularFile(target)) {
      return false;
    }
    try (InputStream input = Files.newInputStream(target)) {
      return input.read() >= 0 || Files.size(target) == 0;
    } catch (IOException error) {
      return false;
    }
  }

  /** 申请一个独占 staging 目录；调用方负责在 finally 中删除。 */
  Path newStagingDirectory(String prefix) throws IOException {
    return Files.createTempDirectory(stagingRoot, prefix + "-");
  }

  private Path bodyFile(String revision) {
    return bodiesRoot.resolve(revision + ".md");
  }

  private static void moveAtomically(Path source, Path target) throws IOException {
    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }
}
