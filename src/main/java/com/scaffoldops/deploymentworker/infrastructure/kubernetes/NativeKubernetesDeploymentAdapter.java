package com.scaffoldops.deploymentworker.infrastructure.kubernetes;

import com.scaffoldops.deploymentworker.application.port.out.KubernetesDeploymentPort;
import com.scaffoldops.deploymentworker.domain.event.DeploymentEvent;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.PatchUtils;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class NativeKubernetesDeploymentAdapter implements KubernetesDeploymentPort {
    private final AppsV1Api apps;
    private final CoreV1Api core;
    private final KubernetesResourceFactory factory;
    private final Duration timeout;
    private final Duration pollInterval;

    public NativeKubernetesDeploymentAdapter(AppsV1Api apps, CoreV1Api core, KubernetesResourceFactory factory,
            @Value("${app.kubernetes.rollout-timeout:120s}") Duration timeout,
            @Value("${app.kubernetes.poll-interval:1s}") Duration pollInterval) {
        if (timeout.isNegative() || timeout.isZero() || pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("Kubernetes timeouts must be positive");
        }
        this.apps = apps;
        this.core = core;
        this.factory = factory;
        this.timeout = timeout;
        this.pollInterval = pollInterval;
    }

    @Override
    public void deploy(DeploymentEvent event) {
        try {
            var existingDeployment = absentOn404(() -> apps.readNamespacedDeployment(event.resourceName(), event.namespace()).execute());
            var existingService = absentOn404(() -> core.readNamespacedService(event.resourceName(), event.namespace()).execute());
            validateOwner(existingDeployment == null ? null : existingDeployment.getMetadata(), existingDeployment != null, event);
            validateOwner(existingService == null ? null : existingService.getMetadata(), existingService != null, event);
            var deployment = factory.deployment(event);
            var service = factory.service(event);
            V1Deployment applied;
            if (existingDeployment == null) {
                applied = apps.createNamespacedDeployment(event.namespace(), deployment).execute();
            } else {
                deployment.getMetadata().resourceVersion(existingDeployment.getMetadata().getResourceVersion());
                applied = PatchUtils.patch(V1Deployment.class,
                        () -> apps.patchNamespacedDeployment(event.resourceName(), event.namespace(),
                                new V1Patch(apps.getApiClient().getJSON().serialize(deployment))).buildCall(null),
                        V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH, apps.getApiClient());
            }
            if (existingService == null) {
                core.createNamespacedService(event.namespace(), service).execute();
            } else {
                service.getMetadata().resourceVersion(existingService.getMetadata().getResourceVersion());
                PatchUtils.patch(V1Service.class,
                        () -> core.patchNamespacedService(event.resourceName(), event.namespace(),
                                new V1Patch(core.getApiClient().getJSON().serialize(service))).buildCall(null),
                        V1Patch.PATCH_FORMAT_STRATEGIC_MERGE_PATCH, core.getApiClient());
            }
            waitForRollout(event, applied.getMetadata().getGeneration());
        } catch (ApiException ex) {
            throw failure(ex);
        }
    }

    private void validateOwner(V1ObjectMeta metadata, boolean exists, DeploymentEvent event) {
        if (!exists) return;
        var labels = metadata == null ? null : metadata.getLabels();
        if (labels == null || !event.generationRequestId().toString().equals(labels.get("scaffoldops.io/request-id"))
                || !"scaffoldops".equals(labels.get("app.kubernetes.io/managed-by"))) {
            throw new IllegalStateException("Existing Kubernetes resource has another owner");
        }
    }

    private void waitForRollout(DeploymentEvent event, Long generation) throws ApiException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            var deployment = apps.readNamespacedDeployment(event.resourceName(), event.namespace()).execute();
            validateOwner(deployment.getMetadata(), true, event);
            var status = deployment.getStatus();
            if (status != null && status.getObservedGeneration() != null && generation != null
                    && status.getObservedGeneration() >= generation) {
                if (status.getConditions() != null && status.getConditions().stream().anyMatch(c ->
                        "Progressing".equals(c.getType()) && "False".equals(c.getStatus())
                                && "ProgressDeadlineExceeded".equals(c.getReason()))) {
                    throw new IllegalStateException("Kubernetes Deployment exceeded its progress deadline");
                }
                int desired = event.replicas();
                if (count(status.getUpdatedReplicas()) == desired && count(status.getReplicas()) == desired
                        && count(status.getAvailableReplicas()) >= desired && count(status.getReadyReplicas()) >= desired) return;
            }
            pause(deadline, "Kubernetes Deployment readiness timed out");
        }
    }

    private int count(Integer value) { return value == null ? 0 : value; }

    @Override
    public void undeploy(DeploymentEvent event) {
        String selector = "scaffoldops.io/request-id=" + event.generationRequestId()
                + ",app.kubernetes.io/managed-by=scaffoldops";
        try {
            var deployments = apps.listNamespacedDeployment(event.namespace()).labelSelector(selector).execute();
            var services = core.listNamespacedService(event.namespace()).labelSelector(selector).execute();
            for (var resource : deployments.getItems()) {
                absentOn404(() -> apps.deleteNamespacedDeployment(resource.getMetadata().getName(), event.namespace())
                        .body(deleteOptions(resource.getMetadata())).execute());
            }
            for (var resource : services.getItems()) {
                absentOn404(() -> core.deleteNamespacedService(resource.getMetadata().getName(), event.namespace())
                        .body(deleteOptions(resource.getMetadata())).execute());
            }
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!apps.listNamespacedDeployment(event.namespace()).labelSelector(selector).execute().getItems().isEmpty()
                    || !core.listNamespacedService(event.namespace()).labelSelector(selector).execute().getItems().isEmpty()) {
                pause(deadline, "Kubernetes resource deletion timed out");
            }
        } catch (ApiException ex) {
            if (ex.getCode() != 404) throw failure(ex);
        }
    }

    private V1DeleteOptions deleteOptions(V1ObjectMeta metadata) {
        return new V1DeleteOptions().propagationPolicy("Foreground")
                .preconditions(new V1Preconditions().uid(metadata.getUid()).resourceVersion(metadata.getResourceVersion()));
    }

    private void pause(long deadline, String message) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IllegalStateException(message);
        try {
            java.util.concurrent.TimeUnit.NANOSECONDS.sleep(Math.min(pollInterval.toNanos(), remaining));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kubernetes operation interrupted", ex);
        }
    }

    private IllegalStateException failure(ApiException ex) {
        return new IllegalStateException("Kubernetes API failed (HTTP " + ex.getCode() + "): " + ex.getMessage(), ex);
    }

    private <T> T absentOn404(ApiOperation<T> operation) throws ApiException {
        try { return operation.execute(); }
        catch (ApiException ex) { if (ex.getCode() == 404) return null; throw ex; }
    }

    @FunctionalInterface
    private interface ApiOperation<T> { T execute() throws ApiException; }
}
