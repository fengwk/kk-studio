package fun.fengwk.kkstudio.platform.environment.repo.impl.mapper;

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

import fun.fengwk.kkstudio.platform.environment.repo.impl.model.EnvironmentDO;

import java.util.List;
import java.util.UUID;

@Mapper
public interface EnvironmentMapper extends BaseMapper {

  String COLUMNS =
      "id, name, registration_token, version, "
          + "created_at as create_time, updated_at as update_time";

  @Select(
      "select " + COLUMNS + " from environment order by updated_at desc, created_at desc, name asc")
  @Results(
      id = "environmentResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "name", property = "name"),
        @Result(column = "registration_token", property = "registrationToken"),
        @Result(column = "version", property = "version"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime")
      })
  List<EnvironmentDO> listNewestFirst();

  @Select("select " + COLUMNS + " from environment where id = #{id}")
  @ResultMap("environmentResultMap")
  EnvironmentDO getById(@Param("id") UUID id);

  @Select("select " + COLUMNS + " from environment where name = #{name}")
  @ResultMap("environmentResultMap")
  EnvironmentDO getByName(@Param("name") String name);

  @Select("select " + COLUMNS + " from environment where registration_token = #{registrationToken}")
  @ResultMap("environmentResultMap")
  EnvironmentDO getByRegistrationToken(@Param("registrationToken") String registrationToken);

  @Select("select " + COLUMNS + " from environment where id = #{id} for update")
  @ResultMap("environmentResultMap")
  EnvironmentDO lockById(@Param("id") UUID id);

  @Select("select " + COLUMNS + " from environment where id = #{id} for key share")
  @ResultMap("environmentResultMap")
  EnvironmentDO lockForKeyShare(@Param("id") UUID id);

  @Select("select count(1) > 0 from environment where name = #{name}")
  boolean existsByName(@Param("name") String name);

  @Select("select count(1) > 0 from environment where name = #{name} and id <> #{excludeId}")
  boolean existsByNameExcludingId(@Param("name") String name, @Param("excludeId") UUID excludeId);

  @Insert(
      """
      insert into environment (
          id, name, registration_token, created_at, updated_at, version
      ) values (
          #{id}, #{name}, #{registrationToken}, current_timestamp, current_timestamp, 0
      )
      """)
  int insert(EnvironmentDO environment);

  @Update(
      """
      update environment
      set name = #{environment.name},
          registration_token = #{environment.registrationToken},
          updated_at = greatest(updated_at, current_timestamp),
          version = version + 1
      where id = #{environment.id} and version = #{expectedVersion}
      """)
  int updateById(
      @Param("environment") EnvironmentDO environment,
      @Param("expectedVersion") long expectedVersion);

  @Delete("delete from environment where id = #{id} and version = #{expectedVersion}")
  int deleteById(@Param("id") UUID id, @Param("expectedVersion") long expectedVersion);
}
