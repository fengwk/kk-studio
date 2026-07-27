package fun.fengwk.kkstudio.core.harness.thread.reconcile;

import fun.fengwk.convention4j.springboot.starter.mybatis.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageDraft;

import java.time.OffsetDateTime;
import java.util.List;

/** final schema Thread reconcile 所有 SQL 的窄 MyBatis mapper。 */
@Mapper
public interface ThreadReconcileMapper extends BaseMapper {

  @Select(
      "update harness_thread set processor_token = #{token}, processor_until = #{until}, updated_at = greatest(updated_at, #{now}) "
          + "where id = #{threadId} and runnable and head_entry_id is not null "
          + "and (processor_token is null or processor_until <= #{now}) returning execution_epoch")
  Long claim(
      @Param("threadId") long threadId,
      @Param("token") String token,
      @Param("until") OffsetDateTime until,
      @Param("now") OffsetDateTime now);

  @Update(
      "update harness_thread set processor_until = greatest(processor_until, #{until}), updated_at = greatest(updated_at, #{now}) "
          + "where id = #{threadId} and execution_epoch = #{epoch} and processor_token = #{token} and processor_until > #{now}")
  int renew(
      @Param("threadId") long threadId,
      @Param("epoch") long epoch,
      @Param("token") String token,
      @Param("until") OffsetDateTime until,
      @Param("now") OffsetDateTime now);

  @Select(
      "select t.id, e.session_id as sessionId, t.head_entry_id as headEntryId, t.input_sequence as inputSequence, t.runnable, t.execution_epoch as executionEpoch, t.processor_token as processorToken, t.processor_until as processorUntil, t.created_at as createdAt, t.updated_at as updatedAt "
          + "from harness_thread t join harness_entry e on e.id = t.head_entry_id where t.id = #{threadId} for no key update of t")
  ThreadReconcileRow lockThread(@Param("threadId") long threadId);

  @Select(
      "select id, thread_id as threadId, source_head_entry_id as sourceHeadEntryId, execution_epoch as executionEpoch, request::text as requestJson, status, result::text as resultJson, error::text as errorJson, finished_at as finishedAt "
          + "from harness_model_invocation where thread_id = #{threadId} and source_head_entry_id = #{sourceHeadEntryId} and execution_epoch = #{epoch} and applied_at is null "
          + "and status in ('SUCCEEDED','FAILED','CANCELLED','UNKNOWN') order by id limit 1 for update")
  ThreadReconcileRow findTerminalModel(
      @Param("threadId") long threadId,
      @Param("sourceHeadEntryId") long sourceHeadEntryId,
      @Param("epoch") long epoch);

  @Select(
      "select id, thread_id as threadId, assistant_entry_id as headEntryId, execution_epoch as executionEpoch, ordinal, tool_call_id as toolCallId, descriptor::text as descriptorJson, applied_at as appliedAt, "
          + "result::text as resultJson, error::text as errorJson, status, finished_at as finishedAt from harness_tool_invocation "
          + "where thread_id = #{threadId} and assistant_entry_id = #{headEntryId} and execution_epoch = #{epoch} order by ordinal")
  List<ThreadReconcileRow> listToolSiblings(
      @Param("threadId") long threadId,
      @Param("headEntryId") long headEntryId,
      @Param("epoch") long epoch);

  @Select(
      "select id, thread_id as threadId, sequence, input_type as inputType, payload::text as payloadJson, idempotency_key as idempotencyKey, status, created_at as createdAt, applied_at as appliedAt "
          + "from harness_thread_input where thread_id = #{threadId} and status = 'QUEUED' order by sequence")
  List<ThreadReconcileRow> listQueuedInputs(@Param("threadId") long threadId);

  @Select(
      "with recursive path as (select id, session_id, parent_entry_id, entry_type, payload, created_at, 0 as depth from harness_entry where session_id = #{sessionId} and id = #{headEntryId} union all select e.id, e.session_id, e.parent_entry_id, e.entry_type, e.payload, e.created_at, p.depth + 1 from harness_entry e join path p on p.parent_entry_id = e.id and p.session_id = e.session_id) select id, session_id as sessionId, parent_entry_id as parentEntryId, entry_type as entryType, payload::text as payloadJson, created_at as createdAt from path order by depth desc")
  List<ThreadReconcileRow> loadPath(
      @Param("sessionId") long sessionId, @Param("headEntryId") long headEntryId);

  @Select(
      "select id, source_head_entry_id as sourceHeadEntryId, execution_epoch as executionEpoch, status from harness_model_invocation where thread_id = #{threadId} and source_head_entry_id = #{headEntryId} and execution_epoch = #{epoch} and status not in ('SUCCEEDED','FAILED','CANCELLED','UNKNOWN') order by id limit 1")
  ThreadReconcileRow findModelBlocker(
      @Param("threadId") long threadId,
      @Param("headEntryId") long headEntryId,
      @Param("epoch") long epoch);

  @Select(
      "select id, execution_epoch as executionEpoch, status from harness_tool_invocation where thread_id = #{threadId} and assistant_entry_id = #{headEntryId} and execution_epoch = #{epoch} and status not in ('SUCCEEDED','FAILED','CANCELLED','UNKNOWN') order by ordinal limit 1")
  ThreadReconcileRow findToolBlocker(
      @Param("threadId") long threadId,
      @Param("headEntryId") long headEntryId,
      @Param("epoch") long epoch);

  @Insert(
      "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload, created_at) values (#{id}, #{sessionId}, #{parentEntryId}, #{entryType}, cast(#{payloadJson} as jsonb), #{now})")
  int insertEntry(
      @Param("id") long id,
      @Param("sessionId") long sessionId,
      @Param("parentEntryId") long parentEntryId,
      @Param("entryType") String entryType,
      @Param("payloadJson") String payloadJson,
      @Param("now") OffsetDateTime now);

  @Update(
      "update harness_thread set head_entry_id = #{headEntryId}, updated_at = greatest(updated_at, #{now}) where id = #{threadId} and head_entry_id = #{expectedHeadEntryId} and execution_epoch = #{epoch} and processor_token = #{token} and processor_until > #{now}")
  int advanceHead(
      @Param("threadId") long threadId,
      @Param("expectedHeadEntryId") long expectedHeadEntryId,
      @Param("headEntryId") long headEntryId,
      @Param("epoch") long epoch,
      @Param("token") String token,
      @Param("now") OffsetDateTime now);

  @Update(
      "update harness_thread_input set status = 'APPLIED', applied_at = #{now} where id = #{id} and thread_id = #{threadId} and sequence = #{sequence} and input_type = #{inputType} and status = 'QUEUED'")
  int applyInput(
      @Param("id") long id,
      @Param("threadId") long threadId,
      @Param("sequence") long sequence,
      @Param("inputType") String inputType,
      @Param("now") OffsetDateTime now);

  @Update(
      "update harness_model_invocation invocation set applied_at = greatest(#{now}, invocation.finished_at) from harness_thread thread where invocation.id = #{id} and invocation.thread_id = #{threadId} and invocation.source_head_entry_id = #{sourceHeadEntryId} and invocation.execution_epoch = #{epoch} and invocation.applied_at is null and invocation.status in ('SUCCEEDED','FAILED','CANCELLED','UNKNOWN') and thread.id = #{threadId} and thread.head_entry_id = #{currentHeadEntryId} and thread.execution_epoch = #{epoch} and thread.processor_token = #{token} and thread.processor_until > #{now}")
  int applyModel(
      @Param("id") long id,
      @Param("threadId") long threadId,
      @Param("sourceHeadEntryId") long sourceHeadEntryId,
      @Param("currentHeadEntryId") long currentHeadEntryId,
      @Param("epoch") long epoch,
      @Param("token") String token,
      @Param("now") OffsetDateTime now);

  @Update(
      "update harness_tool_invocation invocation set applied_at = greatest(#{now}, invocation.finished_at) from harness_thread thread where invocation.thread_id = #{threadId} and invocation.assistant_entry_id = #{assistantEntryId} and invocation.execution_epoch = #{epoch} and invocation.applied_at is null and invocation.status in ('SUCCEEDED','FAILED','CANCELLED','UNKNOWN') and thread.id = #{threadId} and thread.head_entry_id = #{currentHeadEntryId} and thread.execution_epoch = #{epoch} and thread.processor_token = #{token} and thread.processor_until > #{now}")
  int applyTools(
      @Param("threadId") long threadId,
      @Param("assistantEntryId") long assistantEntryId,
      @Param("currentHeadEntryId") long currentHeadEntryId,
      @Param("epoch") long epoch,
      @Param("token") String token,
      @Param("now") OffsetDateTime now);

  @Insert(
      "insert into harness_model_usage (session_id, thread_id, assistant_entry_id, provider_resource_id, model_resource_id, provider_type, provider_model_id, prompt_cache_mode, prompt_cache_retention, cache_eligible, cache_affinity_key, stop_reason, usage_input_tokens, usage_output_tokens, usage_cache_read_tokens, usage_cache_write_tokens, usage_cache_write_long_tokens, usage_reasoning_tokens, usage_provider_total_tokens, pricing_currency, pricing_tier, pricing_service_tier, pricing_service_tier_multiplier, pricing_version, pricing_input_per_million_tokens, pricing_output_per_million_tokens, pricing_cache_read_per_million_tokens, pricing_cache_write_per_million_tokens, pricing_cache_write_long_per_million_tokens, pricing_reasoning_per_million_tokens, request_id, reported_service_tier, raw_usage, created_at) values (#{sessionId}, #{threadId}, #{assistantEntryId}, #{draft.providerResourceId}, #{draft.modelResourceId}, #{draft.providerType}, #{draft.providerModelId}, #{draft.promptCacheMode}, #{draft.promptCacheRetention}, #{draft.cacheEligible}, #{draft.cacheAffinityKey}, #{draft.stopReason}, #{draft.usage.inputTokens}, #{draft.usage.outputTokens}, #{draft.usage.cacheReadTokens}, #{draft.usage.cacheWriteTokens}, #{draft.usage.cacheWriteLongTokens}, #{draft.usage.reasoningTokens}, #{draft.usage.providerTotalTokens}, #{draft.pricing.currency}, #{draft.pricing.pricingTier}, #{draft.pricing.serviceTier}, #{draft.pricing.serviceTierMultiplier}, #{draft.pricing.version}, #{draft.pricing.inputPerMillionTokens}, #{draft.pricing.outputPerMillionTokens}, #{draft.pricing.cacheReadPerMillionTokens}, #{draft.pricing.cacheWritePerMillionTokens}, #{draft.pricing.cacheWriteLongPerMillionTokens}, #{draft.pricing.reasoningPerMillionTokens}, #{draft.requestId}, #{draft.reportedServiceTier}, cast(#{draft.rawUsageJson} as jsonb), #{now})")
  int insertModelUsage(
      @Param("sessionId") long sessionId,
      @Param("threadId") long threadId,
      @Param("assistantEntryId") long assistantEntryId,
      @Param("draft") ModelUsageDraft draft,
      @Param("now") OffsetDateTime now);

  @Insert(
      "insert into harness_model_invocation (id, thread_id, source_head_entry_id, execution_epoch, request, status, attempt, created_at) values (#{id}, #{threadId}, #{sourceHeadEntryId}, #{epoch}, cast(#{requestJson} as jsonb), 'QUEUED', 1, #{now})")
  int insertModelInvocation(
      @Param("id") long id,
      @Param("threadId") long threadId,
      @Param("sourceHeadEntryId") long sourceHeadEntryId,
      @Param("epoch") long epoch,
      @Param("requestJson") String requestJson,
      @Param("now") OffsetDateTime now);

  @Insert(
      "insert into harness_tool_invocation (id, thread_id, session_id, assistant_entry_id, ordinal, tool_call_id, descriptor, arguments, location, environment_name, execution_epoch, status, attempt, created_at) values (#{id}, #{threadId}, #{sessionId}, #{assistantEntryId}, #{ordinal}, #{toolCallId}, cast(#{descriptorJson} as jsonb), cast(#{argumentsJson} as jsonb), #{location}, #{environmentName}, #{epoch}, 'QUEUED', 1, #{now})")
  int insertToolInvocation(
      @Param("id") long id,
      @Param("threadId") long threadId,
      @Param("sessionId") long sessionId,
      @Param("assistantEntryId") long assistantEntryId,
      @Param("ordinal") int ordinal,
      @Param("toolCallId") String toolCallId,
      @Param("descriptorJson") String descriptorJson,
      @Param("argumentsJson") String argumentsJson,
      @Param("location") String location,
      @Param("environmentName") String environmentName,
      @Param("epoch") long epoch,
      @Param("now") OffsetDateTime now);

  @Update(
      "update harness_thread set runnable = #{runnable}, processor_token = null, processor_until = null, updated_at = greatest(updated_at, #{now}) where id = #{threadId} and execution_epoch = #{epoch} and processor_token = #{token} and processor_until > #{now}")
  int release(
      @Param("threadId") long threadId,
      @Param("epoch") long epoch,
      @Param("token") String token,
      @Param("runnable") boolean runnable,
      @Param("now") OffsetDateTime now);

  @Select(
      "select id from harness_thread where runnable and head_entry_id is not null and (processor_token is null or processor_until <= #{now}) order by id limit #{limit}")
  List<Long> listRecoverableThreadIds(@Param("now") OffsetDateTime now, @Param("limit") int limit);
}
