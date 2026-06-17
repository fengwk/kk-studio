package fun.fengwk.kkstudio.agent.provider.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import fun.fengwk.kkstudio.agent.provider.AssistantResponse;
import fun.fengwk.kkstudio.agent.provider.AssistantResponseHandle;
import fun.fengwk.kkstudio.agent.provider.AssistantResponseHandler;
import fun.fengwk.kkstudio.agent.provider.Provider;
import fun.fengwk.kkstudio.agent.provider.ProviderManagerImpl;
import fun.fengwk.kkstudio.agent.provider.fixtures.ProviderTestFixtures;
import fun.fengwk.kkstudio.agent.session.payload.IndexedToolCallDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolCall;
import fun.fengwk.kkstudio.agent.tool.ToolInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provider HTTP 契约测试公共基座。
 *
 * 通过本地 HttpServer 捕获请求，验证 path/header/body 是否符合预期。
 * 这层测试不依赖真实供应商响应格式，只要求 provider 能把请求正确发出去。
 *
 * @author fengwk
 */
public abstract class AbstractProviderContractTestSupport extends AbstractProviderTestSupport {

    /**
     * 使用本地探针服务校验 provider 实际发出的 HTTP path/header/body。
     *
     * 这层测试目标：
     * - provider 是否真的把 ModelInfo/Variant/ToolInfo 翻译进请求
     * - provider 是否使用了正确的 baseUrl、path 与鉴权 header
     * - provider 在收到错误响应时是否能回调 onError
     */
    @Test
    @Timeout(30)
    public void shouldSendExpectedHttpContract() throws Exception {
        ProviderTestFixtures.ProviderContractCase contractCase = ProviderTestFixtures.contractCase(providerName());
        LocalProbeServer probeServer = new LocalProbeServer(contractCase.responseStatus(), contractCase.responseContentType(), contractCase.responseBody());
        probeServer.start();
        try {
            Provider provider = new ProviderManagerImpl().getProvider(providerInfo(probeServer.baseUrl(contractCase.baseUrlPath()), "test-api-key"));
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            List<ToolInfo> toolInfos = contractCase.requiresTool() ? List.of(echoTool()) : List.of();

            provider.asyncChat(
                List.<ChatMessage>of(UserMessage.from(contractCase.prompt())),
                modelInfo(),
                variant(),
                toolInfos,
                new AssistantResponseHandler() {
                    @Override
                    public void onTextDelta(String textDelta, AssistantResponseHandle handle) {
                    }

                    @Override
                    public void onThinkingDelta(String thinkingDelta, AssistantResponseHandle handle) {
                    }

                    @Override
                    public void onToolCallDelta(IndexedToolCallDelta toolCallDelta, AssistantResponseHandle handle) {
                    }

                    @Override
                    public void onToolCallComplete(Integer index, ToolCall toolCall, AssistantResponseHandle handle) {
                    }

                    @Override
                    public void onComplete(AssistantResponse response, AssistantResponseHandle handle) {
                        done.countDown();
                    }

                    @Override
                    public void onError(Throwable error, AssistantResponseHandle handle) {
                        errorRef.set(error);
                        done.countDown();
                    }
                });

            RecordedRequest request = probeServer.awaitRequest();
            assertNotNull(request);
            assertTrue(done.await(30, TimeUnit.SECONDS), () -> providerName() + " contract test timed out");
            assertNotNull(errorRef.get(), () -> providerName() + " contract test should surface probe error");
            assertEquals(contractCase.expectedPath(), request.path());
            String authHeader = request.headers().get(contractCase.authHeaderName().toLowerCase());
            assertNotNull(authHeader, () -> providerName() + " missing auth header: " + contractCase.authHeaderName());
            assertTrue(authHeader.startsWith(contractCase.authHeaderValuePrefix()), () -> providerName() + " auth header prefix mismatch: " + authHeader);
            for (String requiredHeaderName : contractCase.requiredHeaderNames()) {
                assertTrue(request.headers().containsKey(requiredHeaderName.toLowerCase()), () -> providerName() + " missing required header: " + requiredHeaderName);
            }
            String normalizedBody = normalizeBody(request.body());
            for (String expectedFragment : contractCase.bodyContains()) {
                assertTrue(normalizedBody.contains(normalizeBody(expectedFragment)), () -> providerName() + " request body should contain fragment: " + expectedFragment + "\nactual body: " + request.body());
            }
        } finally {
            probeServer.close();
        }
    }

    /**
     * LocalProbeServer 是一个最小本地 HTTP 探针。
     *
     * 职责：
     * - 捕获 provider 发出的第一个请求
     * - 记录 path/header/body
     * - 按 fixture 返回一个固定的错误响应
     */
    private static final class LocalProbeServer implements AutoCloseable {

        private final HttpServer server;
        private final CountDownLatch requestLatch = new CountDownLatch(1);
        private final AtomicReference<RecordedRequest> requestRef = new AtomicReference<>();
        private final int responseStatus;
        private final String responseContentType;
        private final String responseBody;

        private LocalProbeServer(int responseStatus, String responseContentType, String responseBody) throws IOException {
            this.server = HttpServer.create(new InetSocketAddress(0), 0);
            this.responseStatus = responseStatus;
            this.responseContentType = responseContentType;
            this.responseBody = responseBody;
            server.createContext("/", this::handle);
        }

        private void start() {
            server.start();
        }

        /**
         * 构造 provider 连接探针服务时需要使用的 baseUrl。
         */
        private String baseUrl(String baseUrlPath) {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + baseUrlPath).toString();
        }

        /**
         * 等待第一条请求到达，并返回录制结果。
         */
        private RecordedRequest awaitRequest() throws InterruptedException {
            assertTrue(requestLatch.await(30, TimeUnit.SECONDS), "contract probe did not receive request in time");
            return requestRef.get();
        }

        /**
         * 捕获请求并返回固定错误响应。
         */
        private void handle(HttpExchange exchange) throws IOException {
            try (InputStream inputStream = exchange.getRequestBody()) {
                String body = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                Map<String, String> headers = new LinkedHashMap<>();
                exchange.getRequestHeaders().forEach((name, values) -> {
                    if (values != null && !values.isEmpty()) {
                        headers.put(name.toLowerCase(), values.get(0));
                    }
                });
                String rawPath = exchange.getRequestURI().getRawPath();
                if (exchange.getRequestURI().getRawQuery() != null) {
                    rawPath += "?" + exchange.getRequestURI().getRawQuery();
                }
                requestRef.set(new RecordedRequest(rawPath, headers, body));
                requestLatch.countDown();
                byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", responseContentType);
                exchange.sendResponseHeaders(responseStatus, responseBytes.length);
                exchange.getResponseBody().write(responseBytes);
            } finally {
                exchange.close();
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }

    }

    protected record RecordedRequest(String path,
                                     Map<String, String> headers,
                                     String body) {
    }

    /**
     * 对 body 做最小归一化，避免格式化空白导致契约断言脆弱。
     */
    private String normalizeBody(String body) {
        return body == null ? "" : body.replaceAll("\\s+", "");
    }

}
