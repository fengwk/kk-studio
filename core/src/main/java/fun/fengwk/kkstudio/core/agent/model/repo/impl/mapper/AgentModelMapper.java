package fun.fengwk.kkstudio.core.agent.model.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.kkstudio.core.agent.model.repo.impl.model.AgentModelDO;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.kkstudio.core.agent.model.repo.impl.model.AgentModelDO;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * @author fengwk
 */
@Mapper
public interface AgentModelMapper extends BaseMapper {

  @Select("select count(*) from agent_model")
  long countAll();

  @Select(
      """
        select
            id,
            provider_id,
            name,
            description,
            capabilities_json,
            limit_json,
            pricing_json,
            default_variant,
            variants_json,
            gmt_create as create_time,
            gmt_modified as update_time
        from agent_model
        order by provider_id asc, id asc
        limit #{offset}, #{limit}
        """)
  @Results(
      id = "agentModelResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "provider_id", property = "providerId"),
        @Result(column = "name", property = "name"),
        @Result(column = "description", property = "description"),
        @Result(column = "capabilities_json", property = "capabilitiesJson"),
        @Result(column = "limit_json", property = "limitJson"),
        @Result(column = "pricing_json", property = "pricingJson"),
        @Result(column = "default_variant", property = "defaultVariant"),
        @Result(column = "variants_json", property = "variantsJson"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  List<AgentModelDO> pageAll(@Param("offset") long offset, @Param("limit") int limit);

  @Select(
      """
        select
            id,
            provider_id,
            name,
            description,
            capabilities_json,
            limit_json,
            pricing_json,
            default_variant,
            variants_json,
            gmt_create as create_time,
            gmt_modified as update_time
        from agent_model
        where id = #{id}
        """)
  @ResultMap("agentModelResultMap")
  AgentModelDO getById(@Param("id") long id);

  @Select(
      """
        select
            id,
            provider_id,
            name,
            description,
            capabilities_json,
            limit_json,
            pricing_json,
            default_variant,
            variants_json,
            gmt_create as create_time,
            gmt_modified as update_time
        from agent_model
        where provider_id = #{providerId} and name = #{name}
        """)
  @ResultMap("agentModelResultMap")
  AgentModelDO getByProviderIdAndName(
      @Param("providerId") long providerId, @Param("name") String name);

  @Insert(
      """
        insert into agent_model (
            id,
            provider_id,
            name,
            description,
            capabilities_json,
            limit_json,
            pricing_json,
            default_variant,
            variants_json,
            gmt_create,
            gmt_modified,
            version
        ) values (
            #{id},
            #{providerId},
            #{name},
            #{description},
            #{capabilitiesJson},
            #{limitJson},
            #{pricingJson},
            #{defaultVariant},
            #{variantsJson},
            current_timestamp(3),
            current_timestamp(3),
            0
        )
        """)
  int insert(AgentModelDO model);

  @Update(
      """
        update agent_model
        set
            name = #{model.name},
            description = #{model.description},
            capabilities_json = #{model.capabilitiesJson},
            limit_json = #{model.limitJson},
            pricing_json = #{model.pricingJson},
            default_variant = #{model.defaultVariant},
            variants_json = #{model.variantsJson},
            gmt_modified = current_timestamp(3)
        where id = #{model.id}
        """)
  int updateById(@Param("model") AgentModelDO model);

  @Delete("delete from agent_model where id = #{id}")
  int deleteById(@Param("id") long id);

  @Select("select count(*) from agent_definition where default_model_id = #{modelId}")
  long countAgentsByModelId(@Param("modelId") long modelId);
}
