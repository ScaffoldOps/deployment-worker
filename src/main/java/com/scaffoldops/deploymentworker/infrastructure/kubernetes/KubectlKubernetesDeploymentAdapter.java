package com.scaffoldops.deploymentworker.infrastructure.kubernetes;

import com.scaffoldops.deploymentworker.application.port.out.KubernetesDeploymentPort;
import com.scaffoldops.deploymentworker.domain.event.DeploymentEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class KubectlKubernetesDeploymentAdapter implements KubernetesDeploymentPort {
    private final Kubectl kubectl;
    private final ObjectMapper json;
    private final KubernetesResourceFactory factory;

    public KubectlKubernetesDeploymentAdapter(Kubectl kubectl, ObjectMapper json, KubernetesResourceFactory factory) {
        this.kubectl = kubectl;
        this.json = json;
        this.factory = factory;
    }

    @Override
    public void deploy(DeploymentEvent event) {
        // Fail closed if deterministic names belong to a different owner.
        String existing = kubectl.run(null, "-n", event.namespace(), "get", "deployment,service",
                event.resourceName(), "--ignore-not-found", "-o", "json");
        try {
            var resources = json.readTree(existing).path("items");
            for (var resource : resources) {
                var labels = resource.path("metadata").path("labels");
                if (!event.generationRequestId().toString().equals(labels.path("scaffoldops.io/request-id").asText())
                        || !"scaffoldops".equals(labels.path("app.kubernetes.io/managed-by").asText())) {
                    throw new IllegalStateException("Existing Kubernetes resource has another owner");
                }
            }
            kubectl.run(json.writeValueAsString(factory.manifests(event)), "apply", "-f", "-");
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Invalid Kubernetes JSON", ex);
        }
        kubectl.run(null, "-n", event.namespace(), "rollout", "status", "deployment/" + event.resourceName(),
                "--timeout=120s");
    }

    @Override
    public void undeploy(DeploymentEvent event) {
        kubectl.run(null, "-n", event.namespace(), "delete", "deployment,service", "-l",
                "scaffoldops.io/request-id=" + event.generationRequestId() + ",app.kubernetes.io/managed-by=scaffoldops",
                "--ignore-not-found=true", "--wait=true", "--timeout=120s");
    }
}
