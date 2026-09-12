package fun.fengwk.kkstudio.platform.environment.skill;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillSourceCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillSourceDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentSkillSourceUpdateDTO;

import java.util.List;
import java.util.UUID;

/**
 * Environment Skill 来源配置的稳定 CRUD 服务。
 *
 * <p>配置持久化与操作执行严格分离：本服务从不调用 Daemon、不认领操作，也不改变应用的 applied 事实。所有写路径以来源行版本 CAS，并只在来源集合成员关系变化（创建/删除）时推进
 * {@code source_set_version}；来源配置编辑不改变集合成员关系。
 */
public interface EnvironmentSkillSourceService {

  /** 按 {@code sourceId} 升序列出该 Environment 的来源配置；Environment 不存在时 404。 */
  List<EnvironmentSkillSourceDTO> list(EnvironmentId environmentId);

  /** 读取单个来源配置；Environment 或来源不存在时 404。 */
  EnvironmentSkillSourceDTO get(EnvironmentId environmentId, UUID sourceId);

  /**
   * 创建来源：{@code version = 0}、{@code UNAPPLIED}、{@code defaultSource = false}，并原子推进来源集合代际。
   *
   * <p>缺省来源由服务端在创建 Environment 时生成，客户端不可指定。
   */
  EnvironmentSkillSourceDTO create(
      EnvironmentId environmentId, EnvironmentSkillSourceCreateDTO request);

  /**
   * CAS 整体替换来源配置：推进行版本、置 {@code UNAPPLIED} 并清空诊断与 last error。
   *
   * <p>既有 applied 三元组与 skill 行作为“最近一次成功应用”的陈旧事实保留，供展示使用但不构成新规划候选。
   */
  EnvironmentSkillSourceDTO update(
      EnvironmentId environmentId, UUID sourceId, EnvironmentSkillSourceUpdateDTO request);

  /**
   * CAS 删除来源：级联删除该来源的持久 inventory 行，并原子推进来源集合代际。
   *
   * <p>缺省来源同样可删；删除后空集合就是明确的“禁用发现”，绝不自动补回缺省来源。
   */
  void delete(EnvironmentId environmentId, UUID sourceId, String expectedVersion);
}
