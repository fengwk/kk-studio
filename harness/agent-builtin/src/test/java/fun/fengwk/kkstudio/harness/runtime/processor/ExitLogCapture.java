package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试专用退出日志捕获：只挂到指定类的精确 logger，关闭时恢复原 level/additivity 并摘除 appender。
 *
 * <p>仅用于验证退出边界日志只包含固定 phase 与异常简单类名；不修改 root logger、不复用静态日志实现、不触碰标准输出。
 */
public final class ExitLogCapture extends AppenderBase<ILoggingEvent> implements AutoCloseable {

  private final Logger logger;
  private final Level previousLevel;
  private final boolean previousAdditivity;
  private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

  private ExitLogCapture(Logger logger, Level previousLevel, boolean previousAdditivity) {
    this.logger = logger;
    this.previousLevel = previousLevel;
    this.previousAdditivity = previousAdditivity;
  }

  public static ExitLogCapture attach(Class<?> owner) {
    Logger logger = (Logger) LoggerFactory.getLogger(owner);
    ExitLogCapture capture = new ExitLogCapture(logger, logger.getLevel(), logger.isAdditive());
    capture.setContext(logger.getLoggerContext());
    capture.start();
    logger.setLevel(Level.WARN);
    logger.setAdditive(false);
    logger.addAppender(capture);
    return capture;
  }

  @Override
  protected void append(ILoggingEvent event) {
    events.add(event);
  }

  /** 成功路径不应出现该退出边界的失败日志。 */
  public void assertNoFailure(String messageMarker) {
    assertEquals(
        0,
        matching(messageMarker).size(),
        () -> "unexpected exit failure log: " + describe(messageMarker));
  }

  /** 断言该边界恰有一条失败日志：固定 phase、异常简单类名、无 Throwable，且 formatted/raw 参数都不含 synthetic secret。 */
  public void assertSingleSafeFailure(
      String messageMarker, String phase, Class<? extends Throwable> failureType, String secret) {
    List<ILoggingEvent> matched = matching(messageMarker);
    assertEquals(
        1,
        matched.size(),
        () -> "expected exactly one exit failure log: " + describe(messageMarker));
    ILoggingEvent event = matched.getFirst();
    String formatted = event.getFormattedMessage();
    assertTrue(formatted.contains("phase=" + phase), formatted);
    assertTrue(formatted.contains(failureType.getSimpleName()), formatted);
    assertNull(event.getThrowableProxy(), "exit failure log must not carry a throwable");
    assertFalse(formatted.contains(secret), "formatted message must not expose failure details");
    Object[] arguments = event.getArgumentArray();
    if (arguments != null) {
      for (Object argument : arguments) {
        assertFalse(
            String.valueOf(argument).contains(secret),
            () -> "raw log argument must not expose failure details: " + argument);
      }
    }
  }

  private List<ILoggingEvent> matching(String messageMarker) {
    return events.stream()
        .filter(event -> event.getFormattedMessage().contains(messageMarker))
        .toList();
  }

  private String describe(String messageMarker) {
    return events.stream()
        .filter(event -> event.getFormattedMessage().contains(messageMarker))
        .map(ILoggingEvent::getFormattedMessage)
        .toList()
        .toString();
  }

  @Override
  public void close() {
    logger.detachAppender(this);
    stop();
    logger.setLevel(previousLevel);
    logger.setAdditive(previousAdditivity);
  }
}
