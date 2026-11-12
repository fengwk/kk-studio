package fun.fengwk.kkstudio.platform.harness.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.api.session.NativeAssistantBlockContent;
import fun.fengwk.kkstudio.harness.api.session.RedactedThinkingMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.builtin.request.ProviderMessageProjector;
import fun.fengwk.kkstudio.harness.builtin.request.ProviderMessageProjector.ProjectedMessage;
import fun.fengwk.kkstudio.harness.codec.AgentMessageJsonCodec;
import fun.fengwk.kkstudio.harness.model.ImageInputTier;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ModelProvider;
import fun.fengwk.kkstudio.harness.model.provider.ProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMediaCapabilities;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderJsonBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderOpaqueBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRedactedThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResourceBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.model.provider.impl.anthropic.AnthropicProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.impl.gemini.GeminiProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.impl.openai.chat.OpenAiChatProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.impl.openai.responses.OpenAiResponsesProviderAdapter;
import fun.fengwk.kkstudio.harness.model.provider.impl.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.platform.plugin.resource.SessionResourceUri;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobContent;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;

import java.io.IOException;
import java.util.ArrayList;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 四种协议的两轮离线回归：本地 HttpServer 回放合成 SSE，经真实 adapter + 真实 {@link JdkHttpSseTransport} 产生 provider
 * completion 与 block-owned metadata，经 durable message codec 和 {@link ProviderResourceMaterializer} 后发起第二轮请求。
 *
 * <p>物化和持久化边界不得丢弃 assistant block metadata；测试不访问真实 Provider，也不使用真实会话内容。
 */
class ProviderReplayMaterializationTransportTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final UUID BLOB_ID = new UUID(0L, 7L);
  private static final String THINKING = "synthetic reasoning";
  private static final String ANSWER = "synthetic answer";

  private final List<String> requestBodies = new CopyOnWriteArrayList<>();
  private volatile String responseSse;

  private HttpServer server;
  private int port;
  private HttpClient httpClient;
  private ExecutorService workerExecutor;
  private ScheduledExecutorService scheduler;
  private JdkHttpSseTransport transport;

  @BeforeEach
  void setUp() throws IOException {
    responseSse = fixture(ProviderType.OPENAI);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    port = server.getAddress().getPort();
    server.createContext(
        "/",
        exchange -> {
          try (exchange) {
            requestBodies.add(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body = responseSse;
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
              out.write(body.getBytes(StandardCharsets.UTF_8));
              out.flush();
            }
          }
        });
    server.start();

    workerExecutor = Executors.newCachedThreadPool();
    scheduler = Executors.newSingleThreadScheduledExecutor();
    httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .executor(workerExecutor)
            .build();
    transport = new JdkHttpSseTransport(httpClient, scheduler);
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
    if (httpClient != null) {
      httpClient.shutdownNow();
    }
    if (workerExecutor != null) {
      workerExecutor.shutdownNow();
    }
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  /** Provider-native reasoning block metadata survives the resource materialization boundary. */
  @Test
  void secondTurnWireRequestKeepsReasoningContentAfterResourceMaterialization() throws Exception {
    StorageBlobManager blobManager = mock(StorageBlobManager.class);
    when(blobManager.getBlob(BLOB_ID)).thenReturn(activeTextBlob());
    // 该回归只使用文本 Resource：内联能力保持 NONE，wire 上仍是确定性文本回退，与内联媒体能力无关。
    ProviderResourceMaterializer materializer =
        new ProviderResourceMaterializer(
            blobManager, mock(StorageBlobContentService.class), () -> ImageInputTier.P720);
    ProviderDescriptor descriptor = descriptor();
    ModelProvider provider = new OpenAiChatProviderAdapter(transport, "sk-test").create(descriptor);

    // 第一轮：durable 用户消息含 Resource，production 路径先物化再发出。
    ProviderCompletion firstCompletion =
        stream(
            provider,
            request(
                materializer.materialize(
                    List.of(userWithResource()),
                    Set.of(ModelInputModality.TEXT),
                    ProviderMediaCapabilities.NONE)));

    assertEquals(THINKING, firstCompletion.response().thinking());
    // 第一轮已发生物化：wire 上不再出现 durable blob 引用，且尚无 assistant replay。
    assertTrue(
        requestBodies.get(0).contains("uri: " + SessionResourceUri.format(BLOB_ID)),
        "Resource 必须在 wire 前被物化: " + requestBodies.get(0));
    assertFalse(requestBodies.get(0).contains("reasoning_content"), requestBodies.get(0));

    // 第二轮：assistant durable 消息（thinking 取自 provider response）与同一用户消息一起物化后发出。
    ProviderMessage assistant =
        new ProviderMessage(ProviderMessageRole.ASSISTANT, firstCompletion.response().blocks());
    assertNotNull(
        stream(
            provider,
            request(
                materializer.materialize(
                    List.of(userWithResource(), assistant),
                    Set.of(ModelInputModality.TEXT),
                    ProviderMediaCapabilities.NONE))));

    JsonNode messages = MAPPER.readTree(requestBodies.get(1)).get("messages");
    // systemInstruction 恒由请求级 system 消息承载，不占用会话消息位置。
    assertEquals(3, messages.size(), requestBodies.get(1));
    assertEquals("system", messages.get(0).path("role").asText());
    assertEquals("Test system instruction.", messages.get(0).path("content").asText());
    assertEquals("user", messages.get(1).path("role").asText());
    assertEquals("assistant", messages.get(2).path("role").asText());
    assertEquals(ANSWER, messages.get(2).path("content").asText());
    assertEquals(
        THINKING,
        messages.get(2).path("reasoning_content").asText(),
        "物化边界丢弃 replay 会退化为语义回退并丢失 reasoning_content");
  }

  /** 意图：durable Blob 与 opaque replay 分别往返 codec，再经真实转换、编码和 HTTP 发出，原生字段与附件字节均不丢失。 */
  @ParameterizedTest
  @EnumSource(ProviderType.class)
  void blobAndNativeReplaySurviveTwoTurnsAcrossEveryProtocol(ProviderType type) throws Exception {
    responseSse = fixture(type);
    ProviderAdapter adapter = adapter(type);
    ProviderDescriptor descriptor =
        new ProviderDescriptor(
            "test-provider",
            type,
            "http://127.0.0.1:" + port,
            new ModelCallTimeoutPolicy(Duration.ofSeconds(10), Duration.ofSeconds(10)),
            new UUID(0L, 3L));
    ModelProvider provider = adapter.create(descriptor);
    byte[] bytes = "%PDF-1.4\nsynthetic".getBytes(StandardCharsets.UTF_8);
    String base64 = Base64.getEncoder().encodeToString(bytes);
    StorageBlob blob = activeTextBlob();
    blob.setMediaType("application/pdf");
    blob.setSizeBytes(bytes.length);
    StorageBlobManager manager = mock(StorageBlobManager.class);
    StorageBlobContentService content = mock(StorageBlobContentService.class);
    when(manager.getBlob(BLOB_ID)).thenReturn(blob);
    when(content.readBlobContent(BLOB_ID, ProviderInlineBlobReader.Limits.DEFAULT.maxBlobBytes()))
        .thenReturn(new StorageBlobContent(BLOB_ID, bytes, "application/pdf", bytes.length));
    ProviderResourceMaterializer materializer =
        new ProviderResourceMaterializer(manager, content, () -> ImageInputTier.P720);

    AgentMessageJsonCodec messages = new AgentMessageJsonCodec();
    String durableUserJson =
        messages.encode(
            new AgentMessage(
                AgentMessageRole.USER,
                List.of(ResourceMessageContent.media(BLOB_ID, "document.pdf"))));
    assertTrue(durableUserJson.contains(BLOB_ID.toString()));
    assertFalse(durableUserJson.contains("base64"));
    AgentMessage durableUser = messages.decode(durableUserJson);
    ProviderMessageProjector projector = ProviderMessageProjector.byNames(Set.of());
    Set<ModelInputModality> modalities =
        Set.of(ModelInputModality.TEXT, ModelInputModality.DOCUMENT);
    ProviderRequest firstRequest =
        request(
            materializer.materialize(
                projector.projectSources(List.of(ProjectedMessage.of(durableUser))),
                modalities,
                adapter.mediaCapabilities()),
            modalities);
    ProviderCompletion completion = stream(provider, firstRequest);
    assertEquals(THINKING, completion.response().thinking());
    assertEquals(ANSWER, completion.response().text());

    AgentMessage durableAssistant =
        messages.decode(messages.encode(durableAssistant(completion.response().blocks())));
    List<ProviderMessage> secondMessages =
        projector.projectSources(
            List.of(
                ProjectedMessage.of(durableUser),
                ProjectedMessage.of(durableAssistant),
                ProjectedMessage.of(
                    new AgentMessage(
                        AgentMessageRole.USER, List.of(new TextMessageContent("continue"))))));
    stream(
        provider,
        request(
            materializer.materialize(secondMessages, modalities, adapter.mediaCapabilities()),
            modalities));

    assertEquals(2, requestBodies.size());
    JsonNode firstWire = MAPPER.readTree(requestBodies.get(0));
    JsonNode secondWire = MAPPER.readTree(requestBodies.get(1));
    for (String body : requestBodies) {
      assertTrue(body.contains(base64), "full attachment bytes must reach both requests");
      assertFalse(body.contains(BLOB_ID.toString()), "Blob IDs are not provider media sources");
      assertFalse(body.contains("http://minio"));
    }
    switch (type) {
      case OPENAI -> {
        // 系统指令合成为唯一前导 system message，会话消息整体后移一位。
        assertEquals("system", firstWire.at("/messages/0/role").asText());
        assertEquals("Test system instruction.", firstWire.at("/messages/0/content").asText());
        assertEquals(
            "data:application/pdf;base64," + base64,
            firstWire.at("/messages/1/content/0/file/file_data").asText());
        assertEquals(firstWire.at("/messages/1"), secondWire.at("/messages/1"));
        assertEquals(THINKING, secondWire.at("/messages/2/reasoning_content").asText());
        assertEquals("continue", secondWire.at("/messages/3/content").asText());
      }
      case OPENAI_RESPONSES -> {
        assertEquals(
            "data:application/pdf;base64," + base64,
            firstWire.at("/input/0/content/0/file_data").asText());
        assertEquals(firstWire.at("/input/0"), secondWire.at("/input/0"));
        assertEquals("opaque-encrypted", secondWire.at("/input/1/encrypted_content").asText());
      }
      case ANTHROPIC -> {
        assertEquals(base64, firstWire.at("/messages/0/content/0/source/data").asText());
        assertEquals("base64", firstWire.at("/messages/0/content/0/source/type").asText());
        assertEquals(firstWire.at("/messages/0"), secondWire.at("/messages/0"));
        assertEquals("opaque-signature", secondWire.at("/messages/1/content/0/signature").asText());
        assertEquals("opaque-redacted", secondWire.at("/messages/1/content/1/data").asText());
      }
      case GOOGLE -> {
        assertEquals(base64, firstWire.at("/contents/0/parts/0/inlineData/data").asText());
        assertEquals(firstWire.at("/contents/0"), secondWire.at("/contents/0"));
        assertEquals(
            "opaque-signature", secondWire.at("/contents/1/parts/0/thoughtSignature").asText());
      }
    }
    verify(content, times(1))
        .readBlobContent(BLOB_ID, ProviderInlineBlobReader.Limits.DEFAULT.maxBlobBytes());
  }

  private ProviderAdapter adapter(ProviderType type) {
    return switch (type) {
      case OPENAI -> new OpenAiChatProviderAdapter(transport, "test-key");
      case OPENAI_RESPONSES -> new OpenAiResponsesProviderAdapter(transport, "test-key");
      case ANTHROPIC -> new AnthropicProviderAdapter(transport, "test-key");
      case GOOGLE -> new GeminiProviderAdapter(transport, "test-key");
    };
  }

  private static String fixture(ProviderType type) throws IOException {
    try (InputStream input =
        ProviderReplayMaterializationTransportTest.class.getResourceAsStream(
            "replay/" + type.name() + ".sse")) {
      assertNotNull(input, "missing SSE fixture");
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static AgentMessage durableAssistant(List<ProviderContentBlock> blocks) {
    List<AgentMessageContent> contents = new ArrayList<>();
    for (ProviderContentBlock block : blocks) {
      if (block instanceof ProviderThinkingBlock thinking) {
        contents.add(new ThinkingMessageContent(thinking.thinking(), thinking.providerBlockData()));
      } else if (block instanceof ProviderTextBlock text) {
        contents.add(new TextMessageContent(text.text(), text.providerBlockData()));
      } else if (block instanceof ProviderRedactedThinkingBlock redacted) {
        contents.add(
            new RedactedThinkingMessageContent(redacted.data(), redacted.providerBlockData()));
      } else if (block instanceof ProviderToolCallBlock toolCall) {
        var call = toolCall.toolCall();
        contents.add(
            new ToolCallMessageContent(
                call.id(), call.name(), call.name(), call.argumentsJson(), null, null,
                toolCall.providerBlockData()));
      } else if (block instanceof ProviderOpaqueBlock opaque) {
        contents.add(new NativeAssistantBlockContent(opaque.blockType(), opaque.providerBlockData()));
      } else if (block instanceof ProviderJsonBlock json) {
        contents.add(new JsonMessageContent(json.json()));
      } else {
        throw new IllegalArgumentException("unsupported assistant block: " + block.getClass());
      }
    }
    return new AgentMessage(AgentMessageRole.ASSISTANT, contents);
  }

  private static ProviderMessage userWithResource() {
    return new ProviderMessage(
        ProviderMessageRole.USER,
        List.of(
            new ProviderTextBlock("hello"),
            new ProviderResourceBlock(BLOB_ID, "notes.txt", "synthetic preview")));
  }

  private static ProviderCompletion stream(ModelProvider provider, ProviderRequest request)
      throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<ProviderCompletion> completion = new AtomicReference<>();
    AtomicReference<ProviderException> failure = new AtomicReference<>();
    provider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event) {}

          @Override
          public void onComplete(ProviderCompletion value) {
            completion.set(value);
            latch.countDown();
          }

          @Override
          public void onError(ProviderException error) {
            failure.set(error);
            latch.countDown();
          }
        });
    assertTrue(latch.await(15, TimeUnit.SECONDS), "provider stream timed out");
    assertNull(failure.get(), "provider stream failed");
    ProviderCompletion result = completion.get();
    assertNotNull(result, "provider stream produced no completion");
    return result;
  }

  private ProviderDescriptor descriptor() {
    return new ProviderDescriptor(
        "test-provider",
        ProviderType.OPENAI,
        "http://127.0.0.1:" + port,
        new ModelCallTimeoutPolicy(Duration.ofSeconds(10), Duration.ofSeconds(10)),
        new UUID(0L, 3L));
  }

  private static ProviderRequest request(List<ProviderMessage> messages) {
    return request(messages, Set.of(ModelInputModality.TEXT));
  }

  private static ProviderRequest request(
      List<ProviderMessage> messages, Set<ModelInputModality> modalities) {
    ModelDescriptor model =
        new ModelDescriptor("test-provider", "test-model", "test-model", modalities, false, true);
    return new ProviderRequest(
        model,
        new ModelVariant("default", null, PromptCacheRetention.NONE, null),
        1024,
        "Test system instruction.",
        messages,
        List.of(),
        ProviderCacheControl.none());
  }

  private static StorageBlob activeTextBlob() {
    StorageBlob blob = new StorageBlob();
    blob.setId(BLOB_ID);
    blob.setMediaType("text/plain");
    blob.setSizeBytes(42L);
    blob.setState(StorageBlobState.ACTIVE);
    return blob;
  }
}
