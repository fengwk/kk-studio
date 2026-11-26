package fun.fengwk.kkstudio.ide.gateway;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.io.Connection;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.platform.account.StudioAccountService;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 定期校验已交接的公开 IDE 连接；Studio 登录凭据只留在入口节点内存中。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdeStudioSessionMonitor {

  static final String LISTENER_ATTRIBUTE = IdeStudioSessionMonitor.class.getName() + ".listener";

  private final StudioAccountService accounts;
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon().name("ide-session-check").factory());
  private final ConcurrentHashMap<Connection, Session> active = new ConcurrentHashMap<>();

  @PostConstruct
  void start() {
    scheduler.scheduleWithFixedDelay(this::tick, 5, 5, TimeUnit.SECONDS);
  }

  @PreDestroy
  void stop() {
    scheduler.shutdownNow();
    active.clear();
  }

  Connection.Listener listener(String token, UUID userId) {
    return new Connection.Listener() {
      @Override
      public void onOpened(Connection connection) {
        active.put(connection, new Session(token, userId));
        // Endpoint close or monitor shutdown may race with Jetty's onOpen callback.
        if (scheduler.isShutdown() || !connection.getEndPoint().isOpen()) {
          active.remove(connection);
        }
      }

      @Override
      public void onClosed(Connection connection) {
        active.remove(connection);
      }
    };
  }

  void tick() {
    // Both successful and failed checks are shared only within this scan.
    Map<Session, Boolean> authenticated = new HashMap<>();
    active.forEach(
        (connection, session) -> {
          if (!authenticated.computeIfAbsent(session, this::isAuthenticated)) {
            connection.close();
          }
        });
  }

  private boolean isAuthenticated(Session session) {
    try {
      return accounts
          .authenticate(session.token())
          .filter(p -> session.userId().equals(p.userId()))
          .isPresent();
    } catch (RuntimeException ex) {
      // Do not log exceptions from authentication; they may contain sensitive request data.
      log.warn("Unable to revalidate an IDE Studio session; closing the connection");
      return false;
    }
  }

  private record Session(String token, UUID userId) {}
}
