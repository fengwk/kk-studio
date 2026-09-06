package fun.fengwk.kkstudio.platform.comfyui.workflow_api.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;

import fun.fengwk.kkstudio.platform.comfyui.workflow_api.service.model.ComfyuiWorkflowApi;

import java.util.UUID;

/**
 * @author fengwk
 */
public interface ComfyuiWorkflowApiRepository {

  Page<ComfyuiWorkflowApi> page(PageQuery pageQuery);

  ComfyuiWorkflowApi getById(UUID id);

  ComfyuiWorkflowApi getByApiName(String apiName);

  boolean create(ComfyuiWorkflowApi row);

  boolean updateById(ComfyuiWorkflowApi row);

  boolean deleteById(UUID id);
}
