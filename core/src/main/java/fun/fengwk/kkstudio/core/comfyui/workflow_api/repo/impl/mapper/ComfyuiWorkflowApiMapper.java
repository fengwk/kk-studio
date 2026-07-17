package fun.fengwk.kkstudio.core.comfyui.workflow_api.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import fun.fengwk.kkstudio.core.comfyui.workflow_api.repo.impl.model.ComfyuiWorkflowApiDO;

import java.util.List;

/**
 * @author fengwk
 */
@Mapper
public interface ComfyuiWorkflowApiMapper extends BaseMapper {

  @Select("select count(*) from comfyui_workflow_api")
  long countAll();

  @Select(
      """
        select
            id,
            api_name,
            name,
            description,
            workflow_json,
            input_bindings_json,
            default_selector,
            enabled,
            gmt_create as create_time,
            gmt_modified as update_time
        from comfyui_workflow_api
        order by id asc
        limit #{offset}, #{limit}
        """)
  @Results(
      id = "comfyuiWorkflowApiResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "api_name", property = "apiName"),
        @Result(column = "name", property = "name"),
        @Result(column = "description", property = "description"),
        @Result(column = "workflow_json", property = "workflowJson"),
        @Result(column = "input_bindings_json", property = "inputBindingsJson"),
        @Result(column = "default_selector", property = "defaultSelector"),
        @Result(column = "enabled", property = "enabled"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  List<ComfyuiWorkflowApiDO> pageAll(@Param("offset") long offset, @Param("limit") int limit);

  @Select(
      """
        select
            id,
            api_name,
            name,
            description,
            workflow_json,
            input_bindings_json,
            default_selector,
            enabled,
            gmt_create as create_time,
            gmt_modified as update_time
        from comfyui_workflow_api
        where id = #{id}
        """)
  @ResultMap("comfyuiWorkflowApiResultMap")
  ComfyuiWorkflowApiDO getById(@Param("id") long id);

  @Select(
      """
        select
            id,
            api_name,
            name,
            description,
            workflow_json,
            input_bindings_json,
            default_selector,
            enabled,
            gmt_create as create_time,
            gmt_modified as update_time
        from comfyui_workflow_api
        where api_name = #{apiName}
        """)
  @ResultMap("comfyuiWorkflowApiResultMap")
  ComfyuiWorkflowApiDO getByApiName(@Param("apiName") String apiName);

  @Select(
      """
        select
            id,
            api_name,
            name,
            description,
            workflow_json,
            input_bindings_json,
            default_selector,
            enabled,
            gmt_create as create_time,
            gmt_modified as update_time
        from comfyui_workflow_api
        where api_name = #{apiName} and enabled = #{enabled}
        """)
  @ResultMap("comfyuiWorkflowApiResultMap")
  ComfyuiWorkflowApiDO getByApiNameAndEnabled(
      @Param("apiName") String apiName, @Param("enabled") boolean enabled);

  @Insert(
      """
        insert into comfyui_workflow_api (
            id,
            api_name,
            name,
            description,
            workflow_json,
            input_bindings_json,
            default_selector,
            enabled,
            gmt_create,
            gmt_modified,
            version
        ) values (
            #{id},
            #{apiName},
            #{name},
            #{description},
            #{workflowJson},
            #{inputBindingsJson},
            #{defaultSelector},
            #{enabled},
            current_timestamp(3),
            current_timestamp(3),
            0
        )
        """)
  int insert(ComfyuiWorkflowApiDO row);

  @Update(
      """
        update comfyui_workflow_api
        set
            api_name = #{row.apiName},
            name = #{row.name},
            description = #{row.description},
            workflow_json = #{row.workflowJson},
            input_bindings_json = #{row.inputBindingsJson},
            default_selector = #{row.defaultSelector},
            enabled = #{row.enabled},
            gmt_modified = current_timestamp(3),
            version = version + 1
        where id = #{row.id}
        """)
  int updateById(@Param("row") ComfyuiWorkflowApiDO row);

  @Delete("delete from comfyui_workflow_api where id = #{id}")
  int deleteById(@Param("id") long id);
}
