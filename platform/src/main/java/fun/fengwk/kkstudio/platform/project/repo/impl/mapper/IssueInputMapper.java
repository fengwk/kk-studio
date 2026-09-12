package fun.fengwk.kkstudio.platform.project.repo.impl.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import fun.fengwk.kkstudio.platform.project.repo.impl.model.IssueInputDO;

import java.util.List;
import java.util.UUID;

@Mapper
public interface IssueInputMapper extends BaseMapper {

  String COLUMNS = "issue_id, sequence, kind, body, idempotency_key, created_at";

  @Insert(
      """
      insert into issue_input (
          issue_id, sequence, kind, body, idempotency_key, created_at
      ) values (
          #{issueId}, #{sequence}, #{kind}, #{body}, #{idempotencyKey}, clock_timestamp()
      )
      """)
  int insert(IssueInputDO input);

  @Select(
      """
      select issue_id, sequence, kind, body, idempotency_key, created_at
      from issue_input
      where issue_id = #{issueId} and idempotency_key = #{idempotencyKey}
      """)
  @Results(
      id = "issueInputResultMap",
      value = {
        @Result(column = "issue_id", property = "issueId"),
        @Result(column = "sequence", property = "sequence"),
        @Result(column = "kind", property = "kind"),
        @Result(column = "body", property = "body"),
        @Result(column = "idempotency_key", property = "idempotencyKey"),
        @Result(column = "created_at", property = "createdAt")
      })
  IssueInputDO findByIdempotencyKey(
      @Param("issueId") UUID issueId, @Param("idempotencyKey") String idempotencyKey);

  @Select(
      "select " + COLUMNS + " from issue_input where issue_id = #{issueId} order by sequence asc")
  @ResultMap("issueInputResultMap")
  List<IssueInputDO> listByIssueId(@Param("issueId") UUID issueId);

  @Select(
      """
      select issue_id, sequence, kind, body, idempotency_key, created_at
      from issue_input
      where issue_id = #{issueId} and sequence > #{afterSequence}
      order by sequence asc
      """)
  @ResultMap("issueInputResultMap")
  List<IssueInputDO> listAfterSequence(
      @Param("issueId") UUID issueId, @Param("afterSequence") long afterSequence);
}
