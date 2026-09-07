package fun.fengwk.kkstudio.harness.provider.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 校验 upstream-test-manifest.json 的机器可读性、真实上游 Inventory 覆盖与自包含性。
 *
 * <p>保证 LangChain4j 1.20.0（提交 3a2f4dca6fb447e4d191624b3d588952ed9f4ce9）在 shared HTTP 与 JDK 客户端范围内的
 * 15 个源文件、共计 115 个真实 active 测试方法、按 invocation 展开共 141 个 invocation 全量可审计：
 *
 * <ul>
 *   <li>精确校验 (source, method) 全集 115 项，拒绝虚构、重复或遗漏。
 *   <li>按 invocation 维度独立记录 disposition：49 项 PORTED/PASSED，92 项 NOT_EXECUTED_OUT_OF_SCOPE。
 *   <li>逐方法机械校验参数键和值（包括 Parser 7 个字符串、ExecutionMode SYNC/ASYNC、StreamingMode
 *       LISTENER/PUBLISHER、NonBlocking logging false/true、非参数化空参数）。
 *   <li>所有 PORTED invocation 均映射到本地真实存在的测试方法，必须标注 @Test 或 @ParameterizedTest，且绝对不得标注 @Disabled。
 *   <li>所有 OUT_OF_SCOPE invocation 均详述具体能力不匹配（capability mismatch），其 localClass/localMethod 严格为
 *       null。
 *   <li>所有统计指标在测试中由代码从内容动态计算断言，不单纯信任外部自报。
 * </ul>
 */
class UpstreamTestManifestTest {

  private static final String REQUIRED_UPSTREAM_COMMIT = "3a2f4dca6fb447e4d191624b3d588952ed9f4ce9";

  private static final int EXPECTED_TOTAL_METHODS = 115;
  private static final int EXPECTED_TOTAL_INVOCATIONS = 141;
  private static final int EXPECTED_PORTED_INVOCATIONS = 49;
  private static final int EXPECTED_OUT_OF_SCOPE_INVOCATIONS = 92;
  private static final int EXPECTED_PORTED_METHODS = 43;
  private static final int EXPECTED_PURE_OUT_OF_SCOPE_METHODS = 72;

  private static final List<String> PARSER_SINGLE_LINE_INPUTS =
      List.of(
          "data: Simple message",
          "data: Simple message\n",
          "\ndata: Simple message",
          "\ndata: Simple message\n",
          "\n\ndata: Simple message",
          "data: Simple message\n\n",
          "\n\ndata: Simple message\n\n");

  private static final Set<String> EXECUTION_MODE_METHODS =
      Set.of(
          "should_return_successful_http_response",
          "should_throw_400",
          "should_throw_401",
          "should_return_successful_http_response_form_data");

  private static final Set<String> STREAMING_MODE_METHODS =
      Set.of(
          "should_stream_successful_response",
          "should_cancel_streaming",
          "should_stream_response_with_double_newline",
          "should_deliver_error_when_streaming_400",
          "should_deliver_error_when_streaming_connect_fails");

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

    // 校验自报 summary（若存在）
    if (root.has("summary")) {
      JsonNode summary = root.get("summary");
      assertEquals(EXPECTED_TOTAL_METHODS, summary.get("totalMethods").asInt());
      assertEquals(EXPECTED_TOTAL_INVOCATIONS, summary.get("totalInvocations").asInt());
      assertEquals(EXPECTED_PORTED_INVOCATIONS, summary.get("portedInvocations").asInt());
      assertEquals(EXPECTED_OUT_OF_SCOPE_INVOCATIONS, summary.get("outOfScopeInvocations").asInt());
      assertEquals(EXPECTED_PORTED_METHODS, summary.get("portedMethods").asInt());
      assertEquals(
          EXPECTED_PURE_OUT_OF_SCOPE_METHODS, summary.get("pureOutOfScopeMethods").asInt());
    }

    JsonNode methods = root.get("methods");
    assertTrue(methods.isArray(), "methods must be a JSON array");

    Set<String> seenCaseIds = new HashSet<>();
    Set<String> seenInvocationIds = new HashSet<>();
    Set<String> seenSourceMethodPairs = new HashSet<>();

    int countedMethods = 0;
    int countedInvocations = 0;
    int countedPortedInvocations = 0;
    int countedOutOfScopeInvocations = 0;
    int countedPortedMethods = 0;
    int countedPureOutOfScopeMethods = 0;

    for (JsonNode methodNode : methods) {
      countedMethods++;
      String caseId = methodNode.get("caseId").asText();
      assertTrue(seenCaseIds.add(caseId), "caseId must be unique: " + caseId);

      String source = methodNode.get("source").asText();
      assertTrue(
          EXACT_EXPECTED_SOURCES.contains(source),
          "method source must belong to exact expected 15 sources: " + source);

      String methodName = methodNode.get("method").asText();
      Set<String> validMethods = SOURCE_TO_METHODS.get(source);
      assertNotNull(validMethods, "valid methods definition must exist for: " + source);
      assertTrue(
          validMethods.contains(methodName),
          () -> "upstreamMethod '" + methodName + "' must actually exist in source: " + source);

      String pair = source + "#" + methodName;
      assertTrue(seenSourceMethodPairs.add(pair), "source and method pair must be unique: " + pair);

      JsonNode invocations = methodNode.get("invocations");
      assertTrue(
          invocations.isArray() && invocations.size() > 0, "invocations must be non-empty array");

      // 机械校验各方法的 invocation 数量及参数键值
      if ("shouldParseSimpleSingleLineEvent".equals(methodName)) {
        assertEquals(
            7,
            invocations.size(),
            "shouldParseSimpleSingleLineEvent must have exactly 7 invocations matching upstream @ValueSource");
        List<String> actualInputs = new ArrayList<>();
        for (JsonNode inv : invocations) {
          assertTrue(inv.get("parameters").has("input"), "must contain parameter 'input'");
          actualInputs.add(inv.get("parameters").get("input").asText());
        }
        assertEquals(
            PARSER_SINGLE_LINE_INPUTS,
            actualInputs,
            "parser @ValueSource inputs must match exactly");
      } else if (EXECUTION_MODE_METHODS.contains(methodName) && source.contains("HttpClientIT")) {
        assertEquals(
            2,
            invocations.size(),
            () ->
                "ExecutionMode parameterized method "
                    + pair
                    + " must define exactly 2 invocations (SYNC, ASYNC)");
        Set<String> modes = new HashSet<>();
        for (JsonNode inv : invocations) {
          assertTrue(
              inv.get("parameters").has("executionMode"), "must contain parameter 'executionMode'");
          modes.add(inv.get("parameters").get("executionMode").asText());
        }
        assertEquals(
            Set.of("SYNC", "ASYNC"), modes, "executionMode must be exactly SYNC and ASYNC");
      } else if (STREAMING_MODE_METHODS.contains(methodName) && source.contains("HttpClientIT")) {
        assertEquals(
            2,
            invocations.size(),
            () ->
                "StreamingMode parameterized method "
                    + pair
                    + " must define exactly 2 invocations (LISTENER, PUBLISHER)");
        Set<String> modes = new HashSet<>();
        for (JsonNode inv : invocations) {
          assertTrue(
              inv.get("parameters").has("streamingMode"), "must contain parameter 'streamingMode'");
          modes.add(inv.get("parameters").get("streamingMode").asText());
        }
        assertEquals(
            Set.of("LISTENER", "PUBLISHER"),
            modes,
            "streamingMode must be exactly LISTENER and PUBLISHER");
      } else if ("publisher_path_does_not_block_the_transport_threads".equals(methodName)) {
        assertEquals(
            2,
            invocations.size(),
            () ->
                "NonBlocking method "
                    + pair
                    + " must define exactly 2 invocations (logging=false/true)");
        Set<String> loggingVals = new HashSet<>();
        for (JsonNode inv : invocations) {
          assertTrue(inv.get("parameters").has("logging"), "must contain parameter 'logging'");
          loggingVals.add(inv.get("parameters").get("logging").asText());
        }
        assertEquals(
            Set.of("false", "true"), loggingVals, "logging must be exactly false and true");
      } else {
        assertEquals(
            1,
            invocations.size(),
            () -> "Non-parameterized method " + pair + " must define exactly 1 invocation");
        assertEquals(
            0,
            invocations.get(0).get("parameters").size(),
            () -> "Non-parameterized invocation must have empty parameters: " + pair);
      }

      boolean methodHasPorted = false;

      for (JsonNode inv : invocations) {
        countedInvocations++;
        String invocationId = inv.get("invocationId").asText();
        assertNotNull(invocationId, "invocationId must not be null");
        assertTrue(
            seenInvocationIds.add(invocationId),
            "invocationId must be globally unique: " + invocationId);

        String status = inv.get("status").asText();
        String executionStatus = inv.get("executionStatus").asText();

        if ("PORTED".equals(status)) {
          countedPortedInvocations++;
          methodHasPorted = true;
          assertEquals(
              "PASSED", executionStatus, "PORTED invocation must have PASSED executionStatus");
          assertTrue(
              inv.get("capabilityMismatch") == null || inv.get("capabilityMismatch").isNull(),
              "PORTED invocation must not have capabilityMismatch");

          String localClass = inv.get("localClass").asText();
          String localMethod = inv.get("localMethod").asText();
          assertNotNull(localClass, "PORTED invocation must have non-null localClass");
          assertNotNull(localMethod, "PORTED invocation must have non-null localMethod");

          // 反射验证本地测试真实存在、具有 @Test 或 @ParameterizedTest 注解且未被 @Disabled
          Class<?> clazz = Class.forName(localClass);
          assertNotNull(clazz, "local test class must be loadable: " + localClass);
          assertFalse(
              clazz.isAnnotationPresent(Disabled.class), "local test class must not be Disabled");

          Method targetMethod =
              Arrays.stream(clazz.getDeclaredMethods())
                  .filter(m -> m.getName().equals(localMethod))
                  .findFirst()
                  .orElse(null);

          assertNotNull(
              targetMethod,
              () -> "local test method " + localMethod + " must exist in " + localClass);

          boolean hasTestAnnotation =
              targetMethod.isAnnotationPresent(Test.class)
                  || targetMethod.isAnnotationPresent(ParameterizedTest.class);
          assertTrue(
              hasTestAnnotation,
              () ->
                  "local test method "
                      + localMethod
                      + " must be annotated with @Test or @ParameterizedTest");

          assertFalse(
              targetMethod.isAnnotationPresent(Disabled.class),
              () -> "local test method " + localMethod + " must not be annotated with @Disabled");
        } else if ("OUT_OF_SCOPE".equals(status)) {
          countedOutOfScopeInvocations++;
          assertEquals(
              "NOT_EXECUTED_OUT_OF_SCOPE",
              executionStatus,
              "OUT_OF_SCOPE invocation must strictly have NOT_EXECUTED_OUT_OF_SCOPE executionStatus");

          JsonNode mismatchNode = inv.get("capabilityMismatch");
          assertNotNull(mismatchNode, "OUT_OF_SCOPE invocation must specify capabilityMismatch");
          assertFalse(
              mismatchNode.isNull() || mismatchNode.asText().isBlank(),
              "OUT_OF_SCOPE invocation must provide non-blank capabilityMismatch explanation");

          assertTrue(
              inv.get("localClass") == null || inv.get("localClass").isNull(),
              "OUT_OF_SCOPE invocation must have null localClass");
          assertTrue(
              inv.get("localMethod") == null || inv.get("localMethod").isNull(),
              "OUT_OF_SCOPE invocation must have null localMethod");
        } else {
          throw new AssertionError("Unknown invocation status: " + status);
        }
      }

      if (methodHasPorted) {
        countedPortedMethods++;
      } else {
        countedPureOutOfScopeMethods++;
      }
    }

    // 校验全量 Inventory 覆盖与全集一致性
    int expectedTotalMethods = 0;
    for (Set<String> mSet : SOURCE_TO_METHODS.values()) {
      expectedTotalMethods += mSet.size();
    }
    assertEquals(EXPECTED_TOTAL_METHODS, expectedTotalMethods);
    assertEquals(EXPECTED_TOTAL_METHODS, seenSourceMethodPairs.size());

    // 动态计算统计指标的断言，不信任外部自报
    assertEquals(EXPECTED_TOTAL_METHODS, countedMethods, "total methods count");
    assertEquals(EXPECTED_TOTAL_INVOCATIONS, countedInvocations, "total invocations count");
    assertEquals(EXPECTED_PORTED_INVOCATIONS, countedPortedInvocations, "ported invocations count");
    assertEquals(
        EXPECTED_OUT_OF_SCOPE_INVOCATIONS,
        countedOutOfScopeInvocations,
        "out of scope invocations count");
    assertEquals(EXPECTED_PORTED_METHODS, countedPortedMethods, "ported methods count");
    assertEquals(
        EXPECTED_PURE_OUT_OF_SCOPE_METHODS,
        countedPureOutOfScopeMethods,
        "pure out of scope methods count");
  }
}
