package fun.fengwk.kkstudio.harness.daemon;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateCommand;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * 当前 handoff 文件的唯一读写边界：受管更新用它在进程私有 operation 目录里持久化「交接阶段」，独立 OS 更新器按它决定是否替换发行目录。
 *
 * <p>状态机刻意最小：{@code PREPARING} 在 OS 作业启动前落盘，只有 OS 确定接受后才置 {@code ACCEPTED}，启动失败或不确定置 {@code
 * FAILED}。只有 {@code ACCEPTED} 可复用；{@code PREPARING}/{@code FAILED} 一律 fail closed，绝不据此再次下载或再次启动。
 *
 * <p>写入在同私有父目录内创建独占随机临时文件、收紧为 owner-only（非 POSIX 继承私有父目录 ACL）后以 {@code ATOMIC_MOVE} 替换，绝不跟随预置链接或
 * 覆盖其目标；读取与改写一律拒绝符号链接与非普通父目录。文件内容只承载绝对路径等本地事实，不含凭据，错误说明只描述安全阶段。
 */
final class UpdateHandoff {

  /** handoff 元数据文件名；独立 OS 更新器按它核对 operationId 与状态。 */
  static final String HANDOFF_FILE = "handoff.json";

  /** 更新许可文件名；独立 OS 更新器必须等它出现（且内容为本 operationId）才可替换发行目录。 */
  static final String PERMIT_FILE = "permit";

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final String STATE_FIELD = "state";

  /** handoff 复用判定结果：只有 {@link #ACCEPTED} 表示 OS 作业已被接受。 */
  enum State {
    /** 尚无 handoff：可以准备一次新的更新。 */
    ABSENT,
    /** 已提交但尚未确认接受：拒绝复用，绝不重新下载或再次启动。 */
    PREPARING,
    /** OS 作业已确定接受：可以复用暂存制品并只重放回执。 */
    ACCEPTED,
    /** 已记录的失败：拒绝复用。 */
    FAILED,
    /** 已有元数据与本命令不匹配或结构非法：拒绝复用，也绝不静默重新下载。 */
    UNUSABLE
  }

  private UpdateHandoff() {}

  /**
   * 读取当前 handoff 相对本命令的复用状态。
   *
   * <p>不存在为 {@link State#ABSENT}；存在但与本 operationId/版本/URL/SHA256 不一致或结构非法为 {@link
   * State#UNUSABLE}；读取本身失败（如权限）抛出 {@link IOException}，让调用方 fail closed 而不是重新下载。
   */
  static State readState(Path updateRoot, DaemonUpdateCommand command) throws IOException {
    Path handoff = updateRoot.resolve(HANDOFF_FILE);
    if (!Files.exists(handoff, LinkOption.NOFOLLOW_LINKS)) {
      return State.ABSENT;
    }
    if (!isRealRegularFile(handoff)) {
      return State.UNUSABLE;
    }
    JsonNode root;
    try {
      root = MAPPER.readTree(Files.readString(handoff, StandardCharsets.UTF_8));
    } catch (RuntimeException error) {
      return State.UNUSABLE;
    }
    if (!(root instanceof ObjectNode)) {
      return State.UNUSABLE;
    }
    if (!command.operationId().equals(text(root, "operationId"))
        || !command.targetVersion().equals(text(root, "targetVersion"))
        || !command.artifactUrl().equals(text(root, "artifactUrl"))
        || !command.artifactSha256().equalsIgnoreCase(text(root, "artifactSha256"))) {
      return State.UNUSABLE;
    }
    String state = text(root, STATE_FIELD);
    if (state == null) {
      return State.UNUSABLE;
    }
    return switch (state) {
      case "PREPARING" -> State.PREPARING;
      case "ACCEPTED" -> State.ACCEPTED;
      default -> State.FAILED;
    };
  }

  /** 在 OS 作业启动前持久化 {@code PREPARING} handoff（原子替换）。 */
  static void writePreparing(
      Path updateRoot,
      DaemonUpdateCommand command,
      Path updateScript,
      Path stagedJar,
      Path installedJar,
      Path configFile,
      String javaExecutable)
      throws IOException {
    ObjectNode handoff = MAPPER.createObjectNode();
    handoff.put(STATE_FIELD, State.PREPARING.name());
    handoff.put("operationId", command.operationId());
    handoff.put("targetVersion", command.targetVersion());
    handoff.put("artifactUrl", command.artifactUrl());
    handoff.put("artifactSha256", command.artifactSha256());
    handoff.put("updateScript", updateScript.toString());
    handoff.put("stagedJar", stagedJar.toString());
    handoff.put("installedJar", installedJar.toString());
    handoff.put("configFile", configFile.toString());
    if (javaExecutable != null) {
      handoff.put("javaExecutable", javaExecutable);
    }
    writeAtomic(updateRoot.resolve(HANDOFF_FILE), MAPPER.writeValueAsString(handoff));
  }

  /** 就地切换已存在 handoff 的状态（原子替换），保留其余元数据；handoff 必须是普通文件。 */
  static void transition(Path updateRoot, State state) throws IOException {
    if (state != State.ACCEPTED && state != State.FAILED) {
      throw new IllegalArgumentException("only ACCEPTED or FAILED may be persisted");
    }
    Path handoff = updateRoot.resolve(HANDOFF_FILE);
    if (!isRealRegularFile(handoff)) {
      throw new IOException("handoff metadata is not a regular file");
    }
    JsonNode parsed = MAPPER.readTree(Files.readString(handoff, StandardCharsets.UTF_8));
    if (!(parsed instanceof ObjectNode root)) {
      throw new IOException("handoff metadata is malformed");
    }
    root.put(STATE_FIELD, state.name());
    writeAtomic(handoff, MAPPER.writeValueAsString(root));
  }

  /** 释放许可：原子写入内容为 operationId 的私有许可文件，独立 OS 更新器据此放行替换。 */
  static void releasePermit(Path updateRoot, String operationId) throws IOException {
    writeAtomic(updateRoot.resolve(PERMIT_FILE), operationId + "\n");
  }

  private static String text(JsonNode root, String field) {
    JsonNode value = root.get(field);
    return value != null && value.isString() ? value.stringValue() : null;
  }

  /**
   * 同私有父目录内的原子替换。
   *
   * <p>父目录必须是非符号链接的真实目录；已存在的目标必须是本文件自己的 NOFOLLOW 普通文件，否则直接失败——绝不替换或跟随预置的符号链接/目录条目，
   * 也绝不改动其外部目标。用独占随机临时文件承载内容，避免跟随或截断预置链接/固定 {@code .part}；写完后以 {@code ATOMIC_MOVE} 原子替换普通目标。
   */
  private static void writeAtomic(Path target, String content) throws IOException {
    Path parent = target.getParent();
    if (parent == null || !isRealDirectory(parent)) {
      throw new IOException("handoff directory is not a real directory");
    }
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !isRealRegularFile(target)) {
      throw new IOException("handoff target is not a regular file");
    }
    Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
    Throwable primary = null;
    try {
      restrictToOwner(temporary);
      Files.writeString(temporary, content, StandardCharsets.UTF_8);
      // ATOMIC_MOVE 在 POSIX（rename）与 Windows（MoveFileEx 的 replace-existing 语义）上都原子替换既有的普通目标；
      // REPLACE_EXISTING 把该语义显式化并保持原子性，绝不退化为非原子写入。
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException | RuntimeException | Error error) {
      primary = error;
      throw error;
    } finally {
      // 临时文件清理绝不掩盖主异常：有主异常时只作为 suppressed 附加，没有时才向上抛出。
      try {
        Files.deleteIfExists(temporary);
      } catch (IOException cleanupFailure) {
        if (primary != null) {
          primary.addSuppressed(cleanupFailure);
        } else {
          throw cleanupFailure;
        }
      }
    }
  }

  /** 临时文件收紧为 owner-only；非 POSIX 文件系统依赖其私有父目录的 ACL 继承。 */
  private static void restrictToOwner(Path path) throws IOException {
    try {
      Files.setPosixFilePermissions(
          path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    } catch (UnsupportedOperationException ignored) {
      // 非 POSIX 文件系统：新建文件继承其私有父目录的 ACL。
    }
  }

  private static boolean isRealDirectory(Path path) {
    return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path);
  }

  private static boolean isRealRegularFile(Path path) {
    return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path);
  }
}
