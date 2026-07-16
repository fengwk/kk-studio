package fun.fengwk.kkstudio.core.harness.usage.store.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import fun.fengwk.kkstudio.core.harness.usage.store.model.ModelUsageRecordDO;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/**
 * model_usage_record 行级 MyBatis 映射；幂等由 unique(assistant_entry_id) 与 unique(run_id, attempt,
 * turn_index) 兜底。
 */
@Mapper
public interface ModelUsageRecordMapper extends BaseMapper {

  String COLUMNS =
      "id, session_id, run_id, assistant_entry_id, attempt, turn_index,"
          + " provider_resource_id, model_resource_id, provider_type, provider_model_id,"
          + " prompt_cache_mode, prompt_cache_retention, cache_eligible, cache_affinity_key,"
          + " stop_reason,"
          + " usage_input_tokens, usage_output_tokens, usage_cache_read_tokens,"
          + " usage_cache_write_tokens, usage_cache_write_long_tokens, usage_reasoning_tokens,"
          + " usage_provider_total_tokens,"
          + " cost_currency, cost_input, cost_output, cost_cache_read, cost_cache_write,"
          + " cost_cache_write_long, cost_reasoning, cost_total,"
          + " pricing_currency, pricing_tier, pricing_service_tier,"
          + " pricing_service_tier_multiplier, pricing_version,"
          + " pricing_input_per_million_tokens, pricing_output_per_million_tokens,"
          + " pricing_cache_read_per_million_tokens, pricing_cache_write_per_million_tokens,"
          + " pricing_cache_write_long_per_million_tokens, pricing_reasoning_per_million_tokens,"
          + " request_id, reported_service_tier, raw_usage_json,"
          + " gmt_create as create_time";

  @Insert(
      """
      insert into model_usage_record (
          id, session_id, run_id, assistant_entry_id, attempt, turn_index,
          provider_resource_id, model_resource_id, provider_type, provider_model_id,
          prompt_cache_mode, prompt_cache_retention, cache_eligible, cache_affinity_key,
          stop_reason,
          usage_input_tokens, usage_output_tokens, usage_cache_read_tokens,
          usage_cache_write_tokens, usage_cache_write_long_tokens, usage_reasoning_tokens,
          usage_provider_total_tokens,
          cost_currency, cost_input, cost_output, cost_cache_read, cost_cache_write,
          cost_cache_write_long, cost_reasoning, cost_total,
          pricing_currency, pricing_tier, pricing_service_tier,
          pricing_service_tier_multiplier, pricing_version,
          pricing_input_per_million_tokens, pricing_output_per_million_tokens,
          pricing_cache_read_per_million_tokens, pricing_cache_write_per_million_tokens,
          pricing_cache_write_long_per_million_tokens, pricing_reasoning_per_million_tokens,
          request_id, reported_service_tier, raw_usage_json,
          gmt_create
      ) values (
          #{id}, #{sessionId}, #{runId}, #{assistantEntryId}, #{attempt}, #{turnIndex},
          #{providerResourceId}, #{modelResourceId}, #{providerType}, #{providerModelId},
          #{promptCacheMode}, #{promptCacheRetention}, #{cacheEligible}, #{cacheAffinityKey},
          #{stopReason},
          #{usageInputTokens}, #{usageOutputTokens}, #{usageCacheReadTokens},
          #{usageCacheWriteTokens}, #{usageCacheWriteLongTokens}, #{usageReasoningTokens},
          #{usageProviderTotalTokens},
          #{costCurrency}, #{costInput}, #{costOutput}, #{costCacheRead}, #{costCacheWrite},
          #{costCacheWriteLong}, #{costReasoning}, #{costTotal},
          #{pricingCurrency}, #{pricingTier}, #{pricingServiceTier},
          #{pricingServiceTierMultiplier}, #{pricingVersion},
          #{pricingInputPerMillionTokens}, #{pricingOutputPerMillionTokens},
          #{pricingCacheReadPerMillionTokens}, #{pricingCacheWritePerMillionTokens},
          #{pricingCacheWriteLongPerMillionTokens}, #{pricingReasoningPerMillionTokens},
          #{requestId}, #{reportedServiceTier}, #{rawUsageJson},
          #{createTime}
      )
      """)
  int insert(ModelUsageRecordDO row);

  @Select(
      "select "
          + COLUMNS
          + " from model_usage_record where assistant_entry_id = #{assistantEntryId}")
  @Results(
      id = "modelUsageRecordResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "run_id", property = "runId"),
        @Result(column = "assistant_entry_id", property = "assistantEntryId"),
        @Result(column = "attempt", property = "attempt"),
        @Result(column = "turn_index", property = "turnIndex"),
        @Result(column = "provider_resource_id", property = "providerResourceId"),
        @Result(column = "model_resource_id", property = "modelResourceId"),
        @Result(column = "provider_type", property = "providerType"),
        @Result(column = "provider_model_id", property = "providerModelId"),
        @Result(column = "prompt_cache_mode", property = "promptCacheMode"),
        @Result(column = "prompt_cache_retention", property = "promptCacheRetention"),
        @Result(column = "cache_eligible", property = "cacheEligible"),
        @Result(column = "cache_affinity_key", property = "cacheAffinityKey"),
        @Result(column = "stop_reason", property = "stopReason"),
        @Result(column = "usage_input_tokens", property = "usageInputTokens"),
        @Result(column = "usage_output_tokens", property = "usageOutputTokens"),
        @Result(column = "usage_cache_read_tokens", property = "usageCacheReadTokens"),
        @Result(column = "usage_cache_write_tokens", property = "usageCacheWriteTokens"),
        @Result(column = "usage_cache_write_long_tokens", property = "usageCacheWriteLongTokens"),
        @Result(column = "usage_reasoning_tokens", property = "usageReasoningTokens"),
        @Result(column = "usage_provider_total_tokens", property = "usageProviderTotalTokens"),
        @Result(column = "cost_currency", property = "costCurrency"),
        @Result(column = "cost_input", property = "costInput"),
        @Result(column = "cost_output", property = "costOutput"),
        @Result(column = "cost_cache_read", property = "costCacheRead"),
        @Result(column = "cost_cache_write", property = "costCacheWrite"),
        @Result(column = "cost_cache_write_long", property = "costCacheWriteLong"),
        @Result(column = "cost_reasoning", property = "costReasoning"),
        @Result(column = "cost_total", property = "costTotal"),
        @Result(column = "pricing_currency", property = "pricingCurrency"),
        @Result(column = "pricing_tier", property = "pricingTier"),
        @Result(column = "pricing_service_tier", property = "pricingServiceTier"),
        @Result(
            column = "pricing_service_tier_multiplier",
            property = "pricingServiceTierMultiplier"),
        @Result(column = "pricing_version", property = "pricingVersion"),
        @Result(
            column = "pricing_input_per_million_tokens",
            property = "pricingInputPerMillionTokens"),
        @Result(
            column = "pricing_output_per_million_tokens",
            property = "pricingOutputPerMillionTokens"),
        @Result(
            column = "pricing_cache_read_per_million_tokens",
            property = "pricingCacheReadPerMillionTokens"),
        @Result(
            column = "pricing_cache_write_per_million_tokens",
            property = "pricingCacheWritePerMillionTokens"),
        @Result(
            column = "pricing_cache_write_long_per_million_tokens",
            property = "pricingCacheWriteLongPerMillionTokens"),
        @Result(
            column = "pricing_reasoning_per_million_tokens",
            property = "pricingReasoningPerMillionTokens"),
        @Result(column = "request_id", property = "requestId"),
        @Result(column = "reported_service_tier", property = "reportedServiceTier"),
        @Result(column = "raw_usage_json", property = "rawUsageJson"),
        @Result(column = "create_time", property = "createTime")
      })
  ModelUsageRecordDO findByAssistantEntryId(@Param("assistantEntryId") long assistantEntryId);

  @Select("select " + COLUMNS + " from model_usage_record where run_id = #{runId} order by id asc")
  @ResultMap("modelUsageRecordResultMap")
  List<ModelUsageRecordDO> listByRunId(@Param("runId") long runId);

  @Select(
      "select "
          + COLUMNS
          + " from model_usage_record where session_id = #{sessionId} order by id asc")
  @ResultMap("modelUsageRecordResultMap")
  List<ModelUsageRecordDO> listBySessionId(@Param("sessionId") long sessionId);

  @Select(
      "select "
          + COLUMNS
          + " from model_usage_record where model_resource_id = #{modelResourceId} order by id asc")
  @ResultMap("modelUsageRecordResultMap")
  List<ModelUsageRecordDO> listByModelResourceId(@Param("modelResourceId") long modelResourceId);
}
