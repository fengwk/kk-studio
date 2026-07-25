package fun.fengwk.kkstudio.core.comfyui.workflow_api.service;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.share.model.ComfyuiWorkflowApiCreateDTO;
import fun.fengwk.kkstudio.share.model.ComfyuiWorkflowApiDTO;
import fun.fengwk.kkstudio.share.model.ComfyuiWorkflowApiUpdateDTO;

/**
 * 卡片写路径以正的十进制字符串 ID 暴露给 HTTP / DTO 边界（PostgreSQL sequence 分配）；持久层在内部转换为 {@code long} 后再访问数据库。
 *
 * @author fengwk
 */
public interface ComfyuiWorkflowApiService {

  Page<ComfyuiWorkflowApiDTO> pageWorkflows(PageQuery pageQuery);

  ComfyuiWorkflowApiDTO createWorkflow(ComfyuiWorkflowApiCreateDTO createDTO);

  ComfyuiWorkflowApiDTO updateWorkflow(String id, ComfyuiWorkflowApiUpdateDTO updateDTO);

  void deleteWorkflow(String id);
}
