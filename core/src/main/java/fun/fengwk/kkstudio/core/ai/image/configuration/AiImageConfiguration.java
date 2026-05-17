package fun.fengwk.kkstudio.core.ai.image.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.Authenticator;
import java.net.http.HttpClient;

/**
 * @author fengwk
 */
@EnableConfigurationProperties(GptImage2Properties.class)
@Configuration
public class AiImageConfiguration {

    @Bean
    public HttpClient httpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder();
        ProxySettings proxySettings = ProxySettings.fromEnvironment(System.getenv());
        if (proxySettings.isEnabled()) {
            builder.proxy(proxySettings.toProxySelector());
            Authenticator authenticator = proxySettings.toAuthenticator();
            if (authenticator != null) {
                builder.authenticator(authenticator);
            }
        }
        return builder.build();
    }

}
