package fun.fengwk.kkstudio.platform.ai.environment.service;

import fun.fengwk.kkstudio.share.ai.environment.LiveEnvironmentDTO;

import java.util.List;

/**
 * 面向应用的内存 live Environment registry 只读 API。
 *
 * <p>把 registry 领域对象（包括 harness tool/skill descriptor）映射为 share DTO，使 Web controller 不依赖 harness
 * 类型。
 */
public interface LiveEnvironmentQueryService {

  /** 返回当前注册的全部 live environment 的紧凑 DTO 快照。 */
  List<LiveEnvironmentDTO> listEnvironments();
}
