package fun.fengwk.kkstudio.core.ai.runtime.skill;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Narrow read adapter for the frozen Model request that owns a Tool invocation. */
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
      @Param("toolInvocationId") long toolInvocationId, @Param("threadId") long threadId);
}
