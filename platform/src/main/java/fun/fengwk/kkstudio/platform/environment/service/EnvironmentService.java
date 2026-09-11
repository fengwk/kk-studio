package fun.fengwk.kkstudio.platform.environment.service;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCardDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentRegistrationTokenDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentUpdateDTO;

import java.util.List;

/** 稳定 Environment Card 业务服务。 */
public interface EnvironmentService {

  /** 创建 Environment Card，返回包含 registrationToken 的 DTO；列表/详情永不返回 token。 */
  EnvironmentCardDTO create(EnvironmentCreateDTO dto);

  /** 查询单个 Environment Card 详情，永不返回 registrationToken。 */
  EnvironmentCardDTO get(EnvironmentId id);

  /** 列表查询所有 Environment Cards，包含 live 状态投影，永不返回 registrationToken。 */
  List<EnvironmentCardDTO> list();

  /** CAS 更新 Environment 名称，永不返回 registrationToken。 */
  EnvironmentCardDTO update(EnvironmentId id, EnvironmentUpdateDTO dto, String expectedVersion);

  /** 幂等只读当前 registrationToken；不轮换、不更新 version/updateTime。 */
  EnvironmentRegistrationTokenDTO getRegistrationToken(EnvironmentId id);

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
