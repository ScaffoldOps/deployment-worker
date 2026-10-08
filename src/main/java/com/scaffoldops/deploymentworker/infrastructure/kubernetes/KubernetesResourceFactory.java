package com.scaffoldops.deploymentworker.infrastructure.kubernetes;

import com.scaffoldops.deploymentworker.domain.event.DeploymentEvent;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class KubernetesResourceFactory {
    private final int port;

    public KubernetesResourceFactory(@Value("${app.kubernetes.container-port:8080}") int port) {
        this.port = port;
    }

    public Map<String, String> labels(DeploymentEvent event) {
        return Map.of("app.kubernetes.io/name", event.resourceName(),
                "app.kubernetes.io/managed-by", "scaffoldops",
                "scaffoldops.io/request-id", event.generationRequestId().toString());
    }

    public Map<String, Object> manifests(DeploymentEvent event) {
        var labels = labels(event);
        var selector = Map.of("scaffoldops.io/request-id", event.generationRequestId().toString());
        var metadata = Map.of("name", event.resourceName(), "namespace", event.namespace(), "labels", labels);
        var container = Map.of("name", "service", "image", event.imageRef(),
                "ports", List.of(Map.of("containerPort", port)),
                "readinessProbe", Map.of("tcpSocket", Map.of("port", port),
                        "initialDelaySeconds", 5, "periodSeconds", 5));
        var deployment = Map.of("apiVersion", "apps/v1", "kind", "Deployment", "metadata", metadata,
                "spec", Map.of("replicas", event.replicas(), "selector", Map.of("matchLabels", selector),
                        "template", Map.of("metadata", Map.of("labels", labels),
                                "spec", Map.of("containers", List.of(container)))));
        var service = Map.of("apiVersion", "v1", "kind", "Service", "metadata", metadata,
                "spec", Map.of("type", "ClusterIP", "selector", selector,
                        "ports", List.of(Map.of("port", port, "targetPort", port))));
        return Map.of("apiVersion", "v1", "kind", "List", "items", List.of(deployment, service));
    }
}
