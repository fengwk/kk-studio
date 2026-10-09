package fun.fengwk.kkstudio.notification;

import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.share.notification.NotificationCarrier;
import fun.fengwk.kkstudio.share.notification.NotificationLimits;
import fun.fengwk.kkstudio.share.notification.NotificationOutbox;
import fun.fengwk.kkstudio.share.notification.NotificationPacket;
import fun.fengwk.kkstudio.share.notification.NotificationReassembler;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** One LISTEN connection, one bounded fair transient sender, and no domain-specific SQL. */
@Slf4j
final class PgTransport implements CrossNodeTransport {
  private static final String BROADCAST_CHANNEL = "kk_notification";
  private static final String SEND_SQL =
      "select pg_notify(?, payload) from unnest(?::text[]) with ordinality as n(payload, ord) order by ord";

  private final DataSource dataSource;
  private final JdbcTemplate jdbc;
  private final UUID self;
  private final NotificationLimits limits;
  private final int pollMillis;
  private final long reconnectMillis;
  private final NotificationReassembler reassembler;
  private final Consumer<String> resync;
  private final Runnable resyncAll;
  private final NotificationOutbox outbox;
  private boolean closed;
  private boolean started;
  private volatile boolean readerHealthy;
  private volatile boolean senderHealthy = true;
  private volatile Connection listenConnection;
  private volatile Connection senderConnection;
  private volatile Statement senderStatement;
  private Thread reader;
  private Thread sender;

  PgTransport(
      DataSource dataSource,
      UUID self,
      NotificationLimits limits,
      Duration pollInterval,
      Duration reconnectBackoff,
      Consumer<NotificationPacket> delivery,
      Consumer<String> resync,
      Runnable resyncAll) {
    this.dataSource = Objects.requireNonNull(dataSource);
    this.jdbc = new JdbcTemplate(dataSource);
    this.self = self;
    this.limits = limits;
    this.pollMillis = Math.toIntExact(positiveMillis(pollInterval));
    this.reconnectMillis = positiveMillis(reconnectBackoff);
    this.resync = resync;
    this.resyncAll = resyncAll;
    this.outbox = new NotificationOutbox(limits);
    this.reassembler =
        new NotificationReassembler(self, limits, System::nanoTime, delivery, resync);
  }

  @Override
  public synchronized void start() {
    if (closed) {
      throw new IllegalStateException("transport closed");
    }
    if (!started) {
      started = true;
      reader = Thread.ofPlatform().daemon(true).name("notification-pg-reader").start(this::read);
      sender =
          Thread.ofPlatform().daemon(true).name("notification-pg-sender").start(this::sendPending);
    }
  }

  @Override
  public void send(NotificationPacket message, boolean transactional) {
    if (transactional) {
      synchronized (this) {
        if (closed) {
          throw new IllegalStateException("transport closed");
        }
      }
      List<String> frames = new ArrayList<>(NotificationCarrier.count(message.byteLength()));
      for (int index = 0; index < NotificationCarrier.count(message.byteLength()); index++) {
        frames.add(NotificationCarrier.chunk(message, index).encode());
      }
      sendTransactionalFrames(message.target(), frames);
    } else {
      synchronized (this) {
        if (closed) {
          throw new IllegalStateException("transport closed");
        }
        if (!outbox.offer(message)) {
          resync.accept(message.topic());
          throw new IllegalStateException("transient notification outbox budget exceeded");
        }
        notifyAll();
      }
    }
  }

  private void sendTransactionalFrames(UUID target, List<String> frames) {
    jdbc.execute(
        (ConnectionCallback<Void>)
            connection -> {
              // JdbcTemplate uses the DataSource's thread-bound transaction connection.
              int timeoutSeconds = Math.max(1, (int) limits.reassemblyTimeout().toSeconds());
              try (PreparedStatement statement = connection.prepareStatement(SEND_SQL)) {
                statement.setQueryTimeout(timeoutSeconds);
                var array = connection.createArrayOf("text", frames.toArray(String[]::new));
                try {
                  statement.setString(1, target == null ? BROADCAST_CHANNEL : inboxChannel(target));
                  statement.setArray(2, array);
                  statement.execute();
                } finally {
                  array.free();
                }
              }
              return null;
            });
  }

  private void sendPendingFrames(UUID target, List<String> frames) throws SQLException {
    try (Connection connection = dataSource.getConnection()) {
      synchronized (this) {
        if (closed) {
          return;
        }
        senderConnection = connection;
      }
      try {
        int timeoutSeconds = Math.max(1, (int) limits.reassemblyTimeout().toSeconds());
        try (PreparedStatement statement = connection.prepareStatement(SEND_SQL)) {
          synchronized (this) {
            if (closed) {
              return;
            }
            senderStatement = statement;
          }
          statement.setQueryTimeout(timeoutSeconds);
          var array = connection.createArrayOf("text", frames.toArray(String[]::new));
          try {
            statement.setString(1, target == null ? BROADCAST_CHANNEL : inboxChannel(target));
            statement.setArray(2, array);
            statement.execute();
          } finally {
            array.free();
          }
        }
      } finally {
        synchronized (this) {
          senderStatement = null;
          senderConnection = null;
        }
      }
    }
  }

  private void sendPending() {
    try {
      while (true) {
        NotificationOutbox.Batch batch;
        synchronized (this) {
          while (true) {
            if (closed) {
              return;
            }
            NotificationOutbox.Batch candidate = outbox.pollBatch().orElse(null);
            if (candidate != null) {
              batch = candidate;
              break;
            }
            wait();
          }
        }
        boolean sent = false;
        try {
          // The outbox retains the whole logical reservation while the SQL call is in flight.
          sendPendingFrames(batch.target(), batch.frames());
          senderHealthy = true;
          sent = true;
        } catch (SQLException | RuntimeException error) {
          senderHealthy = false;
          resync.accept(batch.topic());
          log.warn(
              "Notification send failed topic={} errorType={}",
              batch.topic(),
              error.getClass().getSimpleName());
        }
        synchronized (this) {
          if (closed) {
            return;
          }
          outbox.complete(batch, sent);
        }
      }
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }

  private void read() {
    while (!isClosed()) {
      try (Connection connection = dataSource.getConnection()) {
        connection.setAutoCommit(true);
        synchronized (this) {
          if (closed) {
            return;
          }
          listenConnection = connection;
        }
        PGConnection pg = connection.unwrap(PGConnection.class);
        try (Statement statement = connection.createStatement()) {
          statement.execute("LISTEN " + BROADCAST_CHANNEL);
          statement.execute("LISTEN " + inboxChannel(self));
        }
        readerHealthy = true;
        resyncAll.run();
        int tickMillis =
            Math.max(
                1,
                (int) Math.min(pollMillis, Math.min(100L, limits.reassemblyTimeout().toMillis())));
        long validationIntervalNanos = pollMillis * 1_000_000L;
        long nextValidation = System.nanoTime() + validationIntervalNanos;
        int validationTimeoutSeconds = (int) Math.max(1L, (pollMillis + 999L) / 1000L);
        while (!isClosed()) {
          PGNotification[] notifications = pg.getNotifications(tickMillis);
          if (notifications != null) {
            for (PGNotification notification : notifications) {
              try {
                NotificationCarrier carrier =
                    NotificationCarrier.decode(notification.getParameter(), limits, self);
                if (carrier == null) {
                  continue;
                }
                String expected =
                    carrier.target() == null ? BROADCAST_CHANNEL : inboxChannel(carrier.target());
                if (!expected.equals(notification.getName())) {
                  throw new IllegalArgumentException("carrier address/channel mismatch");
                }
                reassembler.accept(carrier);
              } catch (IllegalArgumentException error) {
                log.warn(
                    "Notification carrier decode failed errorType={}",
                    error.getClass().getSimpleName());
                resyncAll.run();
              }
            }
          }
          reassembler.expire();
          // A timed notification read alone cannot detect an idle half-open TCP connection.
          if (System.nanoTime() - nextValidation >= 0) {
            if (!connection.isValid(validationTimeoutSeconds)) {
              throw new SQLException("notification listener connection is not valid");
            }
            nextValidation = System.nanoTime() + validationIntervalNanos;
          }
        }
      } catch (SQLException | RuntimeException error) {
        if (!isClosed()) {
          readerHealthy = false;
          reassembler.clear();
          resyncAll.run();
          log.warn(
              "Notification listener disconnected errorType={}", error.getClass().getSimpleName());
          try {
            Thread.sleep(reconnectMillis);
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return;
          }
        }
      } finally {
        listenConnection = null;
      }
    }
  }

  static String inboxChannel(UUID node) {
    return "kk_notification_" + node.toString().replace("-", "");
  }

  @Override
  public boolean healthy() {
    return !closed && readerHealthy && senderHealthy;
  }

  private synchronized boolean isClosed() {
    return closed;
  }

  @Override
  public void close() {
    Connection listenConn;
    Connection senderConn;
    Statement senderStmt;
    synchronized (this) {
      if (closed) {
        return;
      }
      closed = true;
      readerHealthy = false;
      senderHealthy = false;
      outbox.close();
      listenConn = listenConnection;
      senderConn = senderConnection;
      senderStmt = senderStatement;
      notifyAll();
    }
    if (senderStmt != null) {
      try {
        senderStmt.cancel();
      } catch (SQLException ignored) {
      }
    }
    if (senderConn != null) {
      try {
        senderConn.abort(Runnable::run);
      } catch (SQLException error) {
        log.warn(
            "Notification sender connection abort failed errorType={}",
            error.getClass().getSimpleName());
      }
    }
    if (listenConn != null) {
      try {
        listenConn.abort(Runnable::run);
      } catch (SQLException error) {
        log.warn(
            "Notification connection abort failed errorType={}", error.getClass().getSimpleName());
      }
    }
    try {
      stop(reader);
    } finally {
      try {
        stop(sender);
      } finally {
        reassembler.clear();
      }
    }
  }

  private static void stop(Thread thread) {
    if (thread != null && thread != Thread.currentThread()) {
      thread.interrupt();
      try {
        thread.join(5000);
        if (thread.isAlive()) {
          throw new IllegalStateException("notification worker failed to stop");
        }
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted closing notification worker");
      }
    }
  }

  private static long positiveMillis(Duration duration) {
    long nanos = duration.toNanos();
    if (nanos <= 0 || nanos % 1_000_000 != 0) {
      throw new IllegalArgumentException("duration must be positive whole milliseconds");
    }
    return nanos / 1_000_000;
  }
}
