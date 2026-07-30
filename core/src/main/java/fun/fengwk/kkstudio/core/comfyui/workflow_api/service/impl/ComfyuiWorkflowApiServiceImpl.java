package fun.fengwk.kkstudio.core.comfyui.workflow_api.service.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.comfyui.workflow_api.repo.ComfyuiWorkflowApiRepository;
import fun.fengwk.kkstudio.core.comfyui.workflow_api.service.ComfyuiWorkflowApiService;
import fun.fengwk.kkstudio.core.comfyui.workflow_api.service.converter.ComfyuiWorkflowApiConverter;
import fun.fengwk.kkstudio.core.comfyui.workflow_api.service.model.ComfyuiWorkflowApi;
import fun.fengwk.kkstudio.share.comfyui.ComfyuiWorkflowApiCreateDTO;
import fun.fengwk.kkstudio.share.comfyui.ComfyuiWorkflowApiDTO;
import fun.fengwk.kkstudio.share.comfyui.ComfyuiWorkflowApiUpdateDTO;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Service
public class ComfyuiWorkflowApiServiceImpl implements ComfyuiWorkflowApiService {

  private final ComfyuiWorkflowApiRepository comfyuiWorkflowApiRepository;
  private final ComfyuiWorkflowApiConverter comfyuiWorkflowApiConverter;
  private final ComfyuiWorkflowApiMutationFactory mutationFactory;
  private final ComfyuiWorkflowApiGuard guard;

  @Override
  public Page<ComfyuiWorkflowApiDTO> pageWorkflows(PageQuery pageQuery) {
    return comfyuiWorkflowApiRepository.page(pageQuery).map(comfyuiWorkflowApiConverter::convert);
  }

  @Override
  public ComfyuiWorkflowApiDTO createWorkflow(ComfyuiWorkflowApiCreateDTO createDTO) {
    ComfyuiWorkflowApiMutationFactory.Mutation mutation =
        mutationFactory.newCreateMutation(createDTO);
    guard.ensureApiNameAvailable(mutation.apiName());
    ComfyuiWorkflowApi row = mutationFactory.newWorkflow(mutation);
    if (!comfyuiWorkflowApiRepository.create(row)) {
      throw new IllegalStateException("create comfyui workflow api failed");
    }
    return comfyuiWorkflowApiConverter.convert(comfyuiWorkflowApiRepository.getById(row.getId()));
  }

  @Override
  public ComfyuiWorkflowApiDTO updateWorkflow(String id, ComfyuiWorkflowApiUpdateDTO updateDTO) {
    ComfyuiWorkflowApi existing = guard.requireWorkflow(id);
    ComfyuiWorkflowApiMutationFactory.Mutation mutation =
        mutationFactory.newUpdateMutation(existing.getApiName(), updateDTO);
    guard.ensureApiNameAvailable(existing.getApiName(), mutation.apiName());
    mutationFactory.apply(existing, mutation);
    if (!comfyuiWorkflowApiRepository.updateById(existing)) {
      throw new IllegalStateException("update comfyui workflow api failed: " + id);
    }
    return comfyuiWorkflowApiConverter.convert(
        comfyuiWorkflowApiRepository.getById(existing.getId()));
  }

  @Override
  public void deleteWorkflow(String id) {
    ComfyuiWorkflowApi existing = guard.requireWorkflow(id);
    if (!comfyuiWorkflowApiRepository.deleteById(existing.getId())) {
      throw new IllegalStateException("delete comfyui workflow api failed: " + id);
    }
  }
}
