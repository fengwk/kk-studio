package fun.fengwk.kkstudio.ide.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.server.DaemonConnectionIdentity;
import fun.fengwk.kkstudio.ide.protocol.IdeStreamMode;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * 意图：IDE 流 ticket 必须一次性、绑定 Daemon 实例代际（Environment + Daemon 实例）与模式、且短时过期；消费即删除使二次消费 失败，revoke
 * 只清除同代际未消费的 ticket，避免被接管实例的 ticket 继续可用。
 */
class InMemoryIdeStreamTicketServiceTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final String INSTANCE = "33333333-3333-3333-3333-333333333333";

  private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

  private InMemoryIdeStreamTicketService service() {
    return new InMemoryIdeStreamTicketService(new SecureRandom(), clock, Duration.ofSeconds(30));
  }

  private static DaemonConnectionIdentity identity(String daemonInstanceId) {
    return new DaemonConnectionIdentity(ENV, daemonInstanceId);
  }

  @Test
  void issuesOpaque256BitTicketAndConsumesOnce() {
    InMemoryIdeStreamTicketService service = service();
    IdeStreamTicketService.Issued issued = service.issue(identity(INSTANCE), IdeStreamMode.HTTP);

    // 32 字节 base64url（无 padding）固定 43 个字符；不含注册 token。
    assertEquals(43, issued.ticket().length());
    assertEquals(IdeStreamMode.HTTP, issued.mode());

    Optional<IdeStreamTicketService.Consumed> consumed = service.consume(issued.ticket());
    assertTrue(consumed.isPresent());
    assertEquals(ENV, consumed.get().environmentId());
    assertEquals(INSTANCE, consumed.get().identity().daemonInstanceId());
    assertEquals(IdeStreamMode.HTTP, consumed.get().mode());
    assertTrue(service.consume(issued.ticket()).isEmpty());
  }

  @Test
  void issuesDistinctTickets() {
    InMemoryIdeStreamTicketService service = service();
    assertNotEquals(
        service.issue(identity(INSTANCE), IdeStreamMode.WS).ticket(),
        service.issue(identity(INSTANCE), IdeStreamMode.WS).ticket());
  }

  @Test
  void expiredTicketIsNotConsumable() {
    InMemoryIdeStreamTicketService service = service();
    String ticket = service.issue(identity(INSTANCE), IdeStreamMode.WS).ticket();
    clock.advance(Duration.ofSeconds(31));
    assertTrue(service.consume(ticket).isEmpty());
  }

  @Test
  void revokeClearsOnlyMatchingGeneration() {
    InMemoryIdeStreamTicketService service = service();
    String replacedTicket = service.issue(identity(INSTANCE), IdeStreamMode.HTTP).ticket();
    String otherInstance = "44444444-4444-4444-4444-444444444444";
    String currentTicket = service.issue(identity(otherInstance), IdeStreamMode.HTTP).ticket();

    service.revoke(identity(INSTANCE));

    assertTrue(service.consume(replacedTicket).isEmpty());
    assertTrue(service.consume(currentTicket).isPresent());
  }

  @Test
  void rejectsBlankTicket() {
    InMemoryIdeStreamTicketService service = service();
    assertTrue(service.consume("   ").isEmpty());
    assertTrue(service.consume(null).isEmpty());
  }

  /** 可推进的 UTC 时钟：用于确定性验证短时过期，不依赖真实等待。 */
  private static final class MutableClock extends Clock {

    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    private void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
