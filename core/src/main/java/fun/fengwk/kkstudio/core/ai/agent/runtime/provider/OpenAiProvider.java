package fun.fengwk.kkstudio.core.ai.agent.runtime.provider;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.internal.Json;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

/**
 * @author fengwk
 */
public class OpenAiProvider {

    public static void main(String[] args) {
        StreamingChatModel model = OpenAiStreamingChatModel.builder()
            .baseUrl(System.getenv("TEST_OPENAI_BASE_URL"))
            .apiKey(System.getenv("TEST_OPENAI_API_KEY"))
            .modelName("gpt-5.3-codex")
            .build();

//        ChatMessage.

        SystemMessage msg1 = SystemMessage.systemMessage("你是一个搜搜专家");

        ChatRequest chatReq = ChatRequest.builder().messages(msg1).build();
        model.chat(chatReq, new StreamingChatResponseHandler() {

            @Override
            public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
                System.out.println(Json.toJson(partialThinking));
            }

            @Override
            public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
                System.out.println(Json.toJson(partialResponse));
            }


            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {

                System.out.println(Json.toJson(completeResponse));
            }

            @Override
            public void onError(Throwable error) {
                System.out.println(error);
            }
        });

    }
}
