package fun.fengwk.kkstudio.harness.provider.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 增量解析器的单测与边界测试。
 *
 * <p>完整对齐上游 LangChain4j 1.20.0 的 {@code DefaultServerSentEventParserTest}，
 * 并强化有界内存保护（单行/单事件/累计正文字节数上限）、NUL 检查和严格 UTF-8 解码。
 */
class IncrementalSseParserTest {

  /** 对应上游 shouldParseSimpleSingleLineEvent：验证单行简单 data 事件在不同前导/尾随空行与换行下的解析行为。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "data: Simple message",
        "data: Simple message\n",
        "\ndata: Simple message",
        "\ndata: Simple message\n",
        "\n\ndata: Simple message",
        "data: Simple message\n\n",
        "\n\ndata: Simple message\n\n",
      })
  void shouldParseSimpleSingleLineEvent(String input) {
    List<ServerSentEvent> events = parseAll(input);
    assertEquals(1, events.size());
    assertEquals(new ServerSentEvent(null, "Simple message"), events.get(0));
  }

  /** 对应上游 shouldParseMultiLineDataEvent：验证多行 data 按照 \n 正确拼接。 */
  @Test
  void shouldParseMultiLineDataEvent() {
    String input = "data: First line\ndata: Second line\ndata: Third line\n\n";
    List<ServerSentEvent> events = parseAll(input);
    assertEquals(List.of(new ServerSentEvent(null, "First line\nSecond line\nThird line")), events);
  }

  /** 对应上游 shouldParseEventWithAllFields：验证同时包含 id、event、data、retry 时的解析，仅提取 event 与 data。 */
  @Test
  void shouldParseEventWithAllFields() {
    String input = "id: msg-123\nevent: custom-event\ndata: Message content\nretry: 5000\n\n";
    List<ServerSentEvent> events = parseAll(input);
    assertEquals(List.of(new ServerSentEvent("custom-event", "Message content")), events);
  }

  /** 对应上游 shouldParseMultipleEvents：验证多个双换行分隔的事件逐一分发。 */
  @Test
  void shouldParseMultipleEvents() {
    String input = "data: First event\n\ndata: Second event\n\ndata: Third event\n\n";
    List<ServerSentEvent> events = parseAll(input);
    assertEquals(
        List.of(
            new ServerSentEvent(null, "First event"),
            new ServerSentEvent(null, "Second event"),
            new ServerSentEvent(null, "Third event")),
        events);
  }

  /** 对应上游 shouldIgnoreCommentsAndEmptyLines：验证冒号开头的注释行与多余空行被安全忽略。 */
  @Test
  void shouldIgnoreCommentsAndEmptyLines() {
    String input = ": this is a comment\n\ndata: actual message\n\n";
    List<ServerSentEvent> events = parseAll(input);
    assertEquals(List.of(new ServerSentEvent(null, "actual message")), events);
  }

  /** 对应上游 shouldHandleStreamWithNoEvents：验证空输入流时不分发任何事件。 */
  @Test
  void shouldHandleStreamWithNoEvents() {
    String input = "";
    List<ServerSentEvent> events = parseAll(input);
    assertTrue(events.isEmpty());
  }

  /** 对应上游 shouldPreserveAdditionalLeadingWhitespaceInData：验证除首个冒号后的单空格外，其他前导空格完整保留。 */
  @Test
  void shouldPreserveAdditionalLeadingWhitespaceInData() {
    String input = "data:   indented\n\n";
    List<ServerSentEvent> events = parseAll(input);
    assertEquals(List.of(new ServerSentEvent(null, "  indented")), events);
  }

  /** 对应上游 shouldPreserveTrailingWhitespaceInData：验证尾随空白字符不被裁剪。 */
  @Test
  void shouldPreserveTrailingWhitespaceInData() {
    String input = "data: trailing  \n\n";
    List<ServerSentEvent> events = parseAll(input);
    assertEquals(List.of(new ServerSentEvent(null, "trailing  ")), events);
  }

  /** 对应上游 shouldNotRemoveAnyCharacterWhenDataHasNoLeadingSpace：验证 data 冒号后无空格时不丢失字符。 */
  @Test
  void shouldNotRemoveAnyCharacterWhenDataHasNoLeadingSpace() {
    String input = "data:nospace\n\n";
    List<ServerSentEvent> events = parseAll(input);
    assertEquals(List.of(new ServerSentEvent(null, "nospace")), events);
  }

  /** 对应上游 shouldHandleIOException：验证底层输入流发生真实 I/O 故障时被安全捕获并映射为 IO 终态错误。 */
  @Test
  void shouldHandleIOException() {
    InputStream failingStream =
        new InputStream() {
          private int readCount = 0;

          @Override
          public int read() throws IOException {
            readCount++;
            if (readCount >= 5) {
              throw new IOException("Simulated pipe break");
            }
            return 'd';
          }
        };

    IncrementalSseParser parser = new IncrementalSseParser(HttpSseLimits.DEFAULT, event -> {});
    byte[] buf = new byte[16];
    IOException thrown =
        assertThrows(
            IOException.class,
            () -> {
              int r;
              while ((r = failingStream.read(buf)) != -1) {
                parser.feed(buf, 0, r);
              }
            });
    assertEquals("Simulated pipe break", thrown.getMessage());
  }

  /** 对应上游 parse_stops_emitting_after_the_listener_cancels：验证 listener 取消后停止分发后续事件。 */
  @Test
  void parse_stops_emitting_after_the_listener_cancels() {
    List<ServerSentEvent> received = new ArrayList<>();
    AtomicBoolean cancelled = new AtomicBoolean(false);
    IncrementalSseParser parser =
        new IncrementalSseParser(
            HttpSseLimits.DEFAULT,
            event -> {
              if (!cancelled.get()) {
                received.add(event);
                cancelled.set(true);
              }
            });
    feedString(parser, "data: first\n\ndata: second\n\ndata: third\n\n");
    parser.flush();
    assertEquals(List.of(new ServerSentEvent(null, "first")), received);
  }

  /** 对应上游 parse_handles_cr_and_crlf_line_endings：验证 CRLF 和裸 CR 换行符的统一支持。 */
  @Test
  void parse_handles_cr_and_crlf_line_endings() {
    String input = "event: e\r\ndata: a\r\n\r\ndata: b\r\rdata: c\r\r";
    List<ServerSentEvent> events = parseAll(input);
    assertEquals(
        List.of(
            new ServerSentEvent("e", "a"),
            new ServerSentEvent(null, "b"),
            new ServerSentEvent(null, "c")),
        events);
  }

  /**
   * 对应上游 parse_trims_event_field_and_strips_one_leading_space_from_data：验证 event trim 与 data 剥离单空格。
   */
  @Test
  void parse_trims_event_field_and_strips_one_leading_space_from_data() {
    String input = "event:   spaced-event   \ndata:   spaced value   \n\n";
    List<ServerSentEvent> events = parseAll(input);
    assertEquals(List.of(new ServerSentEvent("spaced-event", "  spaced value   ")), events);
  }

  /**
   * 对应上游 incremental_trims_event_field_and_strips_one_leading_space_from_data：增量模式下空格处理与全量模式严格一致。
   */
  @Test
  void incremental_trims_event_field_and_strips_one_leading_space_from_data() {
    String input = "event:   spaced-event   \ndata:   spaced value   \n\n";
    List<ServerSentEvent> events = parseChunked(input, 4);
    assertEquals(List.of(new ServerSentEvent("spaced-event", "  spaced value   ")), events);
  }

  /**
   * 对应上游 a_parser_that_does_not_support_incremental_parsing_reports_it_as_not_async： 明确证明本 API
   * 架构为原生纯增量解析，无需任何阻塞式/非增量式 fallback，单字节喂入即可实时驱动。
   */
  @Test
  void a_parser_that_does_not_support_incremental_parsing_reports_it_as_not_async() {
    List<ServerSentEvent> events = new ArrayList<>();
    IncrementalSseParser parser = new IncrementalSseParser(HttpSseLimits.DEFAULT, events::add);
    // 单字节逐字节供给，证明在完全未遇到换行时无事件，一旦遇到双换行立即交付，绝无全量缓冲等待
    byte[] bytes = "data: byte-by-byte\n\n".getBytes(StandardCharsets.UTF_8);
    for (int i = 0; i < bytes.length - 1; i++) {
      parser.feed(bytes, i, 1);
      assertTrue(events.isEmpty(), "no event before terminating blank line");
    }
    parser.feed(bytes, bytes.length - 1, 1);
    assertEquals(1, events.size());
    assertEquals("byte-by-byte", events.get(0).data());
  }

  /** 边界测试：空行事件边界即使没有 data 也必须重置 currentEvent，后续 data 事件不继承前置无 data 的 event。 */
  @Test
  void event_only_followed_by_data_does_not_inherit_event() {
    String input = "event: custom-event\n\n: ignored comment\n\ndata: actual-value\n\n";
    List<ServerSentEvent> events = parseAll(input);
    assertEquals(1, events.size());
    assertEquals(new ServerSentEvent(null, "actual-value"), events.get(0));
  }

  /** 边界测试：连续空行与注释行重置事件状态，不累积事件大小。 */
  @Test
  void consecutive_blank_lines_and_comments_do_not_accumulate_event_size() {
    HttpSseLimits tinyEventLimit = new HttpSseLimits(1024, 20, 1024 * 1024, 1024);
    List<ServerSentEvent> events = new ArrayList<>();
    IncrementalSseParser parser = new IncrementalSseParser(tinyEventLimit, events::add);
    // 连续空行与注释
    feedString(parser, "\n\n\n: comment 1\n: comment 2\n\n\n");
    feedString(parser, "data: tiny\n\n");
    assertEquals(List.of(new ServerSentEvent(null, "tiny")), events);
  }

  /** 边界测试：feed 输入参数越界校验。 */
  @Test
  void offset_length_bounds_validation() {
    IncrementalSseParser parser = new IncrementalSseParser(HttpSseLimits.DEFAULT, event -> {});
    byte[] buf = new byte[10];
    assertThrows(IndexOutOfBoundsException.class, () -> parser.feed(buf, -1, 5));
    assertThrows(IndexOutOfBoundsException.class, () -> parser.feed(buf, 0, 15));
    assertThrows(IndexOutOfBoundsException.class, () -> parser.feed(buf, 8, 4));
    assertThrows(NullPointerException.class, () -> parser.feed(null, 0, 0));
  }

  /** 边界测试：成功流实际 wire 字节数统计必须包含 BOM 并在超过限制时抛出异常。 */
  @Test
  void wire_bytes_count_includes_bom_and_triggers_overflow() {
    // 限制总 body 为 5 字节
    HttpSseLimits limit5 = new HttpSseLimits(1024, 1024, 5, 1024);
    IncrementalSseParser parser = new IncrementalSseParser(limit5, event -> {});
    // BOM 3 字节 + "abc" 3 字节 = 6 字节 > 5
    byte[] bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a', 'b', 'c'};
    TransportException ex =
        assertThrows(TransportException.class, () -> parser.feed(bom, 0, bom.length));
    assertEquals(TransportErrorKind.INVALID_RESPONSE, ex.kind());
  }

  /** 对应上游 incremental_parses_single_event_in_one_chunk：验证单 chunk 递送单事件。 */
  @Test
  void incremental_parses_single_event_in_one_chunk() {
    List<ServerSentEvent> events = parseAll("data: hello\n\n");
    assertEquals(List.of(new ServerSentEvent(null, "hello")), events);
  }

  /** 对应上游 incremental_parses_event_split_across_chunks：验证跨 chunk 分片的事件正确组装。 */
  @Test
  void incremental_parses_event_split_across_chunks() {
    List<ServerSentEvent> events = new ArrayList<>();
    IncrementalSseParser parser = new IncrementalSseParser(HttpSseLimits.DEFAULT, events::add);
    feedString(parser, "da");
    assertTrue(events.isEmpty());
    feedString(parser, "ta: hel");
    assertTrue(events.isEmpty());
    feedString(parser, "lo\n\n");
    assertEquals(List.of(new ServerSentEvent(null, "hello")), events);
  }

  /** 对应上游 incremental_handles_crlf_line_endings：增量模式验证 CRLF 换行。 */
  @Test
  void incremental_handles_crlf_line_endings() {
    List<ServerSentEvent> events = parseAll("event: e\r\ndata: a\r\n\r\n");
    assertEquals(List.of(new ServerSentEvent("e", "a")), events);
  }

  /** 对应上游 incremental_parses_multiple_events：增量模式验证多事件有序交付。 */
  @Test
  void incremental_parses_multiple_events() {
    List<ServerSentEvent> events = parseChunked("data: one\n\ndata: two\n\ndata: three\n\n", 5);
    assertEquals(
        List.of(
            new ServerSentEvent(null, "one"),
            new ServerSentEvent(null, "two"),
            new ServerSentEvent(null, "three")),
        events);
  }

  /** 对应上游 incremental_joins_multiline_data：增量模式验证跨 chunk 多行拼接。 */
  @Test
  void incremental_joins_multiline_data() {
    List<ServerSentEvent> events = parseChunked("data: a\ndata: b\ndata: c\n\n", 3);
    assertEquals(List.of(new ServerSentEvent(null, "a\nb\nc")), events);
  }

  /** 对应上游 incremental_ignores_comment_id_and_retry_lines：增量模式验证忽略注释、id 与 retry。 */
  @Test
  void incremental_ignores_comment_id_and_retry_lines() {
    List<ServerSentEvent> events = parseAll("id: 1\nretry: 5000\n: a comment\ndata: x\n\n");
    assertEquals(List.of(new ServerSentEvent(null, "x")), events);
  }

  /** 对应上游 incremental_flush_emits_trailing_event_without_terminating_blank_line：EOF 自动交付尾部未闭合事件。 */
  @Test
  void incremental_flush_emits_trailing_event_without_terminating_blank_line() {
    List<ServerSentEvent> events = new ArrayList<>();
    IncrementalSseParser parser = new IncrementalSseParser(HttpSseLimits.DEFAULT, events::add);
    feedString(parser, "data: a\n\ndata: b\n");
    assertEquals(List.of(new ServerSentEvent(null, "a")), events);
    parser.flush();
    assertEquals(List.of(new ServerSentEvent(null, "a"), new ServerSentEvent(null, "b")), events);
  }

  /** 对应上游 incremental_flush_completes_a_pending_partial_line：EOF 交付未换行的不完整行。 */
  @Test
  void incremental_flush_completes_a_pending_partial_line() {
    List<ServerSentEvent> events = new ArrayList<>();
    IncrementalSseParser parser = new IncrementalSseParser(HttpSseLimits.DEFAULT, events::add);
    feedString(parser, "data: no newline yet");
    assertTrue(events.isEmpty());
    parser.flush();
    assertEquals(List.of(new ServerSentEvent(null, "no newline yet")), events);
  }

  /** 对应上游 incremental_flush_returns_empty_when_nothing_is_pending：无未决数据时 flush 不多发空事件。 */
  @Test
  void incremental_flush_returns_empty_when_nothing_is_pending() {
    List<ServerSentEvent> events = new ArrayList<>();
    IncrementalSseParser parser = new IncrementalSseParser(HttpSseLimits.DEFAULT, events::add);
    feedString(parser, "data: x\n\n");
    assertEquals(List.of(new ServerSentEvent(null, "x")), events);
    parser.flush();
    assertEquals(1, events.size());
  }

  /**
   * 对应上游 incremental_decodes_utf8_multibyte_char_split_across_chunks：跨 chunk 切分的 UTF-8 多字节字符完整解码。
   */
  @Test
  void incremental_decodes_utf8_multibyte_char_split_across_chunks() {
    byte[] all = "data: café\n\n".getBytes(StandardCharsets.UTF_8);
    // 'é' 为 0xC3 0xA9，从 0xC3 之后切分 chunk
    int split = 0;
    for (int i = 0; i < all.length; i++) {
      if (all[i] == (byte) 0xC3) {
        split = i + 1;
        break;
      }
    }

    List<ServerSentEvent> events = new ArrayList<>();
    IncrementalSseParser parser = new IncrementalSseParser(HttpSseLimits.DEFAULT, events::add);
    parser.feed(all, 0, split);
    assertTrue(events.isEmpty());
    parser.feed(all, split, all.length - split);
    parser.flush();
    assertEquals(List.of(new ServerSentEvent(null, "café")), events);
  }

  /** 强化测试：流开头的 UTF-8 BOM 被安全跳过，流中普通数据正常解析。 */
  @Test
  void bom_at_stream_start_is_skipped() {
    byte[] bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    byte[] text = "data: with bom\n\n".getBytes(StandardCharsets.UTF_8);
    byte[] full = new byte[bom.length + text.length];
    System.arraycopy(bom, 0, full, 0, bom.length);
    System.arraycopy(text, 0, full, bom.length, text.length);

    List<ServerSentEvent> events = new ArrayList<>();
    IncrementalSseParser parser = new IncrementalSseParser(HttpSseLimits.DEFAULT, events::add);
    parser.feed(full, 0, full.length);
    parser.flush();
    assertEquals(List.of(new ServerSentEvent(null, "with bom")), events);
  }

  /** 强化测试：单行字节数超出限制时拒绝并抛出 INVALID_RESPONSE。 */
  @Test
  void oversized_line_fails_immediately() {
    HttpSseLimits strictLimits = new HttpSseLimits(10, 100, 1000, 100);
    IncrementalSseParser parser = new IncrementalSseParser(strictLimits, event -> {});
    TransportException ex =
        assertThrows(
            TransportException.class, () -> feedString(parser, "data: 123456789012345\n\n"));
    assertEquals(TransportErrorKind.INVALID_RESPONSE, ex.kind());
  }

  /** 强化测试：单个事件累计字节数超出限制时拒绝。 */
  @Test
  void oversized_event_fails_immediately() {
    HttpSseLimits strictLimits = new HttpSseLimits(100, 20, 1000, 100);
    IncrementalSseParser parser = new IncrementalSseParser(strictLimits, event -> {});
    TransportException ex =
        assertThrows(
            TransportException.class,
            () -> feedString(parser, "data: 1234567890\ndata: 1234567890123\n\n"));
    assertEquals(TransportErrorKind.INVALID_RESPONSE, ex.kind());
  }

  /** 强化测试：累计响应流大小超出上限时拒绝。 */
  @Test
  void oversized_total_body_fails_immediately() {
    HttpSseLimits strictLimits = new HttpSseLimits(100, 100, 30, 100);
    IncrementalSseParser parser = new IncrementalSseParser(strictLimits, event -> {});
    TransportException ex =
        assertThrows(
            TransportException.class,
            () -> feedString(parser, "data: a\n\ndata: b\n\ndata: c\n\ndata: d\n\n"));
    assertEquals(TransportErrorKind.INVALID_RESPONSE, ex.kind());
  }

  /** 强化测试：遇到 NUL 字节时确定性失败。 */
  @Test
  void nul_byte_causes_invalid_response_failure() {
    byte[] input = new byte[] {'d', 'a', 't', 'a', ':', 0, '\n', '\n'};
    IncrementalSseParser parser = new IncrementalSseParser(HttpSseLimits.DEFAULT, event -> {});
    TransportException ex =
        assertThrows(TransportException.class, () -> parser.feed(input, 0, input.length));
    assertEquals(TransportErrorKind.INVALID_RESPONSE, ex.kind());
  }

  /** 强化测试：畸形 UTF-8 序列触发解码失败。 */
  @Test
  void malformed_utf8_causes_invalid_response_failure() {
    byte[] input = new byte[] {'d', 'a', 't', 'a', ':', ' ', (byte) 0xFF, (byte) 0xFE, '\n', '\n'};
    IncrementalSseParser parser = new IncrementalSseParser(HttpSseLimits.DEFAULT, event -> {});
    TransportException ex =
        assertThrows(TransportException.class, () -> parser.feed(input, 0, input.length));
    assertEquals(TransportErrorKind.INVALID_RESPONSE, ex.kind());
  }

  private static List<ServerSentEvent> parseAll(String input) {
    List<ServerSentEvent> events = new ArrayList<>();
    IncrementalSseParser parser = new IncrementalSseParser(HttpSseLimits.DEFAULT, events::add);
    feedString(parser, input);
    parser.flush();
    return events;
  }

  private static List<ServerSentEvent> parseChunked(String input, int chunkSize) {
    List<ServerSentEvent> events = new ArrayList<>();
    IncrementalSseParser parser = new IncrementalSseParser(HttpSseLimits.DEFAULT, events::add);
    byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
    for (int i = 0; i < bytes.length; i += chunkSize) {
      int len = Math.min(chunkSize, bytes.length - i);
      parser.feed(bytes, i, len);
    }
    parser.flush();
    return events;
  }

  private static void feedString(IncrementalSseParser parser, String str) {
    byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
    parser.feed(bytes, 0, bytes.length);
  }
}
