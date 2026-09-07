package fun.fengwk.kkstudio.harness.provider.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 校验 upstream-test-manifest.json 的机器可读性、真实上游 Inventory 覆盖与自包含性。
 *
 * <p>保证 LangChain4j 1.20.0（提交 3a2f4dca6fb447e4d191624b3d588952ed9f4ce9）在 shared HTTP 与 JDK 客户端范围内的
 * 15 个源文件、共计 115 个真实 active 测试方法（包含 Reactive Streams TCK 38 项方法及全部参数化 invocation 维度）全量可审计：
 *
 * <ul>
 *   <li>总测试数严格为 115，其中 51 项 PORTED/PASSED，64 项 OUT_OF_SCOPE。
 *   <li>所有 PORTED 项均映射到本地真实存在的测试方法，且绝对不得标注 @Disabled。
 *   <li>所有 OUT_OF_SCOPE 项均详述具体能力不匹配（capability mismatch），其 localClass/localMethod 严格为 null，绝不伪装通过。
 *   <li>拒绝任何缺失、虚构上游方法或重复 caseId。
 * </ul>
 */
class UpstreamTestManifestTest {

  private static final String REQUIRED_UPSTREAM_COMMIT = "3a2f4dca6fb447e4d191624b3d588952ed9f4ce9";

  private static final int EXPECTED_TOTAL_CASES = 115;
  private static final int EXPECTED_PORTED_CASES = 51;
  private static final int EXPECTED_OUT_OF_SCOPE_CASES = 64;

  private static final Set<String> EXACT_EXPECTED_SOURCES =
      Set.of(
          "langchain4j-http-client/src/test/java/dev/langchain4j/http/client/sse/DefaultServerSentEventParserTest.java",
          "langchain4j-http-client/src/test/java/dev/langchain4j/http/client/HttpClientIT.java",
          "http-clients/langchain4j-http-client-jdk/src/test/java/dev/langchain4j/http/client/jdk/JdkHttpClientIT.java",
          "langchain4j-http-client/src/test/java/dev/langchain4j/http/client/HttpClientCancellationIT.java",
          "http-clients/langchain4j-http-client-jdk/src/test/java/dev/langchain4j/http/client/jdk/JdkHttpClientCancellationIT.java",
          "langchain4j-http-client/src/test/java/dev/langchain4j/http/client/HttpClientTimeoutIT.java",
          "http-clients/langchain4j-http-client-jdk/src/test/java/dev/langchain4j/http/client/jdk/JdkHttpClientTimeoutIT.java",
          "http-clients/langchain4j-http-client-jdk/src/test/java/dev/langchain4j/http/client/jdk/JdkHttpClientErrorBodyTest.java",
          "http-clients/langchain4j-http-client-jdk/src/test/java/dev/langchain4j/http/client/jdk/JdkHttpClientStreamOverflowTest.java",
          "langchain4j-http-client/src/test/java/dev/langchain4j/http/client/HttpClientPublisherIT.java",
          "http-clients/langchain4j-http-client-jdk/src/test/java/dev/langchain4j/http/client/jdk/JdkHttpClientPublisherIT.java",
          "langchain4j-http-client/src/test/java/dev/langchain4j/http/client/AbstractHttpClientPublisherNonBlockingIT.java",
          "http-clients/langchain4j-http-client-jdk/src/test/java/dev/langchain4j/http/client/jdk/JdkHttpClientPublisherNonBlockingIT.java",
          "http-clients/langchain4j-http-client-jdk/src/test/java/dev/langchain4j/http/client/jdk/HttpStreamingEventPublisherTckTest.java",
          "http-clients/langchain4j-http-client-jdk/src/test/java/dev/langchain4j/http/client/jdk/MultipartBodyPublisherTest.java");

  private static final Set<String> PARSER_METHODS =
      Set.of(
          "shouldParseSimpleSingleLineEvent",
          "shouldParseMultiLineDataEvent",
          "shouldParseEventWithAllFields",
          "shouldParseMultipleEvents",
          "shouldIgnoreCommentsAndEmptyLines",
          "shouldHandleStreamWithNoEvents",
          "shouldPreserveAdditionalLeadingWhitespaceInData",
          "shouldPreserveTrailingWhitespaceInData",
          "shouldNotRemoveAnyCharacterWhenDataHasNoLeadingSpace",
          "shouldHandleIOException",
          "parse_stops_emitting_after_the_listener_cancels",
          "parse_handles_cr_and_crlf_line_endings",
          "parse_trims_event_field_and_strips_one_leading_space_from_data",
          "incremental_trims_event_field_and_strips_one_leading_space_from_data",
          "a_parser_that_does_not_support_incremental_parsing_reports_it_as_not_async",
          "incremental_parses_single_event_in_one_chunk",
          "incremental_parses_event_split_across_chunks",
          "incremental_handles_crlf_line_endings",
          "incremental_parses_multiple_events",
          "incremental_joins_multiline_data",
          "incremental_ignores_comment_id_and_retry_lines",
          "incremental_flush_emits_trailing_event_without_terminating_blank_line",
          "incremental_flush_completes_a_pending_partial_line",
          "incremental_flush_returns_empty_when_nothing_is_pending",
          "incremental_decodes_utf8_multibyte_char_split_across_chunks");

  private static final Set<String> HTTP_IT_METHODS =
      Set.of(
          "should_return_successful_http_response",
          "should_deliver_response_off_the_calling_thread_executeAsync",
          "should_throw_400",
          "should_throw_401",
          "should_stream_successful_response",
          "should_cancel_streaming",
          "should_stream_response_with_double_newline",
          "should_deliver_error_when_streaming_400",
          "should_not_fail_when_listener_onOpen_throws_exception",
          "should_not_fail_when_listener_onEvent_throws_exception",
          "should_not_fail_when_listener_onError_throws_exception",
          "should_deliver_error_when_streaming_connect_fails",
          "should_return_successful_http_response_form_data",
          "should_return_binary_response_sync");

  private static final Set<String> CANCELLATION_IT_METHODS =
      Set.of(
          "cancelling_the_future_releases_the_caller",
          "cancelling_the_future_aborts_the_request_and_closes_the_connection");

  private static final Set<String> TIMEOUT_IT_METHODS =
      Set.of("should_timeout_on_read_sync", "should_timeout_on_read_async");

  private static final Set<String> ERROR_BODY_METHODS =
      Set.of(
          "should_preserve_line_separators_of_error_response_body",
          "should_decode_error_response_body_as_utf8",
          "should_not_fail_on_successful_response");

  private static final Set<String> STREAM_OVERFLOW_METHODS =
      Set.of("overflowing_the_stream_buffer_aborts_the_request_and_closes_the_connection");

  private static final Set<String> PUBLISHER_IT_METHODS =
      Set.of("publisher_is_cold_and_each_subscribe_initiates_a_new_request");

  private static final Set<String> NON_BLOCKING_IT_METHODS =
      Set.of(
          "publisher_path_does_not_block_the_transport_threads",
          "blockHound_detects_blocking_on_a_policed_thread");

  private static final Set<String> TCK_METHODS =
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
          "required_createPublisher3MustProduceAStreamOfExactly3Elements",
          "required_spec101_subscriptionRequestMustResultInTheCorrectNumberOfProducedElements",
          "required_spec102_maySignalLessThanRequestedAndTerminateSubscription",
          "required_spec105_mustSignalOnCompleteWhenFiniteStreamTerminates",
          "required_spec107_mustNotEmitFurtherSignalsOnceOnCompleteHasBeenSignalled",
          "required_spec109_mayRejectCallsToSubscribeIfPublisherIsUnableOrUnwillingToServeThemRejectionMustTriggerOnErrorAfterOnSubscribe",
          "required_spec109_mustIssueOnSubscribeForNonNullSubscriber",
          "required_spec109_subscribeThrowNPEOnNullSubscriber",
          "required_spec302_mustAllowSynchronousRequestCallsFromOnNextAndOnSubscribe",
          "required_spec303_mustNotAllowUnboundedRecursion",
          "required_spec306_afterSubscriptionIsCancelledRequestMustBeNops",
          "required_spec307_afterSubscriptionIsCancelledAdditionalCancelationsMustBeNops",
          "required_spec309_requestNegativeNumberMustSignalIllegalArgumentException",
          "required_spec309_requestZeroMustSignalIllegalArgumentException",
          "required_spec312_cancelMustMakeThePublisherToEventuallyStopSignaling",
          "required_spec313_cancelMustMakeThePublisherEventuallyDropAllReferencesToTheSubscriber",
          "required_spec317_mustNotSignalOnErrorWhenPendingAboveLongMaxValue",
          "required_spec317_mustSupportACumulativePendingElementCountUpToLongMaxValue",
          "required_spec317_mustSupportAPendingElementCountUpToLongMaxValue",
          "required_validate_boundedDepthOfOnNextAndRequestRecursion",
          "required_validate_maxElementsFromPublisher",
          "stochastic_spec103_mustSignalOnMethodsSequentially",
          "untested_spec106_mustConsiderSubscriptionCancelledAfterOnErrorOrOnCompleteHasBeenCalled",
          "untested_spec107_mustNotEmitFurtherSignalsOnceOnErrorHasBeenSignalled",
          "untested_spec108_possiblyCanceledSubscriptionShouldNotReceiveOnErrorOrOnCompleteSignals",
          "untested_spec109_subscribeShouldNotThrowNonFatalThrowable",
          "untested_spec110_rejectASubscriptionRequestIfTheSameSubscriberSubscribesTwice",
          "untested_spec304_requestShouldNotPerformHeavyComputations",
          "untested_spec305_cancelMustNotSynchronouslyPerformHeavyComputation");

  private static final Set<String> MULTIPART_METHODS =
      Set.of(
          "should_build_body_with_single_form_field",
          "should_build_body_with_file",
          "should_build_body_with_field_then_file",
          "should_omit_content_type_header_when_content_type_is_null",
          "should_omit_content_type_header_when_content_type_is_blank",
          "content_type_should_carry_the_boundary_used_in_the_body");

  private static final Map<String, Set<String>> SOURCE_TO_METHODS =
      Map.ofEntries(
          Map.entry(
              "langchain4j-http-client/src/test/java/dev/langchain4j/http/client/sse/DefaultServerSentEventParserTest.java",
              PARSER_METHODS),
          Map.entry(
              "langchain4j-http-client/src/test/java/dev/langchain4j/http/client/HttpClientIT.java",
              HTTP_IT_METHODS),
          Map.entry(
              "http-clients/langchain4j-http-client-jdk/src/test/java/dev/langchain4j/http/client/jdk/JdkHttpClientIT.java",
              HTTP_IT_METHODS),
          Map.entry(
              "langchain4j-http-client/src/test/java/dev/langchain4j/http/client/HttpClientCancellationIT.java",
              CANCELLATION_IT_METHODS),
          Map.entry(
              "http-clients/langchain4j-http-client-jdk/src/test/java/dev/langchain4j/http/client/jdk/JdkHttpClientCancellationIT.java",
              CANCELLATION_IT_METHODS),
          Map.entry(
              "langchain4j-http-client/src/test/java/dev/langchain4j/http/client/HttpClientTimeoutIT.java",
              TIMEOUT_IT_METHODS),
          Map.entry(
              "http-clients/langchain4j-http-client-jdk/src/test/java/dev/langchain4j/http/client/jdk/JdkHttpClientTimeoutIT.java",
              TIMEOUT_IT_METHODS),
          Map.entry(
              "http-clients/langchain4j-http-client-jdk/src/test/java/dev/langchain4j/http/client/jdk/JdkHttpClientErrorBodyTest.java",
              ERROR_BODY_METHODS),
          Map.entry(
              "http-clients/langchain4j-http-client-jdk/src/test/java/dev/langchain4j/http/client/jdk/JdkHttpClientStreamOverflowTest.java",
              STREAM_OVERFLOW_METHODS),
          Map.entry(
              "langchain4j-http-client/src/test/java/dev/langchain4j/http/client/HttpClientPublisherIT.java",
              PUBLISHER_IT_METHODS),
          Map.entry(
              "http-clients/langchain4j-http-client-jdk/src/test/java/dev/langchain4j/http/client/jdk/JdkHttpClientPublisherIT.java",
              PUBLISHER_IT_METHODS),
          Map.entry(
              "langchain4j-http-client/src/test/java/dev/langchain4j/http/client/AbstractHttpClientPublisherNonBlockingIT.java",
              NON_BLOCKING_IT_METHODS),
          Map.entry(
              "http-clients/langchain4j-http-client-jdk/src/test/java/dev/langchain4j/http/client/jdk/JdkHttpClientPublisherNonBlockingIT.java",
              NON_BLOCKING_IT_METHODS),
          Map.entry(
              "http-clients/langchain4j-http-client-jdk/src/test/java/dev/langchain4j/http/client/jdk/HttpStreamingEventPublisherTckTest.java",
              TCK_METHODS),
          Map.entry(
              "http-clients/langchain4j-http-client-jdk/src/test/java/dev/langchain4j/http/client/jdk/MultipartBodyPublisherTest.java",
              MULTIPART_METHODS));

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

    // 校验 inventorySources 全集
    JsonNode inventorySources = root.get("inventorySources");
    assertNotNull(inventorySources, "inventorySources must be present in manifest");
    Set<String> manifestSources = new HashSet<>();
    for (JsonNode srcNode : inventorySources) {
      manifestSources.add(srcNode.asText());
    }
    assertEquals(
        EXACT_EXPECTED_SOURCES,
        manifestSources,
        "inventorySources must exactly match the 15 upstream sources");

    // 校验统计字段
    JsonNode stats = root.get("statistics");
    assertNotNull(stats, "statistics must be present in manifest");
    assertEquals(EXPECTED_TOTAL_CASES, stats.get("totalCases").asInt());
    assertEquals(EXPECTED_PORTED_CASES, stats.get("portedPassedCases").asInt());
    assertEquals(EXPECTED_OUT_OF_SCOPE_CASES, stats.get("outOfScopeCases").asInt());

    JsonNode cases = root.get("cases");
    assertTrue(cases.isArray(), "cases must be a JSON array");
    assertEquals(EXPECTED_TOTAL_CASES, cases.size(), "cases count must exactly match 115");

    Set<String> seenCaseIds = new HashSet<>();
    int countedPorted = 0;
    int countedOutOfScope = 0;

    for (JsonNode item : cases) {
      String caseId = item.get("caseId").asText();
      assertTrue(seenCaseIds.add(caseId), "caseId must be unique: " + caseId);

      String upstreamSource = item.get("upstreamSource").asText();
      assertTrue(
          EXACT_EXPECTED_SOURCES.contains(upstreamSource),
          "case upstreamSource must belong to inventorySources: " + upstreamSource);

      String upstreamMethod = item.get("upstreamMethod").asText();
      Set<String> validMethods = SOURCE_TO_METHODS.get(upstreamSource);
      assertNotNull(validMethods, "valid methods definition must exist for: " + upstreamSource);
      assertTrue(
          validMethods.contains(upstreamMethod),
          () ->
              "upstreamMethod '"
                  + upstreamMethod
                  + "' must actually exist in source '"
                  + upstreamSource
                  + "'; fictitious methods are prohibited");

      int invCount = item.get("invocationCount").asInt();
      assertTrue(invCount >= 1, "invocationCount must be >= 1 for case: " + caseId);
      JsonNode invDims = item.get("invocationDimensions");
      assertNotNull(invDims, "invocationDimensions must be specified for case: " + caseId);
      assertTrue(invDims.isArray() && invDims.size() > 0, "invocationDimensions must not be empty");

      String status = item.get("status").asText();
      String executionStatus = item.get("executionStatus").asText();

      if ("PORTED".equals(status)) {
        countedPorted++;
        assertEquals("PASSED", executionStatus, "PORTED case must have PASSED executionStatus");
        assertTrue(
            item.get("capabilityMismatch") == null || item.get("capabilityMismatch").isNull(),
            "PORTED case must not have capabilityMismatch");

        String localClass = item.get("localClass").asText();
        String localMethod = item.get("localMethod").asText();
        assertNotNull(localClass, "PORTED case must have non-null localClass: " + caseId);
        assertNotNull(localMethod, "PORTED case must have non-null localMethod: " + caseId);

        // 验证本地测试类与方法真实存在且未被禁用
        Class<?> clazz = Class.forName(localClass);
        assertNotNull(clazz, "local test class must be loadable: " + localClass);

        Method method =
            Arrays.stream(clazz.getDeclaredMethods())
                .filter(m -> m.getName().equals(localMethod))
                .findFirst()
                .orElse(null);

        assertNotNull(
            method, () -> "local test method " + localMethod + " must exist in " + localClass);

        assertFalse(
            method.isAnnotationPresent(Disabled.class),
            () -> "local test method " + localMethod + " must not be annotated with @Disabled");
      } else if ("OUT_OF_SCOPE".equals(status)) {
        countedOutOfScope++;
        assertEquals(
            "OUT_OF_SCOPE",
            executionStatus,
            "OUT_OF_SCOPE case must have OUT_OF_SCOPE executionStatus");
        JsonNode mismatchNode = item.get("capabilityMismatch");
        assertNotNull(mismatchNode, "OUT_OF_SCOPE case must specify capabilityMismatch");
        assertFalse(
            mismatchNode.isNull() || mismatchNode.asText().isBlank(),
            "OUT_OF_SCOPE case must provide non-blank capabilityMismatch explanation");

        // 严格断言：OUT_OF_SCOPE 项禁止指向无关本地测试或伪装通过
        assertTrue(
            item.get("localClass") == null || item.get("localClass").isNull(),
            "OUT_OF_SCOPE case must have null localClass: " + caseId);
        assertTrue(
            item.get("localMethod") == null || item.get("localMethod").isNull(),
            "OUT_OF_SCOPE case must have null localMethod: " + caseId);
      } else {
        throw new AssertionError("Unknown case status: " + status);
      }
    }

    assertEquals(EXPECTED_PORTED_CASES, countedPorted, "ported cases count must be 51");
    assertEquals(
        EXPECTED_OUT_OF_SCOPE_CASES, countedOutOfScope, "out of scope cases count must be 64");
  }
}
