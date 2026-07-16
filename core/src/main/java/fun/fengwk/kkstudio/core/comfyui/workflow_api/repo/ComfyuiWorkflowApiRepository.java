package fun.fengwk.kkstudio.core.comfyui.workflow_api.repo;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.kkstudio.core.comfyui.workflow_api.service.model.ComfyuiWorkflowApi;

/**
 * @author fengwk
 */
public interface ComfyuiWorkflowApiRepository {

  Page<ComfyuiWorkflowApi> page(PageQuery pageQuery);

  ComfyuiWorkflowApi getById(long id);

  ComfyuiWorkflowApi getByApiName(String apiName);

  /** 用于 runtime 提交路径：按 apiName 查找时仅暴露已启用的卡，避免业务侧引用配置中已禁用的入口。 */
  ComfyuiWorkflowApi getEnabledByApiName(String apiName);

  boolean create(ComfyuiWorkflowApi row);

  boolean updateById(ComfyuiWorkflowApi row);

  boolean deleteById(long id);
}
