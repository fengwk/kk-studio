package fun.fengwk.kkstudio.platform.environment.skill;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceType;
import fun.fengwk.kkstudio.platform.environment.skill.model.SkillSource;
import fun.fengwk.kkstudio.platform.environment.skill.repo.SkillSourceRepository;

import java.util.UUID;

/**
 * 新 Environment 的 Skill inventory 初始化协作者。
 *
 * <p>在调用方（{@code EnvironmentServiceImpl.create}）的事务内插入一行 {@code environment_inventory}（代际 0）与一个缺省
 * PATH 来源 {@code ~/.agents/skills}（{@code defaultSource = true}、{@code version 0}、{@code
 * UNAPPLIED}）。两次插入 都必须成功，否则抛出异常让整个 Environment 创建回滚——绝不留下没有 inventory 身份的 Environment。
 *
 * <p>本类不含任何 Inventory 业务规则，只负责"新 Environment 的标准形状"，因此不会与来源 CRUD 服务形成循环依赖。
 */
@AllArgsConstructor
@Component
public class EnvironmentSkillSourceInitializer {

  /** 缺省来源目录：{@code ~/} 由目标 Daemon 按自己的 HOME 展开，Backend 不做解析。 */
  public static final String DEFAULT_SOURCE_PATH = "~/.agents/skills";

  private final SkillSourceRepository repository;

  /**
   * 为新 Environment 原子创建 inventory 头与缺省 PATH 来源。
   *
   * @param environmentId 已插入的 Environment UUID
   */
  public void initialize(UUID environmentId, UUID defaultSourceId) {
    if (!repository.createInventory(environmentId)) {
      throw new IllegalStateException("create environment inventory failed");
    }
    SkillSource defaultSource = new SkillSource();
    defaultSource.setSourceId(defaultSourceId);
    defaultSource.setEnvironmentId(environmentId);
    defaultSource.setType(DaemonSkillSourceType.PATH);
    defaultSource.setPath(DEFAULT_SOURCE_PATH);
    defaultSource.setDefaultSource(true);
    if (!repository.createSource(defaultSource)) {
      throw new IllegalStateException("create default skill source failed");
    }
  }
}
