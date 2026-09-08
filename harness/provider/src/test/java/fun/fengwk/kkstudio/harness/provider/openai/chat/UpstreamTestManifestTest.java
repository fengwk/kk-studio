package fun.fengwk.kkstudio.harness.provider.openai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 校验 OpenAI upstream-test-manifest.json 的机器可读性、全量上游 Inventory 覆盖与自包含性。
 *
 * <p>保证 LangChain4j 1.20.0 在 langchain4j-open-ai 模块全部 70 个测试文件、 追踪继承/基座方法后共计 502 个方法、按 invocation
 * 展开共 659 个 invocation 全量可审计。
 */
class UpstreamTestManifestTest {

  private static final String REQUIRED_UPSTREAM_COMMIT = "3a2f4dca6fb447e4d191624b3d588952ed9f4ce9";
  private static final String REQUIRED_UPSTREAM_VERSION = "1.20.0";

  private static final int EXPECTED_TOTAL_SOURCES = 70;
  private static final int EXPECTED_TOTAL_METHODS = 502;
  private static final int EXPECTED_TOTAL_INVOCATIONS = 659;
  private static final int EXPECTED_PORTED_INVOCATIONS = 247;
  private static final int EXPECTED_IN_SCOPE_PENDING_INVOCATIONS = 0;
  private static final int EXPECTED_OUT_OF_SCOPE_INVOCATIONS = 412;
  private static final int EXPECTED_REAL_CREDENTIAL_INVOCATIONS = 390;

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String TARGET_CLASS_PREFIX =
      "fun.fengwk.kkstudio.harness.provider.openai.chat.";
  private static final String CLASSPATH_FIXTURE_PREFIX = "/fun/fengwk/kkstudio/harness/provider/";

  private static final Map<String, Boolean> VALIDATED_TARGETS = new HashMap<>();
  private static final Map<String, Boolean> VALIDATED_FIXTURES = new HashMap<>();

  private static void validateTargetTest(String targetTest) {
    assertNotNull(targetTest, "PORTED must have targetTest");
    assertFalse(targetTest.isBlank(), "targetTest must not be blank");

    int hashIndex = targetTest.indexOf('#');
    assertTrue(
        hashIndex > 0 && hashIndex < targetTest.length() - 1,
        () -> "targetTest must follow class#method format: " + targetTest);
    assertEquals(
        -1,
        targetTest.indexOf('#', hashIndex + 1),
        () -> "targetTest must contain exactly one '#': " + targetTest);

    String targetClassName = targetTest.substring(0, hashIndex).trim();
    String targetMethodName = targetTest.substring(hashIndex + 1).trim();
    assertFalse(
        targetClassName.isBlank(), () -> "targetClassName must not be blank: " + targetTest);
    assertFalse(
        targetMethodName.isBlank(), () -> "targetMethodName must not be blank: " + targetTest);
    assertTrue(
        targetClassName.startsWith(TARGET_CLASS_PREFIX),
        () -> "targetTest class must start with " + TARGET_CLASS_PREFIX + ": " + targetTest);

    if (VALIDATED_TARGETS.containsKey(targetTest)) {
      return;
    }

    Class<?> targetClass;
    try {
      targetClass = Class.forName(targetClassName);
    } catch (ClassNotFoundException e) {
      throw new AssertionError(
          "targetTest class not found: " + targetClassName + " in " + targetTest, e);
    }

    assertFalse(
        targetClass.isAnnotationPresent(Disabled.class),
        () -> "targetTest class must not be annotated with @Disabled: " + targetClassName);

    boolean foundAnnotatedMethod = false;
    for (Method method : targetClass.getDeclaredMethods()) {
      if (method.getName().equals(targetMethodName)) {
        assertFalse(
            method.isAnnotationPresent(Disabled.class),
            () -> "targetTest method must not be annotated with @Disabled: " + targetMethodName);
        if (method.isAnnotationPresent(Test.class)
            || method.isAnnotationPresent(ParameterizedTest.class)) {
          foundAnnotatedMethod = true;
          break;
        }
      }
    }

    assertTrue(
        foundAnnotatedMethod,
        () ->
            "targetTest method '"
                + targetMethodName
                + "' on class '"
                + targetClassName
                + "' must exist and be annotated with @Test or @ParameterizedTest");

    VALIDATED_TARGETS.put(targetTest, Boolean.TRUE);
  }

  private static void validateFixture(String fixture) {
    assertNotNull(fixture, "PORTED fixture must not be null");
    assertFalse(fixture.isBlank(), "PORTED fixture must not be blank");
    assertFalse(
        fixture.startsWith("/"),
        () -> "PORTED fixture must be relative (cannot start with /): " + fixture);
    assertTrue(
        fixture.startsWith("openai/chat/fixtures/"),
        () -> "PORTED fixture must start with openai/chat/fixtures/: " + fixture);
    assertFalse(fixture.contains(".."), () -> "PORTED fixture must not contain '..': " + fixture);

    if (VALIDATED_FIXTURES.containsKey(fixture)) {
      return;
    }

    String resourcePath = CLASSPATH_FIXTURE_PREFIX + fixture;
    try (InputStream in = UpstreamTestManifestTest.class.getResourceAsStream(resourcePath)) {
      assertNotNull(in, () -> "fixture resource not found on classpath: " + resourcePath);
      byte[] bytes = in.readAllBytes();
      assertTrue(bytes.length > 0, () -> "fixture resource must not be empty: " + resourcePath);

      if (fixture.endsWith(".json")) {
        try (JsonParser parser = MAPPER.createParser(bytes)) {
          JsonNode tree = MAPPER.readTree(parser);
          assertNotNull(tree, () -> "JSON tree must not be null for " + resourcePath);
          assertFalse(
              tree.isNull(), () -> "JSON tree must not represent a null value for " + resourcePath);
          assertNull(
              parser.nextToken(),
              () -> "fixture JSON must have no trailing content for " + resourcePath);
        } catch (IOException e) {
          throw new AssertionError("fixture failed to parse as valid JSON: " + resourcePath, e);
        }
      }
    } catch (IOException e) {
      throw new AssertionError("failed reading fixture resource: " + resourcePath, e);
    }

    VALIDATED_FIXTURES.put(fixture, Boolean.TRUE);
  }

  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiAudioTranscriptionModelIT.java
  private static final Set<String> METHODS_SOURCE_1 = Set.of("should_support_all_model_names");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiCachingProxyIT.java
  private static final Set<String> METHODS_SOURCE_2 =
      Set.of("should_cache_streaming_chat_responses", "should_cache_sync_chat_responses");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiChatModelAsyncRetryTest.java
  private static final Set<String> METHODS_SOURCE_3 =
      Set.of(
          "chatAsync_does_not_retry_a_non_retriable_failure",
          "chatAsync_retries_a_retriable_failure_and_then_succeeds");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiChatModelAsyncTest.java
  private static final Set<String> METHODS_SOURCE_4 =
      Set.of(
          "chatAsync_can_be_cancelled_while_in_flight",
          "chatAsync_completes_exceptionally_on_http_error");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiChatModelErrorsTest.java
  private static final Set<String> METHODS_SOURCE_5 =
      Set.of("should_handle_error_responses", "should_handle_refusal", "should_handle_timeout");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiChatModelIT.java
  private static final Set<String> METHODS_SOURCE_6 =
      Set.of(
          "should_accept_pdf_file_content",
          "should_answer_with_reasoning_effort",
          "should_generate_valid_json",
          "should_respect_deprecated_maxTokens",
          "should_respect_maxCompletionTokens",
          "should_return_logprobs",
          "should_set_custom_parameters_and_get_raw_response",
          "should_support_all_model_names",
          "test_toolChoice_none");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiChatModelListenerIT.java
  private static final Set<String> METHODS_SOURCE_7 =
      Set.of("should_listen_error", "should_listen_request_and_response");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiChatModelNonBlockingIT.java
  private static final Set<String> METHODS_SOURCE_8 =
      Set.of(
          "blockHound_detects_blocking_on_a_policed_thread",
          "chatAsync_does_not_block_the_http_worker_threads",
          "streaming_publisher_does_not_block_the_http_worker_threads");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiChatModelWithJsonSchemaIT.java
  private static final Set<String> METHODS_SOURCE_9 =
      Set.of("should_generate_valid_json_with_anyof", "should_support_json_schema_in_model");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiChatRequestParametersTest.java
  private static final Set<String> METHODS_SOURCE_10 = Set.of("null_model_name", "override_with");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiChatRequestTest.java
  private static final Set<String> METHODS_SOURCE_11 =
      concatSets(
          Set.of(
              "should_handle_boolean_false_values",
              "should_handle_empty_maps",
              "should_handle_large_values",
              "should_handle_zero_values",
              "should_not_be_equal_to_different_class",
              "should_not_be_equal_to_null",
              "should_not_be_equal_when_fields_differ",
              "should_override_with_other_openai_parameters",
              "should_set_OpenAI_specific_parameters_then_common_parameters",
              "should_set_common_parameters_then_OpenAI_specific_parameters"),
          Set.of("should_use_empty_constant"));
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiChatResponseMetadataTest.java
  private static final Set<String> METHODS_SOURCE_12 =
      Set.of(
          "should_modify_all_properties_via_builder",
          "should_modify_parent_properties_via_builder",
          "should_modify_specific_properties_via_builder",
          "should_not_throw_when_casting_TokenUsage_to_OpenAiTokenUsage");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiCustomHeadersSupplierTest.java
  private static final Set<String> METHODS_SOURCE_13 =
      Set.of(
          "should_call_supplier_for_each_request_with_chat_model",
          "should_handle_null_from_supplier",
          "should_work_with_static_map_for_backwards_compatibility");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiEmbeddingModelAsyncRetryTest.java
  private static final Set<String> METHODS_SOURCE_14 =
      Set.of(
          "embedAsync_does_not_retry_a_non_retriable_failure",
          "embedAsync_retries_a_retriable_failure_and_then_succeeds");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiEmbeddingModelAsyncTest.java
  private static final Set<String> METHODS_SOURCE_15 =
      Set.of(
          "cancelling_embedAsync_future_aborts_the_in_flight_http_call",
          "multi_batch_failure_reports_the_real_error_not_a_masked_cancellation");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiEmbeddingModelIT.java
  private static final Set<String> METHODS_SOURCE_16 =
      Set.of(
          "should_embed_multiple_batch_segments",
          "should_embed_multiple_segments",
          "should_embed_single_text",
          "should_embed_single_text_with_base64_encoding_format",
          "should_embed_text_with_embedding_shortening");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiEmbeddingModelTest.java
  private static final Set<String> METHODS_SOURCE_17 =
      Set.of(
          "embedAsync_returns_the_embedding_via_the_async_http_path",
          "should_not_be_affected_when_custom_parameters_map_is_modified_after_build",
          "should_not_send_custom_parameters_when_absent",
          "should_notify_configured_listener",
          "should_send_custom_parameters");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiEmbeddingRequestParametersTest.java
  private static final Set<String> METHODS_SOURCE_18 =
      Set.of(
          "carries_openai_specific_and_common_parameters",
          "customParameter_merges_into_the_map",
          "override_with_empty_or_null_returns_same_instance",
          "override_with_merges_common_and_specific",
          "unset_parameters_are_absent");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiImageModelIT.java
  private static final Set<String> METHODS_SOURCE_19 =
      Set.of(
          "image_edit_with_mask_works",
          "image_edit_works",
          "image_generation_with_quality_works",
          "image_generation_with_transparent_background_works",
          "multiple_images_generation_works",
          "should_use_enum_as_model_name",
          "simple_image_generation_works");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiImageModelRetryTest.java
  private static final Set<String> METHODS_SOURCE_20 =
      Set.of(
          "should_not_retry_image_generation_when_max_retries_is_zero",
          "should_retry_image_editing",
          "should_retry_image_generation");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiLanguageModelIT.java
  private static final Set<String> METHODS_SOURCE_21 =
      Set.of("should_generate_answer_and_return_token_usage_and_finish_reason_stop");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiModerationModelIT.java
  private static final Set<String> METHODS_SOURCE_22 =
      Set.of(
          "should_flag",
          "should_not_flag",
          "should_use_enum_as_model_name",
          "should_use_model_name_from_request_overriding_default");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiModerationModelListenerIT.java
  private static final Set<String> METHODS_SOURCE_23 =
      Set.of(
          "should_continue_executing_other_listeners_when_one_throws_exception",
          "should_continue_executing_other_listeners_when_one_throws_exception_in_onError",
          "should_listen_error",
          "should_listen_request_and_response");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiResponsesChatModelNonBlockingIT.java
  private static final Set<String> METHODS_SOURCE_24 =
      Set.of(
          "blockHound_detects_blocking_on_a_policed_thread",
          "chatAsync_does_not_block_the_http_worker_threads",
          "streaming_publisher_does_not_block_the_http_worker_threads");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiResponsesChatRequestParametersTest.java
  private static final Set<String> METHODS_SOURCE_25 =
      Set.of(
          "should_include_server_tools_in_equals_and_hash_code",
          "should_override_server_tools",
          "should_store_server_tools",
          "should_store_server_tools_in_chat_model_default_request_parameters",
          "should_store_server_tools_in_streaming_model_default_request_parameters");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiResponsesCustomHeadersTest.java
  private static final Set<String> METHODS_SOURCE_26 =
      Set.of(
          "should_allow_custom_headers_to_override_default_headers",
          "should_call_custom_headers_supplier_before_each_request",
          "should_handle_null_returned_by_custom_headers_supplier",
          "should_send_custom_headers_with_chat_model",
          "should_send_custom_headers_with_streaming_chat_model",
          "should_work_without_custom_headers");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiResponsesFinishReasonTest.java
  private static final Set<String> METHODS_SOURCE_27 =
      Set.of(
          "should_report_content_filter_when_response_is_incomplete_because_of_the_content_filter",
          "should_report_content_filter_when_stream_ends_incomplete_because_of_the_content_filter",
          "should_report_length_when_response_is_incomplete_because_of_the_token_limit",
          "should_report_length_when_response_is_incomplete_without_details",
          "should_report_length_when_the_incomplete_reason_is_not_recognised");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiResponsesStreamingChatModelPayloadTest.java
  private static final Set<String> METHODS_SOURCE_28 =
      Set.of(
          "should_override_default_server_tools_with_request_server_tools",
          "should_send_function_and_server_tools_together",
          "should_send_object_schema_for_tool_without_parameters",
          "should_send_only_server_tools",
          "should_send_strict_false_explicitly_for_tool_with_parameters",
          "should_send_strict_object_schema_for_tool_without_parameters_when_strict");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiResponsesStreamingChatModelPublisherTckTest.java
  private static final Set<String> METHODS_SOURCE_29 =
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
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiResponsesStreamingChatModelRawEventTest.java
  private static final Set<String> METHODS_SOURCE_30 =
      Set.of("should_forward_only_raw_events_not_exposed_via_typed_callbacks");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiResponsesStreamingEventParsingTest.java
  private static final Set<String> METHODS_SOURCE_31 =
      Set.of(
          "should_complete_a_tool_call_from_the_output_item_done_event",
          "should_fail_with_the_error_message_of_the_failed_event",
          "should_fall_back_to_the_error_payload_when_the_failed_event_carries_no_message",
          "should_ignore_events_that_do_not_belong_to_a_known_tool_call",
          "should_read_the_metadata_of_the_completed_event",
          "should_report_the_incomplete_reason_of_the_incomplete_event",
          "should_stream_a_tool_call_assembled_from_its_events",
          "should_stream_text_and_reasoning_deltas");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiStreamingChatModelErrorsTest.java
  private static final Set<String> METHODS_SOURCE_32 =
      Set.of(
          "should_handle_error_responses",
          "should_handle_timeout",
          "should_map_error_responses_on_the_reactive_publisher_path");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiStreamingChatModelIT.java
  private static final Set<String> METHODS_SOURCE_33 =
      Set.of(
          "should_execute_a_tool_with_blank_partial_arguments",
          "should_respect_deprecated_maxTokens",
          "should_respect_maxCompletionTokens",
          "should_set_custom_parameters_and_get_raw_response",
          "should_stream_valid_json",
          "should_support_all_model_names");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiStreamingChatModelListenerIT.java
  private static final Set<String> METHODS_SOURCE_34 =
      Set.of("should_listen_error", "should_listen_request_and_response");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiStreamingChatModelPublisherCancellationTest.java
  private static final Set<String> METHODS_SOURCE_35 =
      Set.of("cancelling_subscription_mid_stream_stops_the_pipeline");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiStreamingChatModelPublisherListenerTest.java
  private static final Set<String> METHODS_SOURCE_36 =
      Set.of(
          "listener_gets_only_onRequest_when_subscriber_throws_from_onNext",
          "listener_gets_only_onRequest_when_subscription_is_cancelled_mid_stream",
          "listener_is_invoked_with_request_then_error_on_failure",
          "listener_is_invoked_with_request_then_response_on_success");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiStreamingChatModelPublisherTckTest.java
  private static final Set<String> METHODS_SOURCE_37 =
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
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiStreamingChatModelRawEventTest.java
  private static final Set<String> METHODS_SOURCE_38 =
      Set.of(
          "should_forward_only_raw_events_not_exposed_via_typed_callbacks",
          "should_not_throw_npe_when_tool_call_delta_has_no_function_object");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiStreamingLanguageModelIT.java
  private static final Set<String> METHODS_SOURCE_39 = Set.of("should_stream_answer");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiStreamingResponseBuilderTest.java
  private static final Set<String> METHODS_SOURCE_40 =
      Set.of(
          "should_accumulate_logprobs_across_streaming_chunks",
          "should_handle_multiple_tool_calls_with_null_index",
          "should_handle_non_null_tool_call_index",
          "should_handle_null_tool_call_index",
          "should_ignore_trailing_sentinel_chunk_from_deepseek_v4_flash",
          "should_keep_all_tool_calls_from_same_delta",
          "should_keep_logprobs_null_when_not_requested",
          "should_not_produce_ghost_for_orphan_sentinel_chunk",
          "should_not_throw_npe_when_tool_call_has_no_function");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiTextToSpeechModelIT.java
  private static final Set<String> METHODS_SOURCE_41 = Set.of("should_support_all_model_names");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiTextToSpeechModelTest.java
  private static final Set<String> METHODS_SOURCE_42 =
      Set.of("should_reject_text_exceeding_max_length", "should_require_model_name");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiTokenCountEstimatorIT.java
  private static final Set<String> METHODS_SOURCE_43 =
      Set.of(
          "should_count_tokens_in_messages",
          "should_count_tokens_in_messages_with_multiple_tools",
          "should_count_tokens_in_messages_with_single_tool");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiTokenCountEstimatorTest.java
  private static final Set<String> METHODS_SOURCE_44 =
      Set.of(
          "should_count_tokens_in_ai_message_with_empty_tool_arguments",
          "should_count_tokens_in_ai_message_with_null_tool_argument",
          "should_count_tokens_in_average_text",
          "should_count_tokens_in_large_text",
          "should_count_tokens_in_short_texts",
          "should_encode_and_decode_text",
          "should_encode_with_truncation_and_decode_text",
          "should_support_all_chat_model_names",
          "should_support_all_embedding_model_names",
          "should_support_all_language_model_names");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiTokenUsageTest.java
  private static final Set<String> METHODS_SOURCE_45 =
      Set.of(
          "should_add_token_usages_with_both_input_and_output_details",
          "should_add_token_usages_with_input_details",
          "should_add_token_usages_with_output_details",
          "should_add_two_basic_token_usages",
          "should_handle_adding_openai_token_usage_to_standard_token_usage",
          "should_handle_adding_standard_token_usage_to_openai_token_usage",
          "should_handle_null_token_usage_when_adding",
          "should_handle_null_values_in_counter_fields",
          "should_handle_one_side_having_details");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiToolSpecificationIT.java
  private static final Set<String> METHODS_SOURCE_46 =
      Set.of(
          "should_call_tool_with_array_parameter_with_multiple_allowed_types",
          "should_call_tool_with_array_parameter_with_unspecified_item_type");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/common/OpenAiChatModelIT.java
  private static final Set<String> METHODS_SOURCE_47 =
      concatSets(
          Set.of(
              "should_accept_multiple_images_as_base64_encoded_strings",
              "should_accept_multiple_images_as_public_URLs",
              "should_accept_single_image_as_base64_encoded_string",
              "should_accept_single_image_as_public_URL",
              "should_chat_asynchronously",
              "should_execute_a_tool_then_answer",
              "should_execute_a_tool_then_answer_respecting_JSON_response_format_with_schema",
              "should_execute_a_tool_without_arguments_then_answer",
              "should_execute_multiple_tools_in_parallel_then_answer",
              "should_fail_if_JSON_response_format_is_not_supported"),
          Set.of(
              "should_fail_if_JSON_response_format_with_schema_is_not_supported",
              "should_fail_if_images_as_base64_encoded_strings_are_not_supported",
              "should_fail_if_images_as_public_URLs_are_not_supported",
              "should_fail_if_maxOutputTokens_parameter_is_not_supported",
              "should_fail_if_modelName_is_not_supported",
              "should_fail_if_stopSequences_parameter_is_not_supported",
              "should_fail_if_tool_choice_REQUIRED_is_not_supported",
              "should_fail_if_tools_are_not_supported",
              "should_force_LLM_to_execute_any_tool",
              "should_force_LLM_to_execute_specific_tool"),
          Set.of(
              "should_propagate_all_OpenAI_specific_parameters",
              "should_propagate_custom_http_headers",
              "should_propagate_custom_query_parameters",
              "should_respect_JSON_response_format",
              "should_respect_JSON_response_format_with_schema",
              "should_respect_JsonRawSchema_responseFormat",
              "should_respect_common_parameters_wrapped_in_integration_specific_class_in_chat_request",
              "should_respect_common_parameters_wrapped_in_integration_specific_class_in_default_model_parameters",
              "should_respect_logitBias_parameter",
              "should_respect_maxOutputTokens_in_chat_request"),
          Set.of(
              "should_respect_maxOutputTokens_in_default_model_parameters",
              "should_respect_modelName_in_chat_request",
              "should_respect_modelName_in_default_model_parameters",
              "should_respect_multiple_messages",
              "should_respect_parallelToolCalls_parameter",
              "should_respect_stopSequences_in_chat_request",
              "should_respect_stopSequences_in_default_model_parameters",
              "should_respect_system_message",
              "should_respect_user_message",
              "should_return_model_specific_response_metadata"));
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/common/OpenAiEmbeddingModelIT.java
  private static final Set<String> METHODS_SOURCE_48 =
      concatSets(
          Set.of(
              "should_embed_all_text_segments",
              "should_embed_batch_of_inputs_in_order",
              "should_embed_image",
              "should_embed_interleaved_text_and_image",
              "should_embed_query_and_document_differently",
              "should_embed_single_input",
              "should_embed_via_convenience_string",
              "should_embed_via_convenience_text_segment",
              "should_fail_when_dimensions_is_not_supported",
              "should_fail_when_image_input_is_not_supported"),
          Set.of(
              "should_fail_when_input_type_is_not_supported",
              "should_notify_listener_on_error",
              "should_notify_listener_on_request_and_response",
              "should_report_positive_dimension",
              "should_respect_dimensions_parameter"));
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/common/OpenAiListenableChatModelIT.java
  private static final Set<String> METHODS_SOURCE_49 =
      Set.of("should_listen_error", "should_listen_request_and_response");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/common/OpenAiModelCatalogIT.java
  private static final Set<String> METHODS_SOURCE_50 =
      Set.of(
          "should_discover_models",
          "should_have_creation_timestamp",
          "should_have_owner_information",
          "should_return_correct_provider");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/common/OpenAiStreamingChatModelIT.java
  private static final Set<String> METHODS_SOURCE_51 =
      concatSets(
          Set.of(
              "should_accept_multiple_images_as_base64_encoded_strings",
              "should_accept_multiple_images_as_public_URLs",
              "should_accept_single_image_as_base64_encoded_string",
              "should_accept_single_image_as_public_URL",
              "should_cancel_streaming",
              "should_execute_a_tool_then_answer",
              "should_execute_a_tool_then_answer_respecting_JSON_response_format_with_schema",
              "should_execute_a_tool_without_arguments_then_answer",
              "should_execute_multiple_tools_in_parallel_then_answer",
              "should_fail_if_JSON_response_format_is_not_supported"),
          Set.of(
              "should_fail_if_JSON_response_format_with_schema_is_not_supported",
              "should_fail_if_images_as_base64_encoded_strings_are_not_supported",
              "should_fail_if_images_as_public_URLs_are_not_supported",
              "should_fail_if_maxOutputTokens_parameter_is_not_supported",
              "should_fail_if_modelName_is_not_supported",
              "should_fail_if_stopSequences_parameter_is_not_supported",
              "should_fail_if_tool_choice_REQUIRED_is_not_supported",
              "should_fail_if_tools_are_not_supported",
              "should_force_LLM_to_execute_any_tool",
              "should_force_LLM_to_execute_specific_tool"),
          Set.of(
              "should_ignore_user_exceptions_thrown_from_onError",
              "should_propagate_user_exceptions_thrown_from_onCompleteResponse",
              "should_propagate_user_exceptions_thrown_from_onPartialResponse",
              "should_respect_JSON_response_format",
              "should_respect_JSON_response_format_with_schema",
              "should_respect_JsonRawSchema_responseFormat",
              "should_respect_common_parameters_wrapped_in_integration_specific_class_in_chat_request",
              "should_respect_common_parameters_wrapped_in_integration_specific_class_in_default_model_parameters",
              "should_respect_maxOutputTokens_in_chat_request",
              "should_respect_maxOutputTokens_in_default_model_parameters"),
          Set.of(
              "should_respect_modelName_in_chat_request",
              "should_respect_modelName_in_default_model_parameters",
              "should_respect_multiple_messages",
              "should_respect_stopSequences_in_chat_request",
              "should_respect_stopSequences_in_default_model_parameters",
              "should_respect_system_message",
              "should_respect_user_message"));
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/common/responses/OpenAiResponsesChatModelIT.java
  private static final Set<String> METHODS_SOURCE_52 =
      concatSets(
          Set.of(
              "should_accept_multiple_images_as_base64_encoded_strings",
              "should_accept_multiple_images_as_public_URLs",
              "should_accept_pdf_file_content_as_public_url",
              "should_accept_single_image_as_base64_encoded_string",
              "should_accept_single_image_as_public_URL",
              "should_chat_asynchronously",
              "should_execute_a_tool_then_answer",
              "should_execute_a_tool_then_answer_respecting_JSON_response_format_with_schema",
              "should_execute_a_tool_without_arguments_then_answer",
              "should_execute_multiple_tools_in_parallel_then_answer"),
          Set.of(
              "should_fail_if_JSON_response_format_is_not_supported",
              "should_fail_if_JSON_response_format_with_schema_is_not_supported",
              "should_fail_if_images_as_base64_encoded_strings_are_not_supported",
              "should_fail_if_images_as_public_URLs_are_not_supported",
              "should_fail_if_maxOutputTokens_parameter_is_not_supported",
              "should_fail_if_modelName_is_not_supported",
              "should_fail_if_stopSequences_parameter_is_not_supported",
              "should_fail_if_tool_choice_REQUIRED_is_not_supported",
              "should_fail_if_tools_are_not_supported",
              "should_force_LLM_to_execute_any_tool"),
          Set.of(
              "should_force_LLM_to_execute_specific_tool",
              "should_respect_JSON_response_format",
              "should_respect_JSON_response_format_with_schema",
              "should_respect_JsonRawSchema_responseFormat",
              "should_respect_common_parameters_wrapped_in_integration_specific_class_in_chat_request",
              "should_respect_common_parameters_wrapped_in_integration_specific_class_in_default_model_parameters",
              "should_respect_maxOutputTokens_in_chat_request",
              "should_respect_maxOutputTokens_in_default_model_parameters",
              "should_respect_modelName_in_chat_request",
              "should_respect_modelName_in_default_model_parameters"),
          Set.of(
              "should_respect_multiple_messages",
              "should_respect_stopSequences_in_chat_request",
              "should_respect_stopSequences_in_default_model_parameters",
              "should_respect_system_message",
              "should_respect_user_message"));
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/common/responses/OpenAiResponsesChatModelListenerIT.java
  private static final Set<String> METHODS_SOURCE_53 =
      Set.of("should_listen_error", "should_listen_request_and_response");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/common/responses/OpenAiResponsesChatModelThinkingIT.java
  private static final Set<String> METHODS_SOURCE_54 =
      Set.of(
          "should_not_return_reasoning_summary_when_not_requested",
          "should_return_encrypted_reasoning_and_send_it_back__single_tool_call",
          "should_return_encrypted_reasoning_and_send_it_back__two_parallel_tool_calls",
          "should_return_reasoning_summary");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/common/responses/OpenAiResponsesDynamicToolIT.java
  private static final Set<String> METHODS_SOURCE_55 =
      Set.of("should_send_arbitrary_arguments_to_a_tool_without_declared_parameters");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/common/responses/OpenAiResponsesStreamingChatModelIT.java
  private static final Set<String> METHODS_SOURCE_56 =
      concatSets(
          Set.of(
              "should_accept_multiple_images_as_base64_encoded_strings",
              "should_accept_multiple_images_as_public_URLs",
              "should_accept_pdf_file_content_as_public_url",
              "should_accept_single_image_as_base64_encoded_string",
              "should_accept_single_image_as_public_URL",
              "should_cancel_streaming",
              "should_execute_a_tool_then_answer",
              "should_execute_a_tool_then_answer_respecting_JSON_response_format_with_schema",
              "should_execute_a_tool_without_arguments_then_answer",
              "should_execute_multiple_tools_in_parallel_then_answer"),
          Set.of(
              "should_fail_if_JSON_response_format_is_not_supported",
              "should_fail_if_JSON_response_format_with_schema_is_not_supported",
              "should_fail_if_images_as_base64_encoded_strings_are_not_supported",
              "should_fail_if_images_as_public_URLs_are_not_supported",
              "should_fail_if_maxOutputTokens_parameter_is_not_supported",
              "should_fail_if_modelName_is_not_supported",
              "should_fail_if_stopSequences_parameter_is_not_supported",
              "should_fail_if_tool_choice_REQUIRED_is_not_supported",
              "should_fail_if_tools_are_not_supported",
              "should_force_LLM_to_execute_any_tool"),
          Set.of(
              "should_force_LLM_to_execute_specific_tool",
              "should_ignore_user_exceptions_thrown_from_onError",
              "should_propagate_all_Responses_API_specific_parameters",
              "should_propagate_user_exceptions_thrown_from_onCompleteResponse",
              "should_propagate_user_exceptions_thrown_from_onPartialResponse",
              "should_respect_JSON_response_format",
              "should_respect_JSON_response_format_with_schema",
              "should_respect_JsonRawSchema_responseFormat",
              "should_respect_common_parameters_wrapped_in_integration_specific_class_in_chat_request",
              "should_respect_common_parameters_wrapped_in_integration_specific_class_in_default_model_parameters"),
          Set.of(
              "should_respect_maxOutputTokens_in_chat_request",
              "should_respect_maxOutputTokens_in_default_model_parameters",
              "should_respect_modelName_in_chat_request",
              "should_respect_modelName_in_default_model_parameters",
              "should_respect_multiple_messages",
              "should_respect_stopSequences_in_chat_request",
              "should_respect_stopSequences_in_default_model_parameters",
              "should_respect_system_message",
              "should_respect_user_message",
              "should_return_model_specific_response_metadata"),
          Set.of(
              "should_send_previous_response_id_from_default_request_parameters",
              "should_send_previous_response_id_from_request_parameters",
              "should_surface_response_error_message_from_error_node",
              "should_surface_response_failed_message_from_error_node",
              "should_work_with_o_models"));
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/common/responses/OpenAiResponsesStreamingChatModelListenerIT.java
  private static final Set<String> METHODS_SOURCE_57 =
      Set.of("should_listen_error", "should_listen_request_and_response");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/common/responses/OpenAiResponsesStreamingChatModelThinkingIT.java
  private static final Set<String> METHODS_SOURCE_58 =
      Set.of(
          "should_not_return_reasoning_summary_when_not_requested",
          "should_return_encrypted_reasoning_and_send_it_back__single_tool_call",
          "should_return_encrypted_reasoning_and_send_it_back__two_parallel_tool_calls",
          "should_return_reasoning_summary");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/compatible/deepseek/OpenAiChatModelDeepSeekThinkingIT.java
  private static final Set<String> METHODS_SOURCE_59 =
      Set.of(
          "should_NOT_return_thinking",
          "should_return_thinking",
          "should_send_thinking_in_follow_up_request_when_enabled",
          "should_use_custom_reasoning_content_field_name_in_follow_up_request");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/compatible/deepseek/OpenAiStreamingChatModelDeepSeekIT.java
  private static final Set<String> METHODS_SOURCE_60 = Set.of("should_respect_user_message");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/compatible/deepseek/OpenAiStreamingChatModelDeepSeekThinkingIT.java
  private static final Set<String> METHODS_SOURCE_61 =
      Set.of(
          "should_NOT_return_thinking",
          "should_return_thinking",
          "should_send_thinking_in_follow_up_request_when_enabled",
          "should_use_custom_reasoning_content_field_name_in_follow_up_request");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/internal/ChatCompletionResponseDeserializeTest.java
  private static final Set<String> METHODS_SOURCE_62 =
      Set.of(
          "should_deserialize_chat_response_without_tool_type",
          "should_deserialize_delta_with_reasoning_field",
          "should_deserialize_message_with_reasoning_content_field",
          "should_deserialize_message_with_reasoning_field",
          "should_deserialize_message_without_reasoning_fields");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/internal/OpenAiBuilderCreatorParityTest.java
  private static final Set<String> METHODS_SOURCE_63 =
      Set.of("a_default_applied_inside_build_does_not_survive_the_creator_route");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/internal/OpenAiImageContentFormatTest.java
  private static final Set<String> METHODS_SOURCE_64 =
      Set.of(
          "chat_model_should_use_image_url_format_by_default",
          "chat_model_should_use_input_image_format_when_configured",
          "should_deserialize_chat_completions_image_url_format",
          "should_deserialize_input_image_format",
          "should_use_chat_completions_image_url_format_by_default",
          "should_use_input_image_format_when_configured",
          "streaming_chat_model_should_use_input_image_format_when_configured");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/internal/OpenAiUtilsTest.java
  private static final Set<String> METHODS_SOURCE_65 =
      concatSets(
          Set.of(
              "per_tool_strict_false_should_override_model_level_true",
              "per_tool_strict_null_should_fall_back_to_model_level",
              "per_tool_strict_true_should_override_model_level_false",
              "should_exclude_thinking_content_when_returnThinking_is_false",
              "should_handle_tool_call_with_id",
              "should_include_thinking_content_when_returnThinking_is_true",
              "should_map_all_tool_choices",
              "should_map_tool_choice",
              "should_return_ai_message_with_text_when_no_functions_and_tool_calls_are_present",
              "should_return_ai_message_with_toolExecutionRequests_and_text_when_tool_calls_and_content_are_both_present"),
          Set.of(
              "should_return_ai_message_with_toolExecutionRequests_when_function_is_present",
              "should_return_ai_message_with_toolExecutionRequests_when_tool_calls_are_present",
              "should_return_null_text_when_content_is_null",
              "should_throw_when_choices_is_empty",
              "should_throw_when_choices_is_null",
              "should_throw_when_multiple_choices_are_returned"));
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/internal/StreamingRequestExecutorTest.java
  private static final Set<String> METHODS_SOURCE_66 = Set.of("should_process_streaming_error");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/internal/WireFormatTest.java
  private static final Set<String> METHODS_SOURCE_67 =
      Set.of(
          "chat_request_fields_are_snake_case",
          "embedding_request_fields_are_snake_case",
          "explicitly_named_properties_are_left_alone");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/internal/chat/VideoContentConversionTest.java
  private static final Set<String> METHODS_SOURCE_68 =
      Set.of(
          "should_build_user_message_with_multiple_video_urls",
          "should_build_user_message_with_video_url",
          "should_create_video_url_with_builder",
          "should_have_correct_equals_and_hashcode_for_video_url",
          "should_have_correct_tostring_for_video_url",
          "should_include_video_in_content_equals_and_hashcode",
          "should_include_video_in_content_tostring");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/internal/embedding/EmbeddingDeserializationTest.java
  private static final Set<String> METHODS_SOURCE_69 =
      Set.of(
          "should_read_an_embedding_response",
          "should_read_an_embedding_sent_as_an_array_of_numbers",
          "should_read_an_embedding_sent_as_base64",
          "should_read_an_embedding_without_the_embedding_field",
          "should_read_an_empty_embedding",
          "should_reject_an_array_that_does_not_hold_numbers",
          "should_reject_an_embedding_that_is_neither_an_array_nor_a_string");
  // langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/internal/image/ImageFileTest.java
  private static final Set<String> METHODS_SOURCE_70 =
      Set.of(
          "should_decode_base64_content",
          "should_default_mime_type_and_extension_when_not_provided",
          "should_map_jpeg_and_webp_extensions",
          "should_throw_for_invalid_base64",
          "should_throw_for_url_based_image",
          "should_throw_when_no_data");

  private static final Map<String, Set<String>> SOURCE_TO_METHODS = initSourceToMethods();

  private static Map<String, Set<String>> initSourceToMethods() {
    Map<String, Set<String>> map = new HashMap<>();

    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiAudioTranscriptionModelIT.java",
        METHODS_SOURCE_1);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiCachingProxyIT.java",
        METHODS_SOURCE_2);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiChatModelAsyncRetryTest.java",
        METHODS_SOURCE_3);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiChatModelAsyncTest.java",
        METHODS_SOURCE_4);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiChatModelErrorsTest.java",
        METHODS_SOURCE_5);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiChatModelIT.java",
        METHODS_SOURCE_6);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiChatModelListenerIT.java",
        METHODS_SOURCE_7);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiChatModelNonBlockingIT.java",
        METHODS_SOURCE_8);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiChatModelWithJsonSchemaIT.java",
        METHODS_SOURCE_9);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiChatRequestParametersTest.java",
        METHODS_SOURCE_10);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiChatRequestTest.java",
        METHODS_SOURCE_11);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiChatResponseMetadataTest.java",
        METHODS_SOURCE_12);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiCustomHeadersSupplierTest.java",
        METHODS_SOURCE_13);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiEmbeddingModelAsyncRetryTest.java",
        METHODS_SOURCE_14);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiEmbeddingModelAsyncTest.java",
        METHODS_SOURCE_15);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiEmbeddingModelIT.java",
        METHODS_SOURCE_16);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiEmbeddingModelTest.java",
        METHODS_SOURCE_17);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiEmbeddingRequestParametersTest.java",
        METHODS_SOURCE_18);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiImageModelIT.java",
        METHODS_SOURCE_19);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiImageModelRetryTest.java",
        METHODS_SOURCE_20);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiLanguageModelIT.java",
        METHODS_SOURCE_21);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiModerationModelIT.java",
        METHODS_SOURCE_22);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiModerationModelListenerIT.java",
        METHODS_SOURCE_23);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiResponsesChatModelNonBlockingIT.java",
        METHODS_SOURCE_24);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiResponsesChatRequestParametersTest.java",
        METHODS_SOURCE_25);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiResponsesCustomHeadersTest.java",
        METHODS_SOURCE_26);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiResponsesFinishReasonTest.java",
        METHODS_SOURCE_27);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiResponsesStreamingChatModelPayloadTest.java",
        METHODS_SOURCE_28);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiResponsesStreamingChatModelPublisherTckTest.java",
        METHODS_SOURCE_29);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiResponsesStreamingChatModelRawEventTest.java",
        METHODS_SOURCE_30);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiResponsesStreamingEventParsingTest.java",
        METHODS_SOURCE_31);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiStreamingChatModelErrorsTest.java",
        METHODS_SOURCE_32);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiStreamingChatModelIT.java",
        METHODS_SOURCE_33);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiStreamingChatModelListenerIT.java",
        METHODS_SOURCE_34);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiStreamingChatModelPublisherCancellationTest.java",
        METHODS_SOURCE_35);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiStreamingChatModelPublisherListenerTest.java",
        METHODS_SOURCE_36);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiStreamingChatModelPublisherTckTest.java",
        METHODS_SOURCE_37);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiStreamingChatModelRawEventTest.java",
        METHODS_SOURCE_38);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiStreamingLanguageModelIT.java",
        METHODS_SOURCE_39);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiStreamingResponseBuilderTest.java",
        METHODS_SOURCE_40);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiTextToSpeechModelIT.java",
        METHODS_SOURCE_41);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiTextToSpeechModelTest.java",
        METHODS_SOURCE_42);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiTokenCountEstimatorIT.java",
        METHODS_SOURCE_43);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiTokenCountEstimatorTest.java",
        METHODS_SOURCE_44);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiTokenUsageTest.java",
        METHODS_SOURCE_45);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/OpenAiToolSpecificationIT.java",
        METHODS_SOURCE_46);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/common/OpenAiChatModelIT.java",
        METHODS_SOURCE_47);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/common/OpenAiEmbeddingModelIT.java",
        METHODS_SOURCE_48);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/common/OpenAiListenableChatModelIT.java",
        METHODS_SOURCE_49);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/common/OpenAiModelCatalogIT.java",
        METHODS_SOURCE_50);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/common/OpenAiStreamingChatModelIT.java",
        METHODS_SOURCE_51);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/common/responses/OpenAiResponsesChatModelIT.java",
        METHODS_SOURCE_52);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/common/responses/OpenAiResponsesChatModelListenerIT.java",
        METHODS_SOURCE_53);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/common/responses/OpenAiResponsesChatModelThinkingIT.java",
        METHODS_SOURCE_54);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/common/responses/OpenAiResponsesDynamicToolIT.java",
        METHODS_SOURCE_55);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/common/responses/OpenAiResponsesStreamingChatModelIT.java",
        METHODS_SOURCE_56);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/common/responses/OpenAiResponsesStreamingChatModelListenerIT.java",
        METHODS_SOURCE_57);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/common/responses/OpenAiResponsesStreamingChatModelThinkingIT.java",
        METHODS_SOURCE_58);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/compatible/deepseek/OpenAiChatModelDeepSeekThinkingIT.java",
        METHODS_SOURCE_59);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/compatible/deepseek/OpenAiStreamingChatModelDeepSeekIT.java",
        METHODS_SOURCE_60);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/compatible/deepseek/OpenAiStreamingChatModelDeepSeekThinkingIT.java",
        METHODS_SOURCE_61);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/internal/ChatCompletionResponseDeserializeTest.java",
        METHODS_SOURCE_62);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/internal/OpenAiBuilderCreatorParityTest.java",
        METHODS_SOURCE_63);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/internal/OpenAiImageContentFormatTest.java",
        METHODS_SOURCE_64);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/internal/OpenAiUtilsTest.java",
        METHODS_SOURCE_65);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/internal/StreamingRequestExecutorTest.java",
        METHODS_SOURCE_66);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/internal/WireFormatTest.java",
        METHODS_SOURCE_67);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/internal/chat/VideoContentConversionTest.java",
        METHODS_SOURCE_68);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/internal/embedding/EmbeddingDeserializationTest.java",
        METHODS_SOURCE_69);
    map.put(
        "langchain4j-open-ai/src/test/java/dev/langchain4j/model/openai/internal/image/ImageFileTest.java",
        METHODS_SOURCE_70);
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
    JsonNode root;
    try (InputStream in = getClass().getResourceAsStream("upstream-test-manifest.json")) {
      assertNotNull(in, "upstream-test-manifest.json must exist in test resources");
      root = MAPPER.readTree(in);
    }

    String commit = root.get("upstreamCommit").asText();
    assertEquals(
        REQUIRED_UPSTREAM_COMMIT, commit, "upstreamCommit must match LangChain4j 1.20.0 commit");

    String version = root.get("upstreamVersion").asText();
    assertEquals(
        REQUIRED_UPSTREAM_VERSION, version, "upstreamVersion must match LangChain4j 1.20.0");

    JsonNode summary = root.get("summary");
    assertNotNull(summary, "summary object must exist");
    assertEquals(EXPECTED_TOTAL_SOURCES, summary.get("totalSourceFiles").asInt());
    assertEquals(EXPECTED_TOTAL_METHODS, summary.get("totalMethods").asInt());
    assertEquals(EXPECTED_TOTAL_INVOCATIONS, summary.get("totalInvocations").asInt());
    assertEquals(EXPECTED_PORTED_INVOCATIONS, summary.get("portedInvocations").asInt());
    assertEquals(
        EXPECTED_IN_SCOPE_PENDING_INVOCATIONS, summary.get("inScopePendingInvocations").asInt());
    assertEquals(EXPECTED_OUT_OF_SCOPE_INVOCATIONS, summary.get("outOfScopeInvocations").asInt());
    assertEquals(
        EXPECTED_REAL_CREDENTIAL_INVOCATIONS, summary.get("realCredentialInvocations").asInt());

    JsonNode methodsNode = root.get("methods");
    assertTrue(methodsNode != null && methodsNode.isArray(), "methods must be an array");

    Set<String> seenSources = new HashSet<>();
    Map<String, Set<String>> seenMethodsPerSource = new HashMap<>();
    Set<String> seenInvocationIds = new HashSet<>();

    int countedMethods = 0;
    int countedInvocations = 0;
    int countedPortedInvocations = 0;
    int countedInScopePendingInvocations = 0;
    int countedOutOfScopeInvocations = 0;
    int countedRealCredentialInvocations = 0;

    for (JsonNode methodEntry : methodsNode) {
      countedMethods++;
      String sourcePath = methodEntry.get("sourcePath").asText();
      String className = methodEntry.get("className").asText();
      String methodName = methodEntry.get("methodName").asText();

      seenSources.add(sourcePath);
      seenMethodsPerSource.computeIfAbsent(sourcePath, k -> new HashSet<>()).add(methodName);

      assertTrue(
          SOURCE_TO_METHODS.containsKey(sourcePath), () -> "unexpected sourcePath: " + sourcePath);
      assertTrue(
          SOURCE_TO_METHODS.get(sourcePath).contains(methodName),
          () -> "unexpected methodName '" + methodName + "' for sourcePath: " + sourcePath);

      JsonNode invocations = methodEntry.get("invocations");
      assertTrue(invocations != null && invocations.isArray() && !invocations.isEmpty());

      Set<String> seenParamCasesInMethod = new HashSet<>();
      for (JsonNode inv : invocations) {
        countedInvocations++;
        String invocationId = inv.get("invocationId").asText();
        assertNotNull(invocationId);
        assertTrue(
            seenInvocationIds.add(invocationId), () -> "duplicate invocationId: " + invocationId);

        assertEquals(sourcePath, inv.get("sourcePath").asText());
        assertEquals(className, inv.get("className").asText());
        assertEquals(methodName, inv.get("methodName").asText());

        String parameterCase = inv.get("parameterCase").asText();
        assertNotNull(parameterCase);
        assertTrue(seenParamCasesInMethod.add(parameterCase));

        String upstreamStatus = inv.get("upstreamStatus").asText();
        assertTrue(Set.of("ACTIVE", "DISABLED", "CONDITIONAL").contains(upstreamStatus));

        String capability = inv.get("capability").asText();
        assertNotNull(capability);
        assertFalse(capability.isBlank());

        String validationLayer = inv.get("validationLayer").asText();
        assertNotNull(validationLayer);
        assertFalse(validationLayer.isBlank());

        String mappingStatus = inv.get("mappingStatus").asText();
        String executionStatus = inv.get("executionStatus").asText();
        assertFalse("SKIPPED".equals(executionStatus));

        JsonNode realInterop = inv.get("realInteropStatus");
        if (realInterop != null && !realInterop.isNull()) {
          assertEquals("NOT_EXECUTED_REQUIRES_CREDENTIAL", realInterop.asText());
          countedRealCredentialInvocations++;
        }

        if ("PORTED".equals(mappingStatus)) {
          countedPortedInvocations++;
          assertEquals("PASSED", executionStatus);
          assertTrue(
              inv.get("capabilityMismatch") == null || inv.get("capabilityMismatch").isNull());
          assertNotNull(inv.get("targetTest"));
          validateTargetTest(inv.get("targetTest").asText());
          assertNotNull(inv.get("fixture"));
          validateFixture(inv.get("fixture").asText());
          assertFalse("not-applicable".equals(validationLayer));
        } else if ("PORT_PENDING".equals(mappingStatus)) {
          countedInScopePendingInvocations++;
          assertEquals("NOT_EXECUTED_PENDING_IMPLEMENTATION", executionStatus);
          assertTrue(
              inv.get("capabilityMismatch") == null || inv.get("capabilityMismatch").isNull());
          assertNotNull(inv.get("targetTest"));
          assertFalse(inv.get("targetTest").asText().isBlank());
          assertNotNull(inv.get("fixture"));
          assertFalse(inv.get("fixture").asText().isBlank());
          assertFalse("not-applicable".equals(validationLayer));
        } else if ("OUT_OF_SCOPE".equals(mappingStatus)) {
          countedOutOfScopeInvocations++;
          assertEquals("NOT_EXECUTED_OUT_OF_SCOPE", executionStatus);
          JsonNode mismatch = inv.get("capabilityMismatch");
          assertNotNull(mismatch);
          assertFalse(mismatch.isNull() || mismatch.asText().isBlank());
          assertTrue(inv.get("targetTest") == null || inv.get("targetTest").isNull());
          assertTrue(inv.get("fixture") == null || inv.get("fixture").isNull());
          assertEquals("not-applicable", validationLayer);
        } else {
          throw new AssertionError("unexpected mappingStatus: " + mappingStatus);
        }
      }
    }

    assertEquals(EXPECTED_TOTAL_SOURCES, seenSources.size());
    assertEquals(EXPECTED_TOTAL_METHODS, countedMethods);
    assertEquals(EXPECTED_TOTAL_INVOCATIONS, countedInvocations);
    assertEquals(EXPECTED_PORTED_INVOCATIONS, countedPortedInvocations);
    assertEquals(EXPECTED_IN_SCOPE_PENDING_INVOCATIONS, countedInScopePendingInvocations);
    assertEquals(EXPECTED_OUT_OF_SCOPE_INVOCATIONS, countedOutOfScopeInvocations);
    assertEquals(EXPECTED_REAL_CREDENTIAL_INVOCATIONS, countedRealCredentialInvocations);

    for (Map.Entry<String, Set<String>> entry : SOURCE_TO_METHODS.entrySet()) {
      Set<String> seenInSource = seenMethodsPerSource.get(entry.getKey());
      assertNotNull(seenInSource, () -> "source missed in manifest: " + entry.getKey());
      assertEquals(
          entry.getValue().size(),
          seenInSource.size(),
          () -> "methods count mismatch for source: " + entry.getKey());
    }
  }

  @Disabled
  private static class DisabledClassFixture {
    @Test
    void dummyMethod() {}
  }

  private static class DisabledMethodFixture {
    @Disabled
    @Test
    void disabledMethod() {}
  }

  @Test
  void test_validateTargetTest_guards() {
    assertThrows(AssertionError.class, () -> validateTargetTest("malformed_no_hash"));
    assertThrows(AssertionError.class, () -> validateTargetTest("#method_only"));
    assertThrows(AssertionError.class, () -> validateTargetTest("Class#"));
    assertThrows(AssertionError.class, () -> validateTargetTest("Class#method#extra"));
    assertThrows(
        AssertionError.class, () -> validateTargetTest("non.existent." + "FakeClass#method"));
    assertThrows(
        AssertionError.class, () -> validateTargetTest(String.class.getName() + "#length"));
    assertThrows(
        AssertionError.class,
        () ->
            validateTargetTest(
                "fun.fengwk.kkstudio.harness.provider."
                    + "transport.UpstreamTestManifestTest#test"));
    assertThrows(
        AssertionError.class,
        () -> validateTargetTest(UpstreamTestManifestTest.class.getName() + "#nonExistentMethod"));
    assertThrows(
        AssertionError.class,
        () -> validateTargetTest(UpstreamTestManifestTest.class.getName() + "#validateTargetTest"));
    assertThrows(
        AssertionError.class,
        () -> validateTargetTest(DisabledClassFixture.class.getName() + "#dummyMethod"));
    assertThrows(
        AssertionError.class,
        () -> validateTargetTest(DisabledMethodFixture.class.getName() + "#disabledMethod"));
  }

  @Test
  void test_validateFixture_guards() {
    assertThrows(AssertionError.class, () -> validateFixture(null));
    assertThrows(AssertionError.class, () -> validateFixture(""));
    assertThrows(AssertionError.class, () -> validateFixture("   "));
    assertThrows(
        AssertionError.class,
        () -> validateFixture("/openai/chat/fixtures/create-chat-completion.json"));
    assertThrows(
        AssertionError.class, () -> validateFixture("other/fixtures/create-chat-completion.json"));
    assertThrows(
        AssertionError.class,
        () -> validateFixture("openai/chat/fixtures/../create-chat-completion.json"));
    assertThrows(
        AssertionError.class,
        () -> validateFixture("openai/chat/fixtures/sub/../../create-chat-completion.json"));
    assertThrows(
        AssertionError.class,
        () -> validateFixture("openai/chat/fixtures/non_existent_fixture.json"));
    assertThrows(
        AssertionError.class,
        () -> validateFixture("openai/chat/fixtures/non_existent_stream.sse"));
  }
}
