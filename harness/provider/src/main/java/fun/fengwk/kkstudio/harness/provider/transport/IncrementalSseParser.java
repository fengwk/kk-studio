package fun.fengwk.kkstudio.harness.provider.transport;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 增量式安全 Server-Sent Events 解析器。
 *
 * <p>安全与规范契约：
 *
 * <ul>
 *   <li>逐字节增量解析，禁止使用 {@code BufferedReader.readLine()} 预先无界分配字符缓冲区。
 *   <li>行结尾兼容 CRLF ({@code \r\n})、单 LF ({@code \n}) 与孤立 CR ({@code \r})。
 *   <li>严格 UTF-8 解码与校验，仅在流开头跳过 UTF-8 BOM；遇到畸形 UTF-8 序列或 NUL ({@code \0}) 字节确定失败。
 *   <li>多行 data 以 {@code \n} 拼接；字段值若以单个空格开头则仅剥离该空格，保留其他前导与尾随空白。
 *   <li>严格按字节统计并限制单行上限、单事件上限与成功流累计上限。
 *   <li>流到达 EOF 时分发尚未以空行闭合的 pending event。
 * </ul>
 */
class IncrementalSseParser {

  private static final byte[] UTF8_BOM = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

  private final HttpSseLimits limits;
  private final Consumer<ServerSentEvent> eventConsumer;

  private final ByteArrayOutputStream currentLineBuffer = new ByteArrayOutputStream(256);
  private int currentLineBytes;
  private int currentEventBytes;
  private long totalSuccessBytes;

  private boolean pendingCr;
  private boolean bomChecked;
  private final byte[] bomPending = new byte[3];
  private int bomPendingCount;

  private String currentEvent;
  private StringBuilder currentData;

  IncrementalSseParser(HttpSseLimits limits, Consumer<ServerSentEvent> eventConsumer) {
    this.limits = Objects.requireNonNull(limits, "limits must not be null");
    this.eventConsumer = Objects.requireNonNull(eventConsumer, "eventConsumer must not be null");
  }

  /** 向解析器供给一段原始字节数据。 */
  void feed(byte[] buf, int offset, int length) {
    if (length <= 0) {
      return;
    }
    int index = offset;
    int end = offset + length;

    // 流开头 UTF-8 BOM 探测与跳过
    if (!bomChecked) {
      while (index < end && bomPendingCount < 3) {
        bomPending[bomPendingCount++] = buf[index++];
      }
      if (bomPendingCount == 3) {
        bomChecked = true;
        if (bomPending[0] == UTF8_BOM[0]
            && bomPending[1] == UTF8_BOM[1]
            && bomPending[2] == UTF8_BOM[2]) {
          // 开头包含完整 BOM，丢弃这 3 个字节
        } else {
          // 不是 BOM，将暂存的字节按普通数据逐字节处理
          for (int i = 0; i < 3; i++) {
            processByte(bomPending[i]);
          }
        }
      } else {
        // 数据不足 3 字节，继续等待后续数据
        return;
      }
    }

    while (index < end) {
      processByte(buf[index++]);
    }
  }

  /** 当底层流到达 EOF 时刷新并交付尚未闭合的尾部行和事件。 */
  void flush() {
    // 若流在未凑满 3 字节的 BOM 探测阶段便遇到 EOF，将暂存字节作为普通字节消费
    if (!bomChecked && bomPendingCount > 0) {
      bomChecked = true;
      for (int i = 0; i < bomPendingCount; i++) {
        processByte(bomPending[i]);
      }
    }

    if (currentLineBuffer.size() > 0) {
      finishLine();
    }
    dispatchPendingEvent();
  }

  private void processByte(byte b) {
    totalSuccessBytes++;
    if (totalSuccessBytes > limits.maxSuccessBodyBytes()) {
      throw new TransportException(
          TransportErrorKind.INVALID_RESPONSE, "Success response body size exceeded limit");
    }

    if (b == 0) {
      throw new TransportException(
          TransportErrorKind.INVALID_RESPONSE, "NUL byte is not allowed in SSE stream");
    }

    if (pendingCr) {
      pendingCr = false;
      if (b == '\n') {
        // CRLF 中的 LF，已在遇到 CR 时分发，丢弃
        return;
      }
      // 否则说明 CR 为独立行结尾，当前字节 b 属于新的一行，继续向下执行
    }

    if (b == '\r') {
      pendingCr = true;
      finishLine();
      return;
    }

    if (b == '\n') {
      finishLine();
      return;
    }

    currentLineBytes++;
    if (currentLineBytes > limits.maxLineBytes()) {
      throw new TransportException(
          TransportErrorKind.INVALID_RESPONSE, "SSE line size exceeded limit");
    }

    currentEventBytes++;
    if (currentEventBytes > limits.maxEventBytes()) {
      throw new TransportException(
          TransportErrorKind.INVALID_RESPONSE, "SSE event size exceeded limit");
    }

    currentLineBuffer.write(b);
  }

  private void finishLine() {
    byte[] lineBytes = currentLineBuffer.toByteArray();
    currentLineBuffer.reset();
    currentLineBytes = 0;

    String line;
    try {
      CharsetDecoder decoder =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT);
      line = decoder.decode(ByteBuffer.wrap(lineBytes)).toString();
    } catch (CharacterCodingException error) {
      throw new TransportException(
          TransportErrorKind.INVALID_RESPONSE, "Malformed UTF-8 in SSE stream", error);
    }

    processLine(line);
  }

  private void processLine(String line) {
    if (line.isEmpty()) {
      dispatchPendingEvent();
      return;
    }

    if (line.startsWith(":")) {
      // 规范定义的注释行，忽略
      return;
    }

    if (line.startsWith("event:")) {
      currentEvent = line.substring("event:".length()).trim();
    } else if (line.equals("event")) {
      currentEvent = "";
    } else if (line.startsWith("data:")) {
      String value = extractDataValue(line);
      if (currentData == null) {
        currentData = new StringBuilder(value);
      } else {
        currentData.append('\n').append(value);
      }
    } else if (line.equals("data")) {
      if (currentData == null) {
        currentData = new StringBuilder();
      } else {
        currentData.append('\n');
      }
    }
    // id、retry 以及其他未知字段安全忽略
  }

  private void dispatchPendingEvent() {
    if (currentData != null) {
      ServerSentEvent event = new ServerSentEvent(currentEvent, currentData.toString());
      currentEvent = null;
      currentData = null;
      currentEventBytes = 0;
      eventConsumer.accept(event);
    }
  }

  private static String extractDataValue(String line) {
    String value = line.substring("data:".length());
    return value.startsWith(" ") ? value.substring(1) : value;
  }
}
