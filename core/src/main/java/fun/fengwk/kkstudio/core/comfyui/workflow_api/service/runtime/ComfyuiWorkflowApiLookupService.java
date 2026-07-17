package fun.fengwk.kkstudio.core.comfyui.workflow_api.service.runtime;

import fun.fengwk.kkstudio.core.comfyui.workflow_api.repo.ComfyuiWorkflowApiRepository;
import fun.fengwk.kkstudio.core.comfyui.workflow_api.service.model.ComfyuiWorkflowApi;

import java.util.Optional;

/**
 * 暴露给 runtime 提交路径使用的只读查询入口。
 *
 * <p>该类只做持久配置的获取与解析，不会执行 workflow 或访问网络。runtime 可在每次提交时通过 apiName 获取最新启用的配置 + 校验过的绑定模型。
 *
 * @author fengwk
 */
public class ComfyuiWorkflowApiLookupService {

  private final ComfyuiWorkflowApiRepository comfyuiWorkflowApiRepository;
  private final ComfyuiWorkflowApiBindingsParser bindingsParser;

  public ComfyuiWorkflowApiLookupService(
      ComfyuiWorkflowApiRepository comfyuiWorkflowApiRepository,
      ComfyuiWorkflowApiBindingsParser bindingsParser) {
    if (comfyuiWorkflowApiRepository == null) {
      throw new IllegalArgumentException("comfyuiWorkflowApiRepository must not be null");
    }
    if (bindingsParser == null) {
      throw new IllegalArgumentException("bindingsParser must not be null");
    }
    this.comfyuiWorkflowApiRepository = comfyuiWorkflowApiRepository;
    this.bindingsParser = bindingsParser;
  }

  /**
   * 仅当配置存在且 enabled=true 时返回绑定模型；否则返回 {@link Optional#empty()}，便于 runtime 直接抛 404 / 403
   * 而不是回落到已禁用的旧配置。
   */
  public Optional<ComfyuiWorkflowApiBindings> findEnabledBindings(String apiName) {
    if (apiName == null || apiName.isBlank()) {
      return Optional.empty();
    }
    ComfyuiWorkflowApi row = comfyuiWorkflowApiRepository.getEnabledByApiName(apiName);
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        bindingsParser.parse(
            row.getWorkflowJson(), row.getInputBindingsJson(), row.getDefaultSelector()));
  }
}
