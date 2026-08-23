package fun.fengwk.kkstudio.platform.comfyui.workflow_api.service.converter;

import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.comfyui.workflow_api.service.ComfyuiWorkflowApiIds;
import fun.fengwk.kkstudio.platform.comfyui.workflow_api.service.model.ComfyuiWorkflowApi;
import fun.fengwk.kkstudio.share.comfyui.ComfyuiWorkflowApiDTO;

/**
 * @author fengwk
 */
@Component
public class ComfyuiWorkflowApiConverter {

  public ComfyuiWorkflowApiDTO convert(ComfyuiWorkflowApi row) {
    if (row == null) {
      return null;
    }
    ComfyuiWorkflowApiDTO dto = new ComfyuiWorkflowApiDTO();
    dto.setId(row.getId() == null ? null : ComfyuiWorkflowApiIds.format(row.getId()));
    dto.setApiName(row.getApiName());
    dto.setName(row.getName());
    dto.setDescription(row.getDescription());
    dto.setWorkflowJson(row.getWorkflowJson());
    dto.setInputBindingsJson(row.getInputBindingsJson());
    dto.setDefaultSelector(row.getDefaultSelector());
    dto.setEnabled(row.getEnabled());
    dto.setCreateTime(row.getCreateTime());
    dto.setUpdateTime(row.getUpdateTime());
    return dto;
  }
}
