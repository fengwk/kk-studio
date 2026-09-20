package fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.model.PluginCredentialDO;

import java.time.Instant;
import java.util.List;

/**
 * {@code plugin_credential} 的原子 SQL 入口。
 *
 * <p>写入只有三种形态：整行 upsert（重新登录）、条件终结（finalize / release，同时匹配 lease token 与 version）与过期 lease 收敛。
 * 领取与终结都靠 {@code for update skip locked} 与 CAS version 在多节点间互斥；任何不匹配的写入都是 0 行更新，调用方据此判定竞争失败而不是覆盖。
 */
@Mapper
public interface PluginCredentialMapper extends BaseMapper {

  String COLUMNS =
      "plugin_id, encrypted_payload, region, expires_at, next_refresh_at, status, "
          + "last_refreshed_at, last_refresh_error, refresh_lease_token, refresh_lease_until, "
          + "version, create_time, update_time";

  @Results(
      id = "pluginCredentialResultMap",
      value = {
        @Result(column = "plugin_id", property = "pluginId"),
        @Result(column = "encrypted_payload", property = "encryptedPayload"),
        @Result(column = "region", property = "region"),
        @Result(column = "expires_at", property = "expiresAt"),
        @Result(column = "next_refresh_at", property = "nextRefreshAt"),
        @Result(column = "status", property = "status"),
        @Result(column = "last_refreshed_at", property = "lastRefreshedAt"),
        @Result(column = "last_refresh_error", property = "lastRefreshError"),
        @Result(column = "refresh_lease_token", property = "refreshLeaseToken"),
        @Result(column = "refresh_lease_until", property = "refreshLeaseUntil"),
        @Result(column = "version", property = "version"),
        @Result(column = "create_time", property = "createTime"),
        @Result(column = "update_time", property = "updateTime"),
      })
  // 这里只能声明 @Results：同时声明 @ResultMap 会让 MyBatis 跳过 result map 定义，使该名字从未注册。
  @Select("select " + COLUMNS + " from plugin_credential where plugin_id = #{pluginId}")
  PluginCredentialDO getByPluginId(@Param("pluginId") String pluginId);

  /** 整行 upsert：重新登录整体替换凭据并清空在途 lease；已存在的行 {@code version + 1}，使任何在途刷新 finalize 都失去围栏。 */
  @Insert(
      """
      insert into plugin_credential (
          plugin_id, encrypted_payload, region, expires_at, next_refresh_at,
          status, last_refreshed_at, last_refresh_error,
          refresh_lease_token, refresh_lease_until, version, create_time, update_time
      ) values (
          #{pluginId}, #{encryptedPayload}, #{region}, #{expiresAt}, #{nextRefreshAt},
          #{status}, null, null,
          null, null, 0, #{createTime}, #{updateTime}
      )
      on conflict (plugin_id) do update set
          encrypted_payload = excluded.encrypted_payload,
          region = excluded.region,
          expires_at = excluded.expires_at,
          next_refresh_at = excluded.next_refresh_at,
          status = excluded.status,
          last_refreshed_at = null,
          last_refresh_error = null,
          refresh_lease_token = null,
          refresh_lease_until = null,
          version = plugin_credential.version + 1,
          update_time = excluded.update_time
      """)
  int upsert(PluginCredentialDO row);

  @Delete("delete from plugin_credential where plugin_id = #{pluginId}")
  int deleteByPluginId(@Param("pluginId") String pluginId);

  /** 过期 in-flight lease 收敛：这些行既不能重新 claim 也不能重放，只能标记为结果未知并清空 lease。 */
  @Update(
      """
      <script>
      update plugin_credential
      set status = 'REFRESH_UNCERTAIN',
          last_refresh_error = #{error},
          refresh_lease_token = null,
          refresh_lease_until = null,
          version = version + 1,
          update_time = #{now}
      where plugin_id in
          <foreach item="pluginId" collection="pluginIds" open="(" separator="," close=")">
              #{pluginId}
          </foreach>
        and refresh_lease_token is not null
        and refresh_lease_until &lt;= #{now}
      </script>
      """)
  int markExpiredLeasesUncertain(
      @Param("pluginIds") List<String> pluginIds,
      @Param("now") Instant now,
      @Param("error") String error);

  /**
   * 领取到期行：只领取已安装 Plugin 中状态可刷新、已到 {@code next_refresh_at} 且 lease 为空或已过期的行；每行领取即推进 version。
   *
   * <p>它是写语句，因此必须显式 {@code flushCache} 且禁止 local cache：否则同一 session 内先读后领取会继续读到领取前的 lease 与
   * version。
   */
  @ResultMap("pluginCredentialResultMap")
  @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
  @Select(
      """
      <script>
      with claimable as (
          select plugin_id
          from plugin_credential
          where plugin_id in
              <foreach item="pluginId" collection="pluginIds" open="(" separator="," close=")">
                  #{pluginId}
              </foreach>
            and status in ('CONNECTED', 'REFRESH_FAILED')
            and next_refresh_at &lt;= #{now}
            and (refresh_lease_token is null or refresh_lease_until &lt;= #{now})
          order by next_refresh_at, plugin_id
          limit #{limit}
          for update skip locked
      )
      update plugin_credential credential
      set refresh_lease_token = #{leaseToken},
          refresh_lease_until = #{leaseUntil},
          version = credential.version + 1,
          update_time = #{now}
      from claimable
      where credential.plugin_id = claimable.plugin_id
      returning
          credential.plugin_id, credential.encrypted_payload, credential.region,
          credential.expires_at, credential.next_refresh_at, credential.status,
          credential.last_refreshed_at, credential.last_refresh_error,
          credential.refresh_lease_token, credential.refresh_lease_until,
          credential.version, credential.create_time, credential.update_time
      </script>
      """)
  List<PluginCredentialDO> claimDue(
      @Param("pluginIds") List<String> pluginIds,
      @Param("now") Instant now,
      @Param("leaseUntil") Instant leaseUntil,
      @Param("leaseToken") String leaseToken,
      @Param("limit") int limit);

  /** 成功终结：lease token 与 version 任一不匹配即 0 行，迟到写入不覆盖新凭据。 */
  @Update(
      """
      update plugin_credential
      set encrypted_payload = #{encryptedPayload},
          region = #{region},
          expires_at = #{expiresAt},
          next_refresh_at = #{nextRefreshAt},
          status = 'CONNECTED',
          last_refreshed_at = #{refreshedAt},
          last_refresh_error = null,
          refresh_lease_token = null,
          refresh_lease_until = null,
          version = version + 1,
          update_time = #{now}
      where plugin_id = #{pluginId}
        and refresh_lease_token = #{leaseToken}
        and version = #{version}
      """)
  int finalizeSuccess(
      @Param("pluginId") String pluginId,
      @Param("leaseToken") String leaseToken,
      @Param("version") long version,
      @Param("encryptedPayload") byte[] encryptedPayload,
      @Param("region") String region,
      @Param("expiresAt") Instant expiresAt,
      @Param("nextRefreshAt") Instant nextRefreshAt,
      @Param("refreshedAt") Instant refreshedAt,
      @Param("now") Instant now);

  /** 失败终结：终态与有界去敏错误同一条语句写入，lease token 与 version 任一不匹配即 0 行。 */
  @Update(
      """
      update plugin_credential
      set status = #{status},
          next_refresh_at = #{nextRefreshAt},
          last_refresh_error = #{error},
          refresh_lease_token = null,
          refresh_lease_until = null,
          version = version + 1,
          update_time = #{now}
      where plugin_id = #{pluginId}
        and refresh_lease_token = #{leaseToken}
        and version = #{version}
      """)
  int finalizeFailure(
      @Param("pluginId") String pluginId,
      @Param("leaseToken") String leaseToken,
      @Param("version") long version,
      @Param("status") String status,
      @Param("nextRefreshAt") Instant nextRefreshAt,
      @Param("error") String error,
      @Param("now") Instant now);

  /** 释放 lease 且不改状态；lease token 与 version 任一不匹配即 0 行。 */
  @Update(
      """
      update plugin_credential
      set refresh_lease_token = null,
          refresh_lease_until = null,
          version = version + 1,
          update_time = #{now}
      where plugin_id = #{pluginId}
        and refresh_lease_token = #{leaseToken}
        and version = #{version}
      """)
  int releaseLease(
      @Param("pluginId") String pluginId,
      @Param("leaseToken") String leaseToken,
      @Param("version") long version,
      @Param("now") Instant now);
}
