package fun.fengwk.kkstudio.platform.environment.service;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCardDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentUpdateDTO;

import java.util.List;

/** 稳定 Environment Card 业务服务。 */
public interface EnvironmentService {

  /** 创建 Environment Card，返回包含一次性 registrationToken 的 DTO。 */
  EnvironmentCardDTO create(EnvironmentCreateDTO dto);

  /** 查询单个 Environment Card 详情，registrationToken 绝不暴露。 */
  EnvironmentCardDTO get(EnvironmentId id);

  /** 列表查询所有 Environment Cards，包含 live 状态投影，registrationToken 绝不暴露。 */
  List<EnvironmentCardDTO> list();

  /** CAS 更新 Environment 名称，registrationToken 绝不暴露。 */
  EnvironmentCardDTO update(EnvironmentId id, EnvironmentUpdateDTO dto, String expectedVersion);

  /** 仅在 OFFLINE 状态下 CAS 轮换 registrationToken，返回包含新生成的 registrationToken 的 DTO。 */
  EnvironmentCardDTO rotateToken(EnvironmentId id, String expectedVersion);

  /**
   * CAS 删除 Environment Card。必须校验： 1. 无活跃连接（OFFLINE）； 2. 无 AgentDefinition 引用； 3. 无活跃 harness_work。
   */
  void delete(EnvironmentId id, String expectedVersion);
}
