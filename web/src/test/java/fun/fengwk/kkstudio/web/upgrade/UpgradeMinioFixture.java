package fun.fengwk.kkstudio.web.upgrade;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * 正常 Web 入口验收的真实隔离 S3 基座：本次测试自有的 {@link GenericContainer} 启动与 {@code deploy/test/compose.yaml} 同一个
 * digest 的 MinIO，并用真实 {@link S3Client} 建一个私有桶。
 *
 * <p>容器只把 API 端口发布到回环上的随机端口（Docker 默认会发布到全部网卡），凭据是本次测试的固定可丢弃值。它不启动、不连接也不停止 任何已有 compose 栈，{@link
 * #close()} 只停止自己启动的那一个容器；测试进程异常退出时由 Testcontainers 按 container ID 回收。
 *
 * <p>包内可见（package-private）：只是升级验收的物理 S3 基座，不是生产代码。
 */
final class UpgradeMinioFixture implements AutoCloseable {

  /** 与隔离 compose 栈一致的受审服务端 pin；本机架构不同时由 {@code platform} 决定运行形态。 */
  private static final String IMAGE =
      "fengwk/minio@sha256:ea0a48a13c701cf2c4397b3a9c0fe9e10ed65b2f58f056bf07e2ce7308123535";

  /** compose 栈显式固定的平台：该 fork 只发布 amd64 服务端。 */
  private static final String PLATFORM = "linux/amd64";

  private static final int API_PORT = 9000;

  private static final String HEALTH_PATH = "/minio/health/live";

  private static final String REGION = "us-east-1";

  private static final String LOOPBACK = "127.0.0.1";

  /** 本次测试的固定可丢弃根凭据：只用于隔离容器，与任何部署或共享数据面无关。 */
  private static final String ACCESS_KEY = "upgrade-web-entry";

  private static final String SECRET_KEY = "upgrade-web-entry-secret";

  private final GenericContainer<?> container;

  private final String bucket;

  private UpgradeMinioFixture(GenericContainer<?> container, String bucket) {
    this.container = container;
    this.bucket = bucket;
  }

  /** 启动本次测试独占的 MinIO 并建好私有桶；建桶失败时立即停止该容器，绝不留下半启动的资源。 */
  static UpgradeMinioFixture start() {
    String bucket = "upgrade-" + UUID.randomUUID().toString().replace("-", "");
    GenericContainer<?> container =
        new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withExposedPorts(API_PORT)
            .waitingFor(Wait.forHttp(HEALTH_PATH).forPort(API_PORT))
            .withCreateContainerCmdModifier(UpgradeMinioFixture::bindLoopbackAndPlatform);
    container.start();
    try {
      createPrivateBucket(container, bucket);
    } catch (RuntimeException error) {
      container.stop();
      throw error;
    }
    return new UpgradeMinioFixture(container, bucket);
  }

  String endpoint() {
    return "http://" + LOOPBACK + ":" + container.getMappedPort(API_PORT);
  }

  String region() {
    return REGION;
  }

  String bucket() {
    return bucket;
  }

  String accessKey() {
    return ACCESS_KEY;
  }

  String secretKey() {
    return SECRET_KEY;
  }

  @Override
  public void close() {
    container.stop();
  }

  /**
   * 在容器创建命令上固定服务端平台，并把随机主机端口只发布到回环。
   *
   * <p>Testcontainers 先写好默认的随机端口发布，创建命令 modifier 随后运行，因此这里替换成回环绑定不会与它的端口分配冲突。
   */
  private static void bindLoopbackAndPlatform(CreateContainerCmd command) {
    command.withPlatform(PLATFORM);
    HostConfig hostConfig = command.getHostConfig();
    if (hostConfig == null) {
      hostConfig = new HostConfig();
      command.withHostConfig(hostConfig);
    }
    hostConfig.withPortBindings(
        List.of(new PortBinding(Ports.Binding.bindIp(LOOPBACK), ExposedPort.tcp(API_PORT))));
  }

  /** 用真实 S3 客户端建桶并回读：MinIO 的桶默认私有，不需要额外放开任何匿名访问。 */
  private static void createPrivateBucket(GenericContainer<?> container, String bucket) {
    try (S3Client client = s3Client(container)) {
      client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
      client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
    }
  }

  private static S3Client s3Client(GenericContainer<?> container) {
    return S3Client.builder()
        .endpointOverride(
            URI.create("http://" + LOOPBACK + ":" + container.getMappedPort(API_PORT)))
        .region(Region.of(REGION))
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build();
  }
}
