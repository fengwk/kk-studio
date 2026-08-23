package fun.fengwk.kkstudio.platform.comfyui.workflow_api.service;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.share.comfyui.ComfyuiWorkflowApiCreateDTO;
import fun.fengwk.kkstudio.share.comfyui.ComfyuiWorkflowApiDTO;
import fun.fengwk.kkstudio.share.comfyui.ComfyuiWorkflowApiUpdateDTO;

/**
 * 卡片写路径以 canonical UUID string ID 暴露给 HTTP / DTO 边界（应用侧 {@code UUID.randomUUID()} 分配）；持久层直接以 {@link
 * java.util.UUID} 访问数据库。apiName 保持为稳定自然键。
 *
 * @author fengwk
 */
public interface ComfyuiWorkflowApiService {

  Page<ComfyuiWorkflowApiDTO> pageWorkflows(PageQuery pageQuery);

  ComfyuiWorkflowApiDTO createWorkflow(ComfyuiWorkflowApiCreateDTO createDTO);

  ComfyuiWorkflowApiDTO updateWorkflow(String id, ComfyuiWorkflowApiUpdateDTO updateDTO);

  void deleteWorkflow(String id);
}
