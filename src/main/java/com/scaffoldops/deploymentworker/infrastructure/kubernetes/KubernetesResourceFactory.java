package com.scaffoldops.deploymentworker.infrastructure.kubernetes;

import com.scaffoldops.deploymentworker.domain.event.DeploymentEvent;
import io.kubernetes.client.custom.IntOrString;
import io.kubernetes.client.openapi.models.*;
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

    private V1ObjectMeta metadata(DeploymentEvent event) {
        return new V1ObjectMeta().name(event.resourceName()).namespace(event.namespace()).labels(labels(event));
    }

    private Map<String, String> selector(DeploymentEvent event) {
        return Map.of("scaffoldops.io/request-id", event.generationRequestId().toString());
    }

    public V1Deployment deployment(DeploymentEvent event) {
        var container = new V1Container().name("service").image(event.imageRef())
                .ports(List.of(new V1ContainerPort().containerPort(port)))
                .readinessProbe(new V1Probe().tcpSocket(new V1TCPSocketAction().port(new IntOrString(port)))
                        .initialDelaySeconds(5).periodSeconds(5));
        return new V1Deployment().apiVersion("apps/v1").kind("Deployment").metadata(metadata(event))
                .spec(new V1DeploymentSpec().replicas(event.replicas())
                        .selector(new V1LabelSelector().matchLabels(selector(event)))
                        .template(new V1PodTemplateSpec().metadata(new V1ObjectMeta().labels(labels(event)))
                                .spec(new V1PodSpec().containers(List.of(container)))));
    }

    public V1Service service(DeploymentEvent event) {
        return new V1Service().apiVersion("v1").kind("Service").metadata(metadata(event))
                .spec(new V1ServiceSpec().type("ClusterIP").selector(selector(event))
                        .ports(List.of(new V1ServicePort().port(port).targetPort(new IntOrString(port)))));
    }
}
