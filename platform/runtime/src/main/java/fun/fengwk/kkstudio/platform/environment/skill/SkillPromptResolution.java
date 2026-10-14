package fun.fengwk.kkstudio.platform.environment.skill;

import java.util.Objects;

/**
 * 单个 Skill 的 Prompt 路径解析结果：模型可见的稳定路径、当前 Environment 实际安装的 commit 与交付方式。
 *
 * <p>{@code delivery} 只由「精确安装」判定派生，绝不从路径前缀推断：{@link Delivery#LOCAL} 只在选定 Environment 的 {@code
 * skill_state} 明确记录该 Package 的 {@code currentCommit} 已安装时成立，其余一切情况都是 {@link Delivery#PLATFORM}。
 *
 * <p>{@code installedCommit} 是连接行投影里实际安装的 commit（同步失败时可能是更早一次成功安装的事实，也可能为 null）。它与 {@code delivery}
 * 相互独立：失败的同步可以暴露真实的已安装 commit，但交付方式仍必须是 Platform URI，绝不把可重建的本地缓存升格为交付事实。
 *
 * @param path 精确安装成立时是 Daemon 本地稳定路径，否则是 platform URI
 * @param installedCommit 当前 Environment 已安装的 commit；未安装或未选择 Environment 时为 null
 * @param delivery 交付方式；{@link Delivery#LOCAL} 只在精确安装该 commit 时成立
 */
public record SkillPromptResolution(String path, String installedCommit, Delivery delivery) {

  public SkillPromptResolution {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(delivery, "delivery");
  }

  /** Skill 交付方式：Daemon 本地精确安装或 Platform 托管读取。 */
  public enum Delivery {
    /** Daemon 已按该 Package 的 currentCommit 精确安装，模型可直接读本地稳定路径。 */
    LOCAL,

    /** 其余一切情况：模型经 platform URI 读取 Platform 缓存。 */
    PLATFORM
  }
}
