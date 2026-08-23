package fun.fengwk.kkstudio.platform.comfyui.workflow_api.repo.impl;

import fun.fengwk.convention4j.api.page.Page;
import fun.fengwk.convention4j.api.page.PageQuery;
import fun.fengwk.convention4j.common.page.Pages;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.comfyui.workflow_api.repo.ComfyuiWorkflowApiRepository;
import fun.fengwk.kkstudio.platform.comfyui.workflow_api.repo.impl.mapper.ComfyuiWorkflowApiMapper;
import fun.fengwk.kkstudio.platform.comfyui.workflow_api.repo.impl.model.ComfyuiWorkflowApiDO;
import fun.fengwk.kkstudio.platform.comfyui.workflow_api.service.model.ComfyuiWorkflowApi;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * @author fengwk
 */
@AllArgsConstructor
@Repository
public class PostgresqlComfyuiWorkflowApiRepository implements ComfyuiWorkflowApiRepository {

  private final ComfyuiWorkflowApiMapper comfyuiWorkflowApiMapper;

  @Override
  public Page<ComfyuiWorkflowApi> page(PageQuery pageQuery) {
    long offset = Pages.queryOffset(pageQuery);
    int limit = Pages.queryLimit(pageQuery);
    List<ComfyuiWorkflowApiDO> result = comfyuiWorkflowApiMapper.pageAll(offset, limit);
    long totalCount = comfyuiWorkflowApiMapper.countAll();
    return Pages.page(pageQuery, result, totalCount).map(this::convert);
  }

  @Override
  public ComfyuiWorkflowApi getById(UUID id) {
    return convert(comfyuiWorkflowApiMapper.getById(id));
  }

  @Override
  public ComfyuiWorkflowApi getByApiName(String apiName) {
    return convert(comfyuiWorkflowApiMapper.getByApiName(apiName));
  }

  @Override
  public ComfyuiWorkflowApi getEnabledByApiName(String apiName) {
    return convert(comfyuiWorkflowApiMapper.getByApiNameAndEnabled(apiName, true));
  }

  @Override
  public boolean create(ComfyuiWorkflowApi row) {
    return comfyuiWorkflowApiMapper.insert(convert(row)) == 1;
  }

  @Override
  public boolean updateById(ComfyuiWorkflowApi row) {
    return comfyuiWorkflowApiMapper.updateById(convert(row)) == 1;
  }

  @Override
  public boolean deleteById(UUID id) {
    return comfyuiWorkflowApiMapper.deleteById(id) == 1;
  }

  private ComfyuiWorkflowApiDO convert(ComfyuiWorkflowApi row) {
    if (row == null) {
      return null;
    }
    ComfyuiWorkflowApiDO rowDO = new ComfyuiWorkflowApiDO();
    rowDO.setId(row.getId());
    rowDO.setApiName(row.getApiName());
    rowDO.setName(row.getName());
    rowDO.setDescription(row.getDescription());
    rowDO.setWorkflowJson(row.getWorkflowJson());
    rowDO.setInputBindingsJson(row.getInputBindingsJson());
    rowDO.setDefaultSelector(row.getDefaultSelector());
    rowDO.setEnabled(row.getEnabled());
    return rowDO;
  }

  private ComfyuiWorkflowApi convert(ComfyuiWorkflowApiDO rowDO) {
    if (rowDO == null) {
      return null;
    }
    ComfyuiWorkflowApi row = new ComfyuiWorkflowApi();
    row.setId(rowDO.getId());
    row.setApiName(rowDO.getApiName());
    row.setName(rowDO.getName());
    row.setDescription(rowDO.getDescription());
    row.setWorkflowJson(rowDO.getWorkflowJson());
    row.setInputBindingsJson(rowDO.getInputBindingsJson());
    row.setDefaultSelector(rowDO.getDefaultSelector());
    row.setEnabled(rowDO.getEnabled());
    row.setCreateTime(toLocalDateTime(rowDO.getCreateTime()));
    row.setUpdateTime(toLocalDateTime(rowDO.getUpdateTime()));
    return row;
  }

  private static LocalDateTime toLocalDateTime(OffsetDateTime value) {
    return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
  }
}
