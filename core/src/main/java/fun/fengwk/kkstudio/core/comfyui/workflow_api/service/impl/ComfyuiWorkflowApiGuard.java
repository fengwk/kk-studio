package fun.fengwk.kkstudio.core.comfyui.workflow_api.service.impl;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.comfyui.workflow_api.repo.ComfyuiWorkflowApiRepository;
import fun.fengwk.kkstudio.core.comfyui.workflow_api.service.ComfyuiWorkflowApiIds;
import fun.fengwk.kkstudio.core.comfyui.workflow_api.service.model.ComfyuiWorkflowApi;

import java.util.NoSuchElementException;

/** 统一处理持久配置服务依赖的实体解析与唯一性校验。 */
@AllArgsConstructor
@Component
final class ComfyuiWorkflowApiGuard {

  private final ComfyuiWorkflowApiRepository comfyuiWorkflowApiRepository;

  /**
   * 解析后的 ID 找不到对应记录时抛 {@link NoSuchElementException}（由 controller 翻译为 404）； ID 自身格式错误由 {@link
   * ComfyuiWorkflowApiIds#parsePositive} 抛 {@link IllegalArgumentException}（翻译为 400）。
   */
  ComfyuiWorkflowApi requireWorkflow(String id) {
    long parsed = ComfyuiWorkflowApiIds.parsePositive(id, "id");
    ComfyuiWorkflowApi row = comfyuiWorkflowApiRepository.getById(parsed);
    if (row == null) {
      throw new NoSuchElementException("comfyui workflow api not found: " + id);
    }
    return row;
  }

  void ensureApiNameAvailable(String apiName) {
    if (comfyuiWorkflowApiRepository.getByApiName(apiName) != null) {
      throw new IllegalArgumentException("comfyui workflow api name already exists: " + apiName);
    }
  }

  void ensureApiNameAvailable(String currentApiName, String nextApiName) {
    if (currentApiName == null || !currentApiName.equals(nextApiName)) {
      ensureApiNameAvailable(nextApiName);
    }
  }
}
