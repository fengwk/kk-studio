package fun.fengwk.kkstudio.web.environment;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.server.RawHTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;

import fun.fengwk.kkstudio.platform.daemon.protocol.ControlProtocolCodec;
import fun.fengwk.kkstudio.platform.daemon.protocol.Envelope;
import fun.fengwk.kkstudio.platform.daemon.transport.WebSocketEndPoint;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 测试专用的 Daemon HTTP/2 服务端实现。 */
class DaemonTestHttp2Server {

  static Connection createConnection(
      ServerConnector connector, WebSocketEndPoint endPoint, String environmentId) {
    HttpConfiguration httpConfig = new HttpConfiguration();
    RawHTTP2ServerConnectionFactory h2Factory =
        new RawHTTP2ServerConnectionFactory(
            httpConfig,
            new ServerSessionListener() {
              @Override
              public void onAccept(Session session) {}

              @Override
              public Stream.Listener onNewStream(Stream stream, HeadersFrame frame) {
                MetaData metaData = frame.getMetaData();
                if (metaData instanceof MetaData.Request request) {
                  String method = request.getMethod();
                  String uri = request.getHttpURI().getPath();

                  if ("POST".equals(method) && "/control".equals(uri)) {
                    MetaData.Response response =
                        new MetaData.Response(
                            HttpStatus.OK_200, "OK", HttpVersion.HTTP_2, HttpFields.EMPTY);
                    stream.headers(
                        new HeadersFrame(stream.getId(), response, null, false), Callback.NOOP);

                    byte[] readyFrame = loadReadyFrame(environmentId);
                    stream.data(
                        new DataFrame(stream.getId(), ByteBuffer.wrap(readyFrame), false),
                        Callback.NOOP);

                    return new Stream.Listener() {
                      @Override
                      public void onDataAvailable(Stream s) {
                        Stream.Data data = s.readData();
                        if (data != null) {
                          data.release();
                          s.demand();
                        }
                      }
                    };
                  } else if ("CONNECT".equals(method)) {
                    MetaData.Response response =
                        new MetaData.Response(
                            HttpStatus.OK_200, "OK", HttpVersion.HTTP_2, HttpFields.EMPTY);
                    stream.headers(
                        new HeadersFrame(stream.getId(), response, null, false), Callback.NOOP);
                    stream.demand();

                    return new Stream.Listener() {
                      @Override
                      public void onDataAvailable(Stream s) {
                        Stream.Data data = s.readData();
                        if (data != null) {
                          ByteBuffer buf = data.frame().getByteBuffer();
                          byte[] echo = new byte[buf.remaining()];
                          buf.get(echo);
                          data.release();
                          s.data(
                              new DataFrame(stream.getId(), ByteBuffer.wrap(echo), false),
                              Callback.NOOP);
                          s.demand();
                        }
                      }
                    };
                  } else if ("GET".equals(method) && "/test-tunnel".equals(uri)) {
                    MetaData.Response response =
                        new MetaData.Response(
                            HttpStatus.OK_200, "OK", HttpVersion.HTTP_2, HttpFields.EMPTY);
                    stream.headers(
                        new HeadersFrame(stream.getId(), response, null, false), Callback.NOOP);
                    byte[] payload = "hello-from-daemon".getBytes(StandardCharsets.UTF_8);
                    stream.data(
                        new DataFrame(stream.getId(), ByteBuffer.wrap(payload), true),
                        Callback.NOOP);
                    return new Stream.Listener() {};
                  }
                }
                return new Stream.Listener() {};
              }
            });
    h2Factory.setConnectProtocolEnabled(true);
    Connection connection = h2Factory.newConnection(connector, endPoint);
    endPoint.setConnection(connection);
    return connection;
  }

  private static byte[] loadReadyFrame(String environmentId) {
    Path p1 = Path.of("platform/daemon-server/src/main/protocol/golden/ready.bin");
    Path p2 = Path.of("../platform/daemon-server/src/main/protocol/golden/ready.bin");
    Path target = Files.isRegularFile(p1) ? p1 : p2;
    try {
      Envelope golden = ControlProtocolCodec.decodeBinary(Files.readAllBytes(target));
      Envelope ready = golden.toBuilder().setEnvironmentId(environmentId).build();
      return ControlProtocolCodec.encodeFrame(ready);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
