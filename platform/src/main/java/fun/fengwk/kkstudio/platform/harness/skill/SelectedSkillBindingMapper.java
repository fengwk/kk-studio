package fun.fengwk.kkstudio.platform.harness.skill;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

/** 拥有某 Tool invocation 的冻结 Model request 的窄读适配器。 */
@Mapper
public interface SelectedSkillBindingMapper extends BaseMapper {

  @Select(
      """
      select model.request::text
      from harness_tool_invocation tool
      join harness_model_invocation model on model.id = tool.model_invocation_id
      where tool.id = #{toolInvocationId}
        and model.thread_id = #{threadId}
      """)
  String findModelRequest(
      @Param("toolInvocationId") UUID toolInvocationId, @Param("threadId") UUID threadId);
}
