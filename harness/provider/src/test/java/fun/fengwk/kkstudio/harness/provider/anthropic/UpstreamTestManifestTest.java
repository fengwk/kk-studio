package fun.fengwk.kkstudio.harness.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 校验 Anthropic upstream-test-manifest.json 的机器可读性、全量上游 Inventory 覆盖与自包含性。
 *
 * <p>保证 LangChain4j 1.20.0（提交 3a2f4dca6fb447e4d191624b3d588952ed9f4ce9）在 langchain4j-anthropic 模块全部
 * 40 个测试文件、 追踪继承/组合的核心基座方法后共计 423 个真实测试方法、按 invocation 展开共 577 个 invocation 全量可审计：
 *
 * <ul>
 *   <li>源文件、类与方法自洽性：精确枚举 40 个测试源文件与 423 个真实方法全集，拒绝虚构、重复或遗漏。
 *   <li>状态诚实：仅对存在真实本地对等测试方法且已通过的 6 项标为 PORTED 与 PASSED；其余 272 项在范围测试严格保持 PORT_PENDING 与
 *       NOT_EXECUTED_PENDING_IMPLEMENTATION，绝不预写虚假通过，绝不用 SKIPPED 伪充通过。
 *   <li>能力不匹配（mismatch）显式化：全部 299 项 OUT_OF_SCOPE 均详述具体架构不匹配原因，其 targetTest 严格为 null。
 *   <li>真实凭据隔离：121 项依赖真实凭据（@EnabledIfEnvironmentVariable）的集成测试独立标为
 *       NOT_EXECUTED_REQUIRES_CREDENTIAL， 且均映射到确定性离线回放 fixture 目标。
 *   <li>动态断言：所有统计指标均由测试动态遍历计算并断言，不单纯信任 summary 自报。
 * </ul>
 */
class UpstreamTestManifestTest {

  private static final String REQUIRED_UPSTREAM_COMMIT = "3a2f4dca6fb447e4d191624b3d588952ed9f4ce9";
  private static final String REQUIRED_UPSTREAM_VERSION = "1.20.0";

  private static final int EXPECTED_TOTAL_SOURCES = 40;
  private static final int EXPECTED_TOTAL_METHODS = 423;
  private static final int EXPECTED_TOTAL_INVOCATIONS = 577;
  private static final int EXPECTED_PORTED_INVOCATIONS = 6;
  private static final int EXPECTED_IN_SCOPE_PENDING_INVOCATIONS = 272;
  private static final int EXPECTED_OUT_OF_SCOPE_INVOCATIONS = 299;
  private static final int EXPECTED_REAL_CREDENTIAL_INVOCATIONS = 121;

  private static final Set<String> EXPECTED_PORTED_METHOD_NAMES =
      Set.of(
          "should_deserialize_content_with_unknown_type",
          "shouldStreamCreateMessageResponse",
          "shouldIncludeCacheDiagnosticsFromMessageStartEvent",
          "shouldSendCorrectStreamingHttpRequest",
          "shouldIgnoreDoneSentinelAndUnknownEventFrames",
          "shouldHandleInterleavedParallelToolCalls");

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicBatchChatModelIT.java
  private static final Set<String> METHODS_SOURCE_1 =
      Set.of("should_submit_retrieve_and_list_a_batch");

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicBatchChatModelTest.java
  private static final Set<String> METHODS_SOURCE_2 =
      concatSets(
          Set.of(
              "submit_assigns_ordered_custom_ids_and_returns_running_state",
              "retrieve_reorders_results_to_submission_order_and_maps_success_and_error",
              "cancel_calls_the_cancel_endpoint",
              "list_maps_pagination_cursor_from_has_more_and_last_id",
              "retrieve_while_in_progress_returns_running_and_does_not_fetch_results",
              "retrieve_maps_canceled_result_without_error_to_failure_with_type_as_message",
              "list_with_null_pagination_sends_no_query_params_and_returns_batches",
              "retrieve_maps_ended_batch_with_cancel_initiated_at_to_cancelled",
              "retrieve_does_not_return_thinking_by_default",
              "retrieve_returns_thinking_when_return_thinking_is_enabled"),
          Set.of(
              "submit_applies_anthropic_specific_default_request_parameters",
              "submit_fails_when_model_name_is_not_set"));

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicCacheDiagnosticsIT.java
  private static final Set<String> METHODS_SOURCE_3 =
      Set.of("should_return_null_diagnostics_on_first_turn_and_model_changed_on_second_turn");

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicCachingProxyIT.java
  private static final Set<String> METHODS_SOURCE_4 =
      Set.of("should_cache_sync_chat_responses", "should_cache_streaming_chat_responses");

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicChatModelAsyncRetryTest.java
  private static final Set<String> METHODS_SOURCE_5 =
      Set.of(
          "chatAsync_retries_a_retriable_failure_and_then_succeeds",
          "chatAsync_does_not_retry_a_non_retriable_failure");

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicChatModelErrorsTest.java
  private static final Set<String> METHODS_SOURCE_6 =
      Set.of("should_handle_error_responses", "should_handle_timeout");

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicChatModelIT.java
  private static final Set<String> METHODS_SOURCE_7 =
      concatSets(
          Set.of(
              "should_accept_base64_pdf",
              "should_respect_stop_sequences",
              "should_cache_system_message",
              "should_cache_multiple_system_messages",
              "should_fail_if_more_than_four_system_message_with_cache",
              "all_parameters",
              "should_support_all_enum_model_names",
              "should_support_all_string_model_names",
              "should_fail_to_create_without_api_key",
              "should_execute_one_specific_tool_and_ignore_another_tool_with_parallel_tool_disabled"),
          Set.of(
              "should_force_execution_without_tools_when_pass_tool_choice_none",
              "should_execute_one_tool_and_ignore_another_tool_with_parallel_tool_disabled",
              "should_cache_system_message_and_tools",
              "should_cache_tools",
              "should_allow_non_user_message_as_first_message_and_consecutive_user_messages",
              "should_set_custom_parameters_and_get_raw_response",
              "should_support_code_execution_tool",
              "should_support_skills",
              "should_support_web_search_tool",
              "should_support_tool_search_tool"),
          Set.of(
              "should_send_strict_true_in_tools_definition",
              "should_return_server_tool_results_in_attributes_when_enabled",
              "should_apply_mid_conversation_system_message"));

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicChatModelListenerIT.java
  private static final Set<String> METHODS_SOURCE_8 =
      Set.of("should_listen_request_and_response", "should_listen_error");

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicChatModelNonBlockingIT.java
  private static final Set<String> METHODS_SOURCE_9 =
      Set.of(
          "chatAsync_does_not_block_the_http_worker_threads",
          "streaming_publisher_does_not_block_the_http_worker_threads",
          "blockHound_detects_blocking_on_a_policed_thread");

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicChatModelTest.java
  private static final Set<String> METHODS_SOURCE_10 =
      Set.of(
          "should_use_model_defaults_when_request_parameters_absent",
          "should_override_model_settings_with_request_parameters",
          "should_handle_non_anthropic_parameters_gracefully");

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicChatModelThinkingIT.java
  private static final Set<String> METHODS_SOURCE_11 =
      Set.of(
          "should_return_and_send_thinking",
          "should_return_and_NOT_send_thinking",
          "should_return_and_send_thinking_with_tools",
          "should_NOT_return_thinking");

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicChatRequestCacheParametersTest.java
  private static final Set<String> METHODS_SOURCE_12 =
      concatSets(
          Set.of(
              "should_enable_system_message_caching_per_request_when_model_default_is_disabled",
              "should_disable_system_message_caching_per_request_when_model_default_is_enabled",
              "should_enable_tool_caching_per_request_when_model_default_is_disabled",
              "should_disable_tool_caching_per_request_when_model_default_is_enabled",
              "should_fall_back_to_model_default_when_request_does_not_specify_caching",
              "should_override_thinking_parameters_per_request",
              "should_override_tool_choice_name_and_parallel_tool_use_per_request",
              "should_send_disable_parallel_tool_use_when_tool_choice_is_not_set",
              "should_not_send_tool_choice_when_no_tools_are_present",
              "should_send_tool_choice_name_when_tool_choice_is_not_set"),
          Set.of(
              "should_send_tool_choice_name_together_with_disable_parallel_tool_use_when_tool_choice_is_not_set",
              "should_not_send_tool_choice_name_when_no_tools_are_present",
              "should_override_user_id_per_request",
              "should_not_send_diagnostics_when_not_requested",
              "should_opt_in_to_cache_diagnostics_with_null_previous_message_id_on_first_turn",
              "should_send_previous_message_id_on_subsequent_turn",
              "should_clear_previous_message_id_per_request_even_when_model_default_is_set",
              "should_not_pair_unrelated_default_previous_message_id_with_model_level_diagnostics_toggle",
              "should_send_per_request_previous_message_id_when_diagnostics_enabled_at_model_level"));

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicChatRequestParametersTest.java
  private static final Set<String> METHODS_SOURCE_13 =
      Set.of(
          "should_build_with_anthropic_specific_parameters",
          "should_default_anthropic_specific_parameters_to_null",
          "overrideWith_should_override_anthropic_specific_parameters",
          "overrideWith_should_keep_anthropic_specific_parameters_when_overriding_with_common_parameters",
          "equals_and_hashCode",
          "defaultedBy_should_apply_defaults_to_anthropic_specific_parameters",
          "defaultedBy_should_keep_parameters_when_defaulted_by_common_parameters",
          "toBuilder_should_populate_all_fields",
          "mid_conversation_system_messages_is_plumbed_through_builder_override_defaultedBy_and_copy");

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicCustomHeadersTest.java
  private static final Set<String> METHODS_SOURCE_14 =
      Set.of(
          "should_send_custom_headers_with_chat_model",
          "should_send_custom_headers_via_supplier_with_chat_model",
          "should_not_override_standard_headers",
          "should_send_custom_headers_with_streaming_chat_model");

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicMapperTest.java
  private static final Set<String> METHODS_SOURCE_15 =
      concatSets(
          Set.of(
              "test_toAnthropicMessages",
              "test_toAnthropicTool",
              "test_toAnthropicSchema_with_objects",
              "test_toAnthropicSchema_with_definitions",
              "test_toAnthropicTool_with_definitions",
              "test_toAnthropicTool_without_definitions_omits_defs",
              "test_toAnthropicSchema_with_optional_fields",
              "per_tool_strict_true_should_override_model_level_null",
              "per_tool_strict_false_should_override_model_level_true",
              "per_tool_strict_null_should_fall_back_to_model_level"),
          Set.of(
              "should_retain_keys",
              "should_extract_server_tool_results_when_enabled",
              "should_not_extract_server_tool_results_when_disabled",
              "should_map_user_message_with_cache_control_metadata",
              "should_only_apply_cache_control_to_last_item_when_multiple_items_present",
              "should_map_ai_message_text_with_cache_control_metadata",
              "should_apply_cache_control_to_last_content_block_of_ai_message_with_tool_execution_requests",
              "should_not_apply_cache_control_to_ai_message_without_cache_control_attribute",
              "should_map_tool_execution_result_message_with_single_text_and_cache_control_metadata",
              "should_map_tool_execution_result_message_with_multiple_content_blocks_and_cache_control_metadata"),
          Set.of(
              "should_not_apply_cache_control_to_tool_execution_result_message_without_cache_control_attribute",
              "mid_conversation_system_messages_disabled_sends_all_system_messages_via_top_level_system_prompt",
              "mid_conversation_system_messages_enabled_keeps_only_leading_system_messages_in_top_level_system_prompt",
              "mid_conversation_system_messages_enabled_inlines_system_message_after_pending_tool_result",
              "should_map_null_diagnostics_to_null",
              "should_map_diagnostics_with_no_cache_miss_reason_to_null_reason_type",
              "should_map_diagnostics_with_cache_miss_reason",
              "should_apply_cache_control_to_last_content_block_of_user_message_ending_with_image",
              "should_apply_cache_control_to_last_content_block_of_user_message_ending_with_pdf",
              "should_not_apply_cache_control_to_image_content_when_not_last_item"),
          Set.of(
              "should_apply_cache_control_to_base64_image_content",
              "should_apply_cache_control_to_base64_pdf_content",
              "should_not_apply_cache_control_to_image_content_without_cache_control_attribute",
              "should_not_apply_cache_control_to_pdf_content_without_cache_control_attribute",
              "should_serialize_image_content_with_cache_control_next_to_source",
              "should_serialize_pdf_content_with_cache_control_next_to_source"));

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicMidConversationSystemMessagesTest.java
  private static final Set<String> METHODS_SOURCE_16 =
      Set.of(
          "should_send_mid_conversation_system_message_inline_when_enabled",
          "should_not_mid_conversation_system_messages_by_default",
          "should_mid_conversation_system_messages_per_request_when_model_default_is_disabled");

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicSkillsTest.java
  private static final Set<String> METHODS_SOURCE_17 =
      Set.of(
          "skills_shouldAddContainerWithAnthropicManagedSkills",
          "skills_shouldAutoAddCodeExecutionServerTool",
          "skills_shouldNotDuplicateExplicitlyConfiguredCodeExecutionTool",
          "skills_shouldStillAddServerToolWhenRegularToolIsNamedCodeExecution",
          "skills_shouldDeduplicate",
          "skills_shouldIgnoreNullEntries",
          "noSkills_shouldNotAddContainerOrCodeExecutionTool",
          "skills_shouldSerializeReferenceRequestShape");

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicStreamingChatModelIT.java
  private static final Set<String> METHODS_SOURCE_18 =
      concatSets(
          Set.of(
              "should_support_all_enum_model_names",
              "all_parameters",
              "should_support_output_config_effort_via_custom_parameters",
              "should_cache_system_message",
              "should_cache_tools",
              "should_fail_to_create_without_api_key",
              "should_handle_timeout",
              "should_work_with_userId",
              "should_set_custom_parameters_and_get_raw_response",
              "should_send_strict_true_in_tools_definition_streaming"),
          Set.of("should_return_server_tool_results_in_attributes_when_enabled_streaming"));

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicStreamingChatModelListenerIT.java
  private static final Set<String> METHODS_SOURCE_19 =
      Set.of("should_listen_request_and_response", "should_listen_error");

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicStreamingChatModelPublisherCancellationTest.java
  private static final Set<String> METHODS_SOURCE_20 =
      Set.of("cancelling_subscription_mid_stream_aborts_the_upstream_stream");

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicStreamingChatModelPublisherTckTest.java
  private static final Set<String> METHODS_SOURCE_21 =
      concatSets(
          Set.of(
              "optional_spec104_mustSignalOnErrorWhenFails",
              "optional_spec105_emptyStreamMustTerminateBySignallingOnComplete",
              "optional_spec111_maySupportMultiSubscribe",
              "optional_spec111_multicast_mustProduceTheSameElementsInTheSameSequenceToAllOfItsSubscribersWhenRequestingManyUpfront",
              "optional_spec111_multicast_mustProduceTheSameElementsInTheSameSequenceToAllOfItsSubscribersWhenRequestingManyUpfrontAndCompleteAsExpected",
              "optional_spec111_multicast_mustProduceTheSameElementsInTheSameSequenceToAllOfItsSubscribersWhenRequestingOneByOne",
              "optional_spec111_registeredSubscribersMustReceiveOnNextOrOnCompleteSignals",
              "optional_spec309_requestNegativeNumberMaySignalIllegalArgumentExceptionWithSpecificMessage",
              "required_createPublisher1MustProduceAStreamOfExactly1Element",
              "required_createPublisher3MustProduceAStreamOfExactly3Elements"),
          Set.of(
              "required_spec101_subscriptionRequestMustResultInTheCorrectNumberOfProducedElements",
              "required_spec102_maySignalLessThanRequestedAndTerminateSubscription",
              "required_spec105_mustSignalOnCompleteWhenFiniteStreamTerminates",
              "required_spec107_mustNotEmitFurtherSignalsOnceOnCompleteHasBeenSignalled",
              "required_spec109_mayRejectCallsToSubscribeIfPublisherIsUnableOrUnwillingToServeThemRejectionMustTriggerOnErrorAfterOnSubscribe",
              "required_spec109_mustIssueOnSubscribeForNonNullSubscriber",
              "required_spec109_subscribeThrowNPEOnNullSubscriber",
              "required_spec302_mustAllowSynchronousRequestCallsFromOnNextAndOnSubscribe",
              "required_spec303_mustNotAllowUnboundedRecursion",
              "required_spec306_afterSubscriptionIsCancelledRequestMustBeNops"),
          Set.of(
              "required_spec307_afterSubscriptionIsCancelledAdditionalCancelationsMustBeNops",
              "required_spec309_requestNegativeNumberMustSignalIllegalArgumentException",
              "required_spec309_requestZeroMustSignalIllegalArgumentException",
              "required_spec312_cancelMustMakeThePublisherToEventuallyStopSignaling",
              "required_spec313_cancelMustMakeThePublisherEventuallyDropAllReferencesToTheSubscriber",
              "required_spec317_mustNotSignalOnErrorWhenPendingAboveLongMaxValue",
              "required_spec317_mustSupportACumulativePendingElementCountUpToLongMaxValue",
              "required_spec317_mustSupportAPendingElementCountUpToLongMaxValue",
              "required_validate_boundedDepthOfOnNextAndRequestRecursion",
              "required_validate_maxElementsFromPublisher"),
          Set.of(
              "stochastic_spec103_mustSignalOnMethodsSequentially",
              "untested_spec106_mustConsiderSubscriptionCancelledAfterOnErrorOrOnCompleteHasBeenCalled",
              "untested_spec107_mustNotEmitFurtherSignalsOnceOnErrorHasBeenSignalled",
              "untested_spec108_possiblyCanceledSubscriptionShouldNotReceiveOnErrorOrOnCompleteSignals",
              "untested_spec109_subscribeShouldNotThrowNonFatalThrowable",
              "untested_spec110_rejectASubscriptionRequestIfTheSameSubscriberSubscribesTwice",
              "untested_spec304_requestShouldNotPerformHeavyComputations",
              "untested_spec305_cancelMustNotSynchronouslyPerformHeavyComputation"));

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicStreamingChatModelPublisherTest.java
  private static final Set<String> METHODS_SOURCE_22 =
      Set.of("should_stream_events_through_reactive_publisher");

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicStreamingChatModelThinkingIT.java
  private static final Set<String> METHODS_SOURCE_23 =
      Set.of(
          "should_return_and_send_thinking",
          "should_return_and_NOT_send_thinking",
          "should_return_and_send_thinking_with_tools",
          "should_NOT_return_thinking");

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicTokenCountEstimatorIT.java
  private static final Set<String> METHODS_SOURCE_24 =
      Set.of(
          "should_estimate_token_count_in_text",
          "should_estimate_token_count_in_message",
          "should_estimate_token_count_in_messages",
          "should_estimate_token_count_in_messages_with_system_prompt",
          "should_estimate_token_count_when_only_system_message_provided_and_dummy_user_inserted",
          "should_fail_when_only_system_message_is_provided");

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicTokenUsageTest.java
  private static final Set<String> METHODS_SOURCE_25 =
      Set.of(
          "should_be_equal_when_all_fields_match",
          "should_not_be_equal_when_cache_creation_input_tokens_differ",
          "should_not_be_equal_when_cache_read_input_tokens_differ",
          "should_not_be_equal_when_parent_fields_differ",
          "should_be_equal_when_both_have_null_cache_fields",
          "should_not_be_equal_when_only_one_has_null_cache_fields",
          "should_not_be_equal_to_parent_token_usage",
          "should_be_reflexively_equal_and_not_equal_to_null");

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicUserIdIT.java
  private static final Set<String> METHODS_SOURCE_26 =
      Set.of(
          "should_include_userId_in_chat_model_request",
          "should_include_userId_in_streaming_chat_model_request",
          "should_not_include_metadata_when_userId_is_null",
          "should_not_include_metadata_when_userId_is_empty");

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/InternalAnthropicHelperTest.java
  private static final Set<String> METHODS_SOURCE_27 =
      Set.of(
          "validate_WithNoUnsupportedFeatures_ShouldNotThrowException",
          "validate_WithSchemalessJsonResponseFormat_ShouldThrowException",
          "validate_WithFrequencyPenalty_ShouldThrowException",
          "validate_WithPresencePenalty_ShouldThrowException",
          "validate_WithTwoUnsupportedFeatures_ShouldThrowExceptionWithCombinedMessage");

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/common/AnthropicAiServiceIT.java
  private static final Set<String> METHODS_SOURCE_28 =
      Set.of("should_answer_simple_question", "should_execute_tool_then_return_structured_output");

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/common/AnthropicAiServiceWithJsonSchemaIT.java
  private static final Set<String> METHODS_SOURCE_29 =
      concatSets(
          Set.of(
              "should_extract_pojo_with_primitives",
              "should_extract_pojo_with_missing_data",
              "should_extract_pojo_with_nested_pojo",
              "should_extract_pojo_with_enum",
              "should_extract_pojo_with_array_of_primitives",
              "should_extract_pojo_with_list_of_primitives",
              "should_extract_pojo_with_set_of_primitives",
              "should_extract_pojo_with_array_of_pojos",
              "should_extract_pojo_with_list_of_pojos",
              "should_extract_pojo_with_set_of_pojos"),
          Set.of(
              "should_extract_pojo_with_array_of_enums",
              "should_extract_pojo_with_list_of_enums",
              "should_extract_pojo_with_set_of_enums",
              "should_extract_pojo_with_local_date_time_fields",
              "should_return_result_with_pojo",
              "should_extract_pojo_with_recursion",
              "should_extract_pojo_with_uuid",
              "should_extract_boolean_primitive",
              "should_extract_boolean_boxed",
              "should_extract_int_primitive"),
          Set.of(
              "should_extract_int_boxed",
              "should_extract_long_primitive",
              "should_extract_long_boxed",
              "should_extract_float_primitive",
              "should_extract_float_boxed",
              "should_extract_double_primitive",
              "should_extract_double_boxed",
              "should_extract_list_of_pojo",
              "should_return_result_with_list_of_pojo",
              "should_extract_list_of_strings"),
          Set.of(
              "should_extract_set_of_strings",
              "should_extract_set_of_pojo",
              "should_extract_enum",
              "should_extract_list_of_enums",
              "should_extract_set_of_enums",
              "should_extract_polymorphic_type",
              "should_extract_list_of_polymorphic_types",
              "should_extract_pojo_with_nested_polymorphic_field",
              "should_extract_recursive_polymorphic_type"));

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/common/AnthropicAiServiceWithToolsIT.java
  private static final Set<String> METHODS_SOURCE_30 =
      concatSets(
          Set.of(
              "should_support_tool_search_tool",
              "should_support_tool_use_examples",
              "should_execute_tool_with_primitive_parameters",
              "should_execute_tool_with_pojo_with_primitives",
              "should_execute_tool_with_pojo_with_nested_pojo",
              "should_execute_tool_with_pojo_with_recursion",
              "should_execute_tool_without_parameters",
              "should_execute_tool_with_enum_parameter",
              "should_execute_tool_with_map_parameter",
              "should_execute_tool_with_list_of_strings_parameter"),
          Set.of(
              "should_execute_tool_with_set_of_enums_parameter",
              "should_execute_tool_with_collection_of_integers_parameter",
              "should_execute_tool_with_list_of_POJOs_parameter",
              "should_execute_tool_with_uuid_parameter",
              "should_execute_normal_tool_with_primitive_parameters",
              "should_execute_immediate_tool_with_primitive_parameters",
              "should_execute_normal_tool_in_parallel_with_primitive_parameters",
              "should_execute_immediate_tool_in_parallel_with_primitive_parameters",
              "should_return_to_LLM",
              "should_return_immediately_from_first_tool_when_not_called_in_parallel"),
          Set.of(
              "should_keep_memory_consistent_using_return_immediate",
              "should_throw_using_immediate_tool_on_service_not_returning_Result",
              "should_allow_empty_tool_result",
              "should_allow_blank_tool_result",
              "should_execute_tool_returning_Image",
              "should_execute_tool_returning_ImageContent",
              "should_execute_tool_returning_ContentList",
              "should_fail_when_tool_returns_image_and_provider_does_not_support_it"));

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/common/AnthropicChatModelIT.java
  private static final Set<String> METHODS_SOURCE_31 =
      concatSets(
          Set.of(
              "should_execute_a_tool_then_answer_respecting_JSON_response_format_with_schema",
              "should_respect_JsonRawSchema_responseFormat",
              "should_respect_user_message",
              "should_respect_system_message",
              "should_respect_multiple_messages",
              "should_respect_modelName_in_chat_request",
              "should_respect_modelName_in_default_model_parameters",
              "should_fail_if_modelName_is_not_supported",
              "should_respect_maxOutputTokens_in_chat_request",
              "should_respect_maxOutputTokens_in_default_model_parameters"),
          Set.of(
              "should_fail_if_maxOutputTokens_parameter_is_not_supported",
              "should_respect_stopSequences_in_chat_request",
              "should_respect_stopSequences_in_default_model_parameters",
              "should_fail_if_stopSequences_parameter_is_not_supported",
              "should_respect_common_parameters_wrapped_in_integration_specific_class_in_chat_request",
              "should_respect_common_parameters_wrapped_in_integration_specific_class_in_default_model_parameters",
              "should_execute_a_tool_then_answer",
              "should_execute_a_tool_without_arguments_then_answer",
              "should_execute_multiple_tools_in_parallel_then_answer",
              "should_fail_if_tools_are_not_supported"),
          Set.of(
              "should_force_LLM_to_execute_any_tool",
              "should_force_LLM_to_execute_specific_tool",
              "should_fail_if_tool_choice_REQUIRED_is_not_supported",
              "should_respect_JSON_response_format",
              "should_fail_if_JSON_response_format_is_not_supported",
              "should_respect_JSON_response_format_with_schema",
              "should_fail_if_JSON_response_format_with_schema_is_not_supported",
              "should_accept_single_image_as_base64_encoded_string",
              "should_accept_multiple_images_as_base64_encoded_strings",
              "should_fail_if_images_as_base64_encoded_strings_are_not_supported"),
          Set.of(
              "should_accept_single_image_as_public_URL",
              "should_accept_multiple_images_as_public_URLs",
              "should_fail_if_images_as_public_URLs_are_not_supported",
              "should_chat_asynchronously"));

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/common/AnthropicModelCatalogIT.java
  private static final Set<String> METHODS_SOURCE_32 =
      Set.of(
          "should_have_creation_timestamp",
          "should_have_max_input_tokens",
          "should_have_max_output_tokens",
          "should_discover_models",
          "should_return_correct_provider");

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/common/AnthropicStreamingAiServiceIT.java
  private static final Set<String> METHODS_SOURCE_33 =
      Set.of(
          "should_answer_simple_question",
          "should_execute_tool_without_arguments",
          "should_keep_memory_consistent_when_streaming_using_immediate_tool");

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/common/AnthropicStreamingChatModelIT.java
  private static final Set<String> METHODS_SOURCE_34 =
      concatSets(
          Set.of(
              "should_execute_a_tool_then_answer_respecting_JSON_response_format_with_schema",
              "should_respect_JsonRawSchema_responseFormat",
              "should_respect_user_message",
              "should_respect_system_message",
              "should_respect_multiple_messages",
              "should_respect_modelName_in_chat_request",
              "should_respect_modelName_in_default_model_parameters",
              "should_fail_if_modelName_is_not_supported",
              "should_respect_maxOutputTokens_in_chat_request",
              "should_respect_maxOutputTokens_in_default_model_parameters"),
          Set.of(
              "should_fail_if_maxOutputTokens_parameter_is_not_supported",
              "should_respect_stopSequences_in_chat_request",
              "should_respect_stopSequences_in_default_model_parameters",
              "should_fail_if_stopSequences_parameter_is_not_supported",
              "should_respect_common_parameters_wrapped_in_integration_specific_class_in_chat_request",
              "should_respect_common_parameters_wrapped_in_integration_specific_class_in_default_model_parameters",
              "should_execute_a_tool_then_answer",
              "should_execute_a_tool_without_arguments_then_answer",
              "should_execute_multiple_tools_in_parallel_then_answer",
              "should_fail_if_tools_are_not_supported"),
          Set.of(
              "should_force_LLM_to_execute_any_tool",
              "should_force_LLM_to_execute_specific_tool",
              "should_fail_if_tool_choice_REQUIRED_is_not_supported",
              "should_respect_JSON_response_format",
              "should_fail_if_JSON_response_format_is_not_supported",
              "should_respect_JSON_response_format_with_schema",
              "should_fail_if_JSON_response_format_with_schema_is_not_supported",
              "should_accept_single_image_as_base64_encoded_string",
              "should_accept_multiple_images_as_base64_encoded_strings",
              "should_fail_if_images_as_base64_encoded_strings_are_not_supported"),
          Set.of(
              "should_accept_single_image_as_public_URL",
              "should_accept_multiple_images_as_public_URLs",
              "should_fail_if_images_as_public_URLs_are_not_supported",
              "should_cancel_streaming",
              "should_propagate_user_exceptions_thrown_from_onPartialResponse",
              "should_propagate_user_exceptions_thrown_from_onCompleteResponse",
              "should_ignore_user_exceptions_thrown_from_onError"));

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/common/AnthropicStreamingChatModelPublisherListenerTest.java
  private static final Set<String> METHODS_SOURCE_35 =
      Set.of(
          "listener_is_invoked_with_request_then_response_on_success",
          "listener_is_invoked_with_request_then_error_on_failure",
          "listener_gets_only_onRequest_when_subscription_is_cancelled_mid_stream",
          "listener_gets_only_onRequest_when_subscriber_throws_from_onNext");

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/internal/api/AnthropicContentTest.java
  private static final Set<String> METHODS_SOURCE_36 =
      Set.of("should_deserialize_content_with_unknown_type");

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/internal/api/AnthropicMetadataTest.java
  private static final Set<String> METHODS_SOURCE_37 =
      Set.of(
          "should_create_metadata_with_userId",
          "should_create_metadata_using_builder",
          "should_serialize_to_json_with_snake_case",
          "should_deserialize_from_json_with_snake_case",
          "should_not_serialize_null_userId",
          "should_handle_equals_and_hashCode",
          "should_have_meaningful_toString",
          "should_create_builder_from_existing_metadata");

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/internal/api/AnthropicPdfContentSourceTest.java
  private static final Set<String> METHODS_SOURCE_38 =
      concatSets(
          Set.of(
              "should_create_from_base64",
              "should_create_from_url",
              "should_create_with_three_parameter_constructor",
              "should_have_correct_equals_for_url_sources",
              "should_have_correct_equals_for_base64_sources",
              "should_have_correct_equals_for_different_types",
              "should_have_correct_hashCode",
              "should_have_toString",
              "should_not_equal_null",
              "should_not_equal_different_class"),
          Set.of("should_equal_itself"));

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/internal/api/AnthropicPdfContentTest.java
  private static final Set<String> METHODS_SOURCE_39 =
      concatSets(
          Set.of(
              "should_create_from_base64",
              "should_create_from_url",
              "should_create_with_source_constructor",
              "should_maintain_backward_compatibility_with_two_parameter_constructor",
              "should_maintain_backward_compatibility_with_single_parameter_constructor",
              "should_have_correct_equals_for_url_content",
              "should_have_correct_equals_for_base64_content",
              "should_have_correct_equals_for_different_types",
              "should_have_correct_hashCode",
              "should_have_toString"),
          Set.of(
              "should_not_equal_null", "should_not_equal_different_class", "should_equal_itself"));

  // langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/internal/client/DefaultAnthropicClientTest.java
  private static final Set<String> METHODS_SOURCE_40 =
      concatSets(
          Set.of(
              "shouldThrowWhenApiKeyIsMissing",
              "shouldThrowWhenBaseUrlIsMissing",
              "shouldThrowWhenVersionIsMissing",
              "shouldSendCreateMessageRequest",
              "shouldSendCorrectHttpRequest",
              "shouldFlattenCustomParametersWithoutSerializingCustomParametersField",
              "shouldIncludeBetaHeaderWhenSet",
              "shouldOmitDiagnosticsFieldWhenNotSet",
              "shouldSendDiagnosticsWithNullPreviousMessageIdOnFirstTurn",
              "shouldSendDiagnosticsWithPreviousMessageIdOnSubsequentTurn"),
          Set.of(
              "shouldParseCacheMissReasonFromResponse",
              "shouldReturnRawResponseWithCreateMessageWithRawResponse",
              "shouldSendCountTokensRequest",
              "shouldSendCorrectCountTokensHttpRequest",
              "shouldSendListModelsRequest",
              "shouldSendCorrectListModelsHttpRequest",
              "shouldStreamCreateMessageResponse",
              "shouldForwardOnlyRawEventsNotExposedViaTypedCallbacks",
              "shouldIncludeCacheDiagnosticsFromMessageStartEvent",
              "shouldSendCorrectStreamingHttpRequest"),
          Set.of(
              "shouldHandleStreamingError",
              "shouldIgnoreDoneSentinelAndUnknownEventFrames",
              "shouldHandleInterleavedParallelToolCalls",
              "shouldUseCustomTimeout"));

  private static final Map<String, Set<String>> SOURCE_TO_METHODS = buildSourceToMethods();

  private static Map<String, Set<String>> buildSourceToMethods() {
    Map<String, Set<String>> map = new HashMap<>();
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicBatchChatModelIT.java",
        METHODS_SOURCE_1);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicBatchChatModelTest.java",
        METHODS_SOURCE_2);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicCacheDiagnosticsIT.java",
        METHODS_SOURCE_3);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicCachingProxyIT.java",
        METHODS_SOURCE_4);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicChatModelAsyncRetryTest.java",
        METHODS_SOURCE_5);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicChatModelErrorsTest.java",
        METHODS_SOURCE_6);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicChatModelIT.java",
        METHODS_SOURCE_7);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicChatModelListenerIT.java",
        METHODS_SOURCE_8);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicChatModelNonBlockingIT.java",
        METHODS_SOURCE_9);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicChatModelTest.java",
        METHODS_SOURCE_10);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicChatModelThinkingIT.java",
        METHODS_SOURCE_11);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicChatRequestCacheParametersTest.java",
        METHODS_SOURCE_12);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicChatRequestParametersTest.java",
        METHODS_SOURCE_13);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicCustomHeadersTest.java",
        METHODS_SOURCE_14);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicMapperTest.java",
        METHODS_SOURCE_15);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicMidConversationSystemMessagesTest.java",
        METHODS_SOURCE_16);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicSkillsTest.java",
        METHODS_SOURCE_17);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicStreamingChatModelIT.java",
        METHODS_SOURCE_18);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicStreamingChatModelListenerIT.java",
        METHODS_SOURCE_19);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicStreamingChatModelPublisherCancellationTest.java",
        METHODS_SOURCE_20);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicStreamingChatModelPublisherTckTest.java",
        METHODS_SOURCE_21);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicStreamingChatModelPublisherTest.java",
        METHODS_SOURCE_22);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicStreamingChatModelThinkingIT.java",
        METHODS_SOURCE_23);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicTokenCountEstimatorIT.java",
        METHODS_SOURCE_24);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicTokenUsageTest.java",
        METHODS_SOURCE_25);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/AnthropicUserIdIT.java",
        METHODS_SOURCE_26);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/InternalAnthropicHelperTest.java",
        METHODS_SOURCE_27);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/common/AnthropicAiServiceIT.java",
        METHODS_SOURCE_28);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/common/AnthropicAiServiceWithJsonSchemaIT.java",
        METHODS_SOURCE_29);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/common/AnthropicAiServiceWithToolsIT.java",
        METHODS_SOURCE_30);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/common/AnthropicChatModelIT.java",
        METHODS_SOURCE_31);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/common/AnthropicModelCatalogIT.java",
        METHODS_SOURCE_32);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/common/AnthropicStreamingAiServiceIT.java",
        METHODS_SOURCE_33);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/common/AnthropicStreamingChatModelIT.java",
        METHODS_SOURCE_34);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/common/AnthropicStreamingChatModelPublisherListenerTest.java",
        METHODS_SOURCE_35);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/internal/api/AnthropicContentTest.java",
        METHODS_SOURCE_36);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/internal/api/AnthropicMetadataTest.java",
        METHODS_SOURCE_37);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/internal/api/AnthropicPdfContentSourceTest.java",
        METHODS_SOURCE_38);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/internal/api/AnthropicPdfContentTest.java",
        METHODS_SOURCE_39);
    map.put(
        "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/internal/client/DefaultAnthropicClientTest.java",
        METHODS_SOURCE_40);
    return Map.copyOf(map);
  }

  @SafeVarargs
  private static Set<String> concatSets(Set<String>... sets) {
    Set<String> result = new HashSet<>();
    for (Set<String> s : sets) {
      result.addAll(s);
    }
    return Set.copyOf(result);
  }

  @Test
  void upstreamTestManifestIsCompleteAndSelfContained() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode root;
    try (InputStream in = getClass().getResourceAsStream("upstream-test-manifest.json")) {
      assertNotNull(in, "upstream-test-manifest.json must exist in test resources");
      root = mapper.readTree(in);
    }

    String commit = root.get("upstreamCommit").asText();
    assertEquals(
        REQUIRED_UPSTREAM_COMMIT,
        commit,
        "upstream commit must match required LangChain4j 1.20.0 release commit");

    String version = root.get("upstreamVersion").asText();
    assertEquals(
        REQUIRED_UPSTREAM_VERSION,
        version,
        "upstream version must match required LangChain4j 1.20.0 release version");

    assertNotNull(root.get("license"), "license must exist");
    assertEquals("Apache-2.0", root.get("license").asText());
    assertNotNull(root.get("notes"), "notes must exist");
    assertNotNull(root.get("summary"), "summary must exist");

    JsonNode summary = root.get("summary");
    assertEquals(EXPECTED_TOTAL_SOURCES, summary.get("totalSourceFiles").asInt());
    assertEquals(EXPECTED_TOTAL_METHODS, summary.get("totalMethods").asInt());
    assertEquals(EXPECTED_TOTAL_INVOCATIONS, summary.get("totalInvocations").asInt());
    assertEquals(EXPECTED_PORTED_INVOCATIONS, summary.get("portedInvocations").asInt());
    assertEquals(
        EXPECTED_IN_SCOPE_PENDING_INVOCATIONS, summary.get("inScopePendingInvocations").asInt());
    assertEquals(EXPECTED_OUT_OF_SCOPE_INVOCATIONS, summary.get("outOfScopeInvocations").asInt());
    assertEquals(
        EXPECTED_REAL_CREDENTIAL_INVOCATIONS, summary.get("realCredentialInvocations").asInt());

    JsonNode methods = root.get("methods");
    assertTrue(methods.isArray(), "methods must be a JSON array");

    Set<String> seenSources = new HashSet<>();
    Set<String> seenSourceMethodKeys = new HashSet<>();
    Set<String> seenInvocationIds = new HashSet<>();
    Map<String, Set<String>> seenMethodsPerSource = new HashMap<>();

    int countedMethods = 0;
    int countedInvocations = 0;
    int countedPortedInvocations = 0;
    int countedInScopePendingInvocations = 0;
    int countedOutOfScopeInvocations = 0;
    int countedRealCredentialInvocations = 0;

    for (JsonNode methodNode : methods) {
      countedMethods++;
      String sourcePath = methodNode.get("sourcePath").asText();
      String className = methodNode.get("className").asText();
      String methodName = methodNode.get("methodName").asText();

      assertTrue(SOURCE_TO_METHODS.containsKey(sourcePath), "unexpected sourcePath: " + sourcePath);
      seenSources.add(sourcePath);

      Set<String> expectedMethods = SOURCE_TO_METHODS.get(sourcePath);
      assertTrue(
          expectedMethods.contains(methodName),
          () ->
              "upstreamMethod '"
                  + methodName
                  + "' does not exist in expected source: "
                  + sourcePath);

      String methodKey = sourcePath + "#" + className + "#" + methodName;
      assertTrue(seenSourceMethodKeys.add(methodKey), "duplicate method key: " + methodKey);
      seenMethodsPerSource.computeIfAbsent(sourcePath, k -> new HashSet<>()).add(methodName);

      JsonNode invocations = methodNode.get("invocations");
      assertTrue(
          invocations.isArray() && invocations.size() > 0, "invocations must be non-empty array");

      Set<String> seenParamCasesInMethod = new HashSet<>();
      for (JsonNode inv : invocations) {
        countedInvocations++;
        String invocationId = inv.get("invocationId").asText();
        assertNotNull(invocationId, "invocationId must not be null");
        assertFalse(invocationId.isBlank(), "invocationId must not be blank");
        assertTrue(seenInvocationIds.add(invocationId), "duplicate invocationId: " + invocationId);

        assertEquals(
            sourcePath, inv.get("sourcePath").asText(), "sourcePath mismatch in invocation");
        assertEquals(className, inv.get("className").asText(), "className mismatch in invocation");
        assertEquals(
            methodName, inv.get("methodName").asText(), "methodName mismatch in invocation");

        String parameterCase = inv.get("parameterCase").asText();
        assertNotNull(parameterCase, "parameterCase must not be null");
        assertTrue(
            seenParamCasesInMethod.add(parameterCase),
            "duplicate parameterCase in method: " + parameterCase);

        String upstreamStatus = inv.get("upstreamStatus").asText();
        assertTrue(
            Set.of("ACTIVE", "DISABLED", "CONDITIONAL").contains(upstreamStatus),
            "invalid upstreamStatus: " + upstreamStatus);

        String capability = inv.get("capability").asText();
        assertNotNull(capability, "capability must not be null");
        assertFalse(capability.isBlank(), "capability must not be blank");

        String validationLayer = inv.get("validationLayer").asText();
        assertNotNull(validationLayer, "validationLayer must not be null");
        assertFalse(validationLayer.isBlank(), "validationLayer must not be blank");

        String mappingStatus = inv.get("mappingStatus").asText();
        String executionStatus = inv.get("executionStatus").asText();

        assertFalse("SKIPPED".equals(executionStatus), "executionStatus must not be SKIPPED");

        if ("PORTED".equals(mappingStatus)) {
          countedPortedInvocations++;
          assertEquals("PASSED", executionStatus);
          assertTrue(
              inv.get("capabilityMismatch") == null || inv.get("capabilityMismatch").isNull(),
              "PORTED must have null capabilityMismatch");
          assertNotNull(inv.get("targetTest"), "PORTED must have targetTest");
          String targetTest = inv.get("targetTest").asText();
          assertFalse(targetTest.isBlank(), "targetTest must not be blank");
          assertTrue(
              targetTest.endsWith("#" + methodName),
              "targetTest must end with method name: " + targetTest);
          assertTrue(
              targetTest.contains("AnthropicStreamingDecoderTest"),
              "targetTest must point to AnthropicStreamingDecoderTest: " + targetTest);
          assertTrue(
              EXPECTED_PORTED_METHOD_NAMES.contains(methodName),
              "ported methodName must belong to EXPECTED_PORTED_METHOD_NAMES: " + methodName);
          assertNotNull(inv.get("fixture"), "PORTED must have fixture");
          assertFalse(inv.get("fixture").asText().isBlank(), "fixture must not be blank");
          assertFalse(
              "not-applicable".equals(validationLayer),
              "PORTED validationLayer cannot be not-applicable");
          assertTrue(
              inv.get("realInteropStatus") == null || inv.get("realInteropStatus").isNull(),
              "PORTED realInteropStatus must be null");
        } else if ("PORT_PENDING".equals(mappingStatus)) {
          countedInScopePendingInvocations++;
          assertEquals("NOT_EXECUTED_PENDING_IMPLEMENTATION", executionStatus);
          assertFalse(
              EXPECTED_PORTED_METHOD_NAMES.contains(methodName),
              "method already ported must not be PORT_PENDING: " + methodName);
          assertTrue(
              inv.get("capabilityMismatch") == null || inv.get("capabilityMismatch").isNull(),
              "PORT_PENDING must have null capabilityMismatch");
          assertNotNull(inv.get("targetTest"), "PORT_PENDING must have targetTest");
          assertFalse(inv.get("targetTest").asText().isBlank(), "targetTest must not be blank");
          assertNotNull(inv.get("fixture"), "PORT_PENDING must have fixture");
          assertFalse(inv.get("fixture").asText().isBlank(), "fixture must not be blank");
          assertFalse(
              "not-applicable".equals(validationLayer),
              "PORT_PENDING validationLayer cannot be not-applicable");

          JsonNode realInterop = inv.get("realInteropStatus");
          if (realInterop != null && !realInterop.isNull()) {
            assertEquals("NOT_EXECUTED_REQUIRES_CREDENTIAL", realInterop.asText());
            countedRealCredentialInvocations++;
          }
        } else if ("OUT_OF_SCOPE".equals(mappingStatus)) {
          countedOutOfScopeInvocations++;
          assertEquals("NOT_EXECUTED_OUT_OF_SCOPE", executionStatus);
          JsonNode mismatch = inv.get("capabilityMismatch");
          assertNotNull(mismatch, "OUT_OF_SCOPE must have capabilityMismatch");
          assertFalse(
              mismatch.isNull() || mismatch.asText().isBlank(),
              "capabilityMismatch must be non-blank");
          assertTrue(
              inv.get("targetTest") == null || inv.get("targetTest").isNull(),
              "OUT_OF_SCOPE targetTest must be null");
          assertTrue(
              inv.get("fixture") == null || inv.get("fixture").isNull(),
              "OUT_OF_SCOPE fixture must be null");
          assertEquals(
              "not-applicable",
              validationLayer,
              "OUT_OF_SCOPE validationLayer must be not-applicable");
          assertTrue(
              inv.get("realInteropStatus") == null || inv.get("realInteropStatus").isNull(),
              "OUT_OF_SCOPE realInteropStatus must be null");
        } else {
          throw new AssertionError("unexpected mappingStatus: " + mappingStatus);
        }
      }

      // 参数化方法机械断言校验
      if ("should_handle_error_responses".equals(methodName)) {
        assertEquals(
            8, invocations.size(), "should_handle_error_responses must have 8 invocations");
      } else if ("should_support_all_enum_model_names".equals(methodName)) {
        assertEquals(
            8,
            invocations.size(),
            "should_support_all_enum_model_names must have 8 model enum invocations");
      } else if ("should_return_and_send_thinking".equals(methodName)) {
        assertEquals(
            7,
            invocations.size(),
            "should_return_and_send_thinking must have 7 model enum invocations (excluding CLAUDE_OPUS_4_7)");
      } else if ("should_NOT_return_thinking".equals(methodName)) {
        assertEquals(
            2,
            invocations.size(),
            "should_NOT_return_thinking must have 2 invocations (null and false)");
      } else if ("test_toAnthropicMessages".equals(methodName)) {
        assertEquals(14, invocations.size(), "test_toAnthropicMessages must have 14 invocations");
      } else if ("test_toAnthropicTool".equals(methodName)) {
        assertEquals(2, invocations.size(), "test_toAnthropicTool must have 2 invocations");
      } else if (sourcePath.contains("PublisherTckTest")) {
        assertEquals(
            1,
            invocations.size(),
            "TCK method invocation should be 1 per method (38 total methods)");
      } else if (sourcePath.contains("AnthropicAiServiceWithJsonSchemaIT")) {
        assertEquals(
            2,
            invocations.size(),
            "AnthropicAiServiceWithJsonSchemaIT methods must have 2 invocations (standard and beta)");
      } else if (sourcePath.equals(
          "langchain4j-anthropic/src/test/java/dev/langchain4j/model/anthropic/common/AnthropicStreamingChatModelIT.java")) {
        if (invocations.size() > 1) {
          assertEquals(
              2,
              invocations.size(),
              "Parameterized streaming methods must have 2 invocations (HANDLER and PUBLISHER)");
        }
      }
    }

    // 验证所有预期 source 文件全部覆盖
    assertEquals(EXPECTED_TOTAL_SOURCES, seenSources.size(), "seen sources count");
    for (Map.Entry<String, Set<String>> entry : SOURCE_TO_METHODS.entrySet()) {
      Set<String> seenInSource = seenMethodsPerSource.get(entry.getKey());
      assertNotNull(seenInSource, () -> "source missed in manifest: " + entry.getKey());
      assertEquals(
          entry.getValue().size(),
          seenInSource.size(),
          () -> "methods count mismatch for source: " + entry.getKey());
    }

    // 动态聚合指标最终断言
    assertEquals(EXPECTED_TOTAL_METHODS, countedMethods, "total methods");
    assertEquals(EXPECTED_TOTAL_INVOCATIONS, countedInvocations, "total invocations");
    assertEquals(EXPECTED_PORTED_INVOCATIONS, countedPortedInvocations, "ported invocations");
    assertEquals(
        EXPECTED_IN_SCOPE_PENDING_INVOCATIONS,
        countedInScopePendingInvocations,
        "in-scope pending invocations");
    assertEquals(
        EXPECTED_OUT_OF_SCOPE_INVOCATIONS,
        countedOutOfScopeInvocations,
        "out-of-scope invocations");
    assertEquals(
        EXPECTED_REAL_CREDENTIAL_INVOCATIONS,
        countedRealCredentialInvocations,
        "real-credential IT invocations");

    System.out.printf(
        "Anthropic Upstream Manifest Validation PASSED: %d sources, %d methods, %d invocations (%d ported, %d pending, %d out-of-scope, %d real-credential)%n",
        seenSources.size(),
        countedMethods,
        countedInvocations,
        countedPortedInvocations,
        countedInScopePendingInvocations,
        countedOutOfScopeInvocations,
        countedRealCredentialInvocations);
  }
}
