package com.scaffoldops.deploymentworker.infrastructure.kubernetes;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.util.ClientBuilder;
import java.io.IOException;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KubernetesClientConfiguration {
    @Bean
    ApiClient kubernetesClient() throws IOException {
        // Explicit cluster configuration ignores host kubeconfig and uses rotating SA tokens.
        ApiClient client = ClientBuilder.cluster().setReadTimeout(Duration.ofSeconds(10)).build();
        client.setHttpClient(client.getHttpClient().newBuilder()
                .callTimeout(Duration.ofSeconds(10)).build());
        return client;
    }

    @Bean
    AppsV1Api appsApi(ApiClient client) { return new AppsV1Api(client); }

    @Bean
    CoreV1Api coreApi(ApiClient client) { return new CoreV1Api(client); }
}
