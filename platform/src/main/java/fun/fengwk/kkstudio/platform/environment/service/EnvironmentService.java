package fun.fengwk.kkstudio.platform.environment.service;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCardDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentEventDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInstallCodeDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInstallConfigDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInstallConfigUpdateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentRegistrationTokenDTO;

import java.util.List;

/** 稳定 Environment Card 业务服务。 */
public interface EnvironmentService {

  /** 创建 Environment Card，返回包含 registrationToken 的 DTO；列表/详情永不返回 token。 */
  EnvironmentCardDTO create(EnvironmentCreateDTO dto);

  /**
   * 配置同步导入：以新 UUID 与显式 registrationToken 创建 Environment。
   *
   * <p>与 {@link #create(EnvironmentCreateDTO)} 的区别是不生成随机 token，而是保留导入文件中的 token，使 Daemon HELLO 可继续按
   * token 注册。同名 Environment 由调用方按名称走 {@link #updateImportedEnvironment}。
   */
  EnvironmentCardDTO importEnvironment(
      String name, String registrationToken, EnvironmentInstallConfigDTO installConfig);

  /** CAS 保存安装设置，响应不含注册凭据。 */
  EnvironmentCardDTO updateInstallConfig(
      EnvironmentId id, EnvironmentInstallConfigUpdateDTO request);

  /** 查询单个 Environment Card 详情，永不返回 registrationToken。 */
  EnvironmentCardDTO get(EnvironmentId id);

  /** 列表查询所有 Environment Cards，包含 live 状态投影，永不返回 registrationToken。 */
  List<EnvironmentCardDTO> list();

  /**
   * 按时间正序返回该 Environment 最近 200 条以内的连接与 Skill 同步运维事件。
   *
   * <p>事件是连接行保留的可重建投影，只在此端点暴露；Card 不再内嵌历史事件。
   */
  List<EnvironmentEventDTO> listEvents(EnvironmentId id);

  /** 幂等只读当前 registrationToken；不轮换、不更新 version/updateTime。 */
  EnvironmentRegistrationTokenDTO getRegistrationToken(EnvironmentId id);

  /** 签发五分钟安装 code。校验当前版本和已保存安装设置，不写库、不轮换 token。 */
  EnvironmentInstallCodeDTO issueInstallCode(EnvironmentId id, String expectedVersion);

  /** 校验安装 code 后按稳定身份渲染安装命令。每次读取当前配置与 token；code 缺失、到期、篡改或 token 已轮换均失败。 */
  String installationScript(EnvironmentId id, String code);

  /** 按操作系统返回卸载脚本；不需要 token，也不读取或修改 Environment。 */
  String uninstallationScript(String operatingSystem);

  /**
   * 配置同步导入：原子更新 token 与安装设置（保持 UUID 身份），null 清空设置。
   *
   * <p>两个值均相同则不写行、不推进 version；否则 CAS 更新。
   */
  EnvironmentCardDTO updateImportedEnvironment(
      EnvironmentId id,
      String registrationToken,
      EnvironmentInstallConfigDTO installConfig,
      String expectedVersion);

  /**
   * CAS 轮换 registrationToken，返回新生成的 token。
   *
   * <p>轮换不要求 Environment 离线：已有连接的 lease 继续有效，下一次 HELLO 必须使用新 token。
   */
  EnvironmentCardDTO rotateToken(EnvironmentId id, String expectedVersion);

  /**
   * CAS 删除 Environment Card。必须校验： 1. 无活跃连接（OFFLINE）； 2. 无 AgentDefinition 引用； 3. 无活跃 harness_work。
   */
  void delete(EnvironmentId id, String expectedVersion);
}
