package com.scaffoldops.deploymentworker.application.service;

import com.scaffoldops.deploymentworker.application.port.in.CleanupDeploymentUseCase;
import com.scaffoldops.deploymentworker.application.port.in.DeployGeneratedServiceUseCase;
import com.scaffoldops.deploymentworker.application.port.in.UndeployGeneratedServiceUseCase;
import com.scaffoldops.deploymentworker.application.port.out.GenerationRequestStatusPort;
import com.scaffoldops.deploymentworker.application.port.out.KubernetesDeploymentPort;
import com.scaffoldops.deploymentworker.domain.event.DeploymentEvent;
import com.scaffoldops.deploymentworker.domain.model.DeploymentStatus;
import org.springframework.stereotype.Service;

@Service
public class DeploymentLifecycleService implements DeployGeneratedServiceUseCase,
        UndeployGeneratedServiceUseCase, CleanupDeploymentUseCase {
    private final KubernetesDeploymentPort kubernetes;
    private final GenerationRequestStatusPort statusPort;

    public DeploymentLifecycleService(KubernetesDeploymentPort kubernetes, GenerationRequestStatusPort statusPort) {
        this.kubernetes = kubernetes;
        this.statusPort = statusPort;
    }

    @Override
    public void deploy(DeploymentEvent event) {
        process(event, true);
    }

    @Override
    public void undeploy(DeploymentEvent event) {
        process(event, false);
    }

    @Override
    public void cleanup(DeploymentEvent event) {
        validate(event, false);
        kubernetes.undeploy(event);
    }

    private void validate(DeploymentEvent event, boolean deploy) {
        if (event == null) throw new IllegalArgumentException("Empty deployment event");
        event.validate(deploy);
    }

    private void process(DeploymentEvent event, boolean deploy) {
        validate(event, deploy);
        if (!statusPort.pending(event, deploy)) return;
        DeploymentStatus status = deploy ? DeploymentStatus.DEPLOYED : DeploymentStatus.NOT_DEPLOYED;
        String message = deploy ? "Kubernetes rollout ready" : "Kubernetes resources removed";
        try {
            if (deploy) kubernetes.deploy(event);
            else kubernetes.undeploy(event);
        } catch (RuntimeException ex) {
            status = DeploymentStatus.DEPLOYMENT_FAILED;
            message = ex.getMessage() == null ? "Kubernetes operation failed" : ex.getMessage();
            message = message.substring(0, Math.min(2000, message.length()));
        }
        // Callback failures escape so Kafka retries; Kubernetes operations are idempotent.
        statusPort.callback(event, status.name(), message);
    }
}
