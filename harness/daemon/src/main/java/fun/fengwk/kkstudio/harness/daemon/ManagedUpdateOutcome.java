package fun.fengwk.kkstudio.harness.daemon;

import java.nio.file.Path;

/**
 * {@link ManagedDaemonUpdater#prepare} 的结果：要么已写出交给 OS 更新器的 handoff，要么失败且旧二进制未改动。
 *
 * <p>{@link Prepared} 不是最终成功：替换与重启由独立 OS 更新器阶段完成，最终成功由更新后 Daemon 以目标版本重新 READY 确认。 {@link
 * Failed#message()} 只承载去敏、有界的失败说明，可直接作为 {@code UPDATE_RESULT} 的 message。
 */
public sealed interface ManagedUpdateOutcome {

  /**
   * 暂存制品已通过 SHA256、manifest 版本与磁盘配置预检，handoff 已落到私有目录，可以交给独立更新器。
   *
   * <p>这里携带更新器真正需要的路径（暂存 JAR、受管 JAR 与更新脚本），让分离进程无需再次解析 handoff 文件即可替换二进制。
   */
  record Prepared(
      String targetVersion,
      Path handoffDirectory,
      Path updateScript,
      Path stagedJar,
      Path installedJar)
      implements ManagedUpdateOutcome {}

  /** 准备失败；旧二进制未改动。 */
  record Failed(String message) implements ManagedUpdateOutcome {}
}
