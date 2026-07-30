package fun.fengwk.kkstudio.core.ai.runtime.usage.store.mapper;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import fun.fengwk.kkstudio.core.ai.runtime.usage.store.model.ModelUsageRecordDO;

import java.util.List;

/**
 * harness_model_usage 行级 MyBatis 映射；幂等由 unique(assistant_entry_id) 与外键兜底。cost 分项不在 持久层；读取账本时由
 * {@code ModelCost.calculate(pricing, usage)} 重建。
 */
@Mapper
public interface ModelUsageRecordMapper extends BaseMapper {

  String COLUMNS =
      "id, session_id, thread_id, assistant_entry_id,"
          + " provider_resource_id, model_resource_id, provider_type, provider_model_id,"
          + " prompt_cache_mode, prompt_cache_retention, cache_eligible, cache_affinity_key,"
          + " stop_reason,"
          + " usage_input_tokens, usage_output_tokens, usage_cache_read_tokens,"
          + " usage_cache_write_tokens, usage_cache_write_long_tokens, usage_reasoning_tokens,"
          + " usage_provider_total_tokens,"
          + " pricing_currency, pricing_tier, pricing_service_tier,"
          + " pricing_service_tier_multiplier, pricing_version,"
          + " pricing_input_per_million_tokens, pricing_output_per_million_tokens,"
          + " pricing_cache_read_per_million_tokens, pricing_cache_write_per_million_tokens,"
          + " pricing_cache_write_long_per_million_tokens, pricing_reasoning_per_million_tokens,"
          + " request_id, reported_service_tier, raw_usage::text as raw_usage,"
          + " created_at as create_time";

  @Insert(
      """
      insert into harness_model_usage (
          id, session_id, thread_id, assistant_entry_id,
          provider_resource_id, model_resource_id, provider_type, provider_model_id,
          prompt_cache_mode, prompt_cache_retention, cache_eligible, cache_affinity_key,
          stop_reason,
          usage_input_tokens, usage_output_tokens, usage_cache_read_tokens,
          usage_cache_write_tokens, usage_cache_write_long_tokens, usage_reasoning_tokens,
          usage_provider_total_tokens,
          pricing_currency, pricing_tier, pricing_service_tier,
          pricing_service_tier_multiplier, pricing_version,
          pricing_input_per_million_tokens, pricing_output_per_million_tokens,
          pricing_cache_read_per_million_tokens, pricing_cache_write_per_million_tokens,
          pricing_cache_write_long_per_million_tokens, pricing_reasoning_per_million_tokens,
          request_id, reported_service_tier, raw_usage,
          created_at
      ) values (
          #{id}, #{sessionId}, #{threadId}, #{assistantEntryId},
          #{providerResourceId}, #{modelResourceId}, #{providerType}, #{providerModelId},
          #{promptCacheMode}, #{promptCacheRetention}, #{cacheEligible}, #{cacheAffinityKey},
          #{stopReason},
          #{usageInputTokens}, #{usageOutputTokens}, #{usageCacheReadTokens},
          #{usageCacheWriteTokens}, #{usageCacheWriteLongTokens}, #{usageReasoningTokens},
          #{usageProviderTotalTokens},
          #{pricingCurrency}, #{pricingTier}, #{pricingServiceTier},
          #{pricingServiceTierMultiplier}, #{pricingVersion},
          #{pricingInputPerMillionTokens}, #{pricingOutputPerMillionTokens},
          #{pricingCacheReadPerMillionTokens}, #{pricingCacheWritePerMillionTokens},
          #{pricingCacheWriteLongPerMillionTokens}, #{pricingReasoningPerMillionTokens},
          #{requestId}, #{reportedServiceTier}, cast(#{rawUsageJson} as jsonb),
          #{createTime}
      )
      """)
  int insert(ModelUsageRecordDO row);

  @Select(
      "select "
          + COLUMNS
          + " from harness_model_usage where assistant_entry_id = #{assistantEntryId}")
  @Results(
      id = "modelUsageRecordResultMap",
      value = {
        @Result(column = "id", property = "id"),
        @Result(column = "session_id", property = "sessionId"),
        @Result(column = "thread_id", property = "threadId"),
        @Result(column = "assistant_entry_id", property = "assistantEntryId"),
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
        @Result(column = "raw_usage", property = "rawUsageJson"),
        @Result(column = "create_time", property = "createTime")
      })
  ModelUsageRecordDO findByAssistantEntryId(@Param("assistantEntryId") long assistantEntryId);

  @Select(
      "select "
          + COLUMNS
          + " from harness_model_usage where session_id = #{sessionId} order by id asc")
  @ResultMap("modelUsageRecordResultMap")
  List<ModelUsageRecordDO> listBySessionId(@Param("sessionId") long sessionId);

  @Select(
      "select "
          + COLUMNS
          + " from harness_model_usage where model_resource_id = #{modelResourceId} order by id asc")
  @ResultMap("modelUsageRecordResultMap")
  List<ModelUsageRecordDO> listByModelResourceId(@Param("modelResourceId") long modelResourceId);
}
