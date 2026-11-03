package fun.fengwk.kkstudio.harness.environment;

import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** 环境能力契约不携带 Daemon 连接服务的传输依赖。 */
class EnvironmentDependencyBoundaryTest {

  /** 从本模块测试运行时检查依赖树，防止直接或传递引入 Jetty 与 Protobuf。 */
  @Test
  void transportLibrariesAreAbsentFromContractClasspath() {
    ClassLoader loader = EnvironmentId.class.getClassLoader();
    assertNull(loader.getResource("com/google/protobuf/Message.class"));
    assertNull(loader.getResource("org/eclipse/jetty/io/EndPoint.class"));
    assertNull(loader.getResource("org/eclipse/jetty/http2/client/HTTP2Client.class"));
  }
}
