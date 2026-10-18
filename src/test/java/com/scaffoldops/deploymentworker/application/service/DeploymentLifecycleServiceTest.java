package com.scaffoldops.deploymentworker.application.service;

import com.scaffoldops.deploymentworker.application.port.out.GenerationRequestStatusPort;

import com.scaffoldops.deploymentworker.application.port.out.KubernetesDeploymentPort;
import com.scaffoldops.deploymentworker.domain.event.DeploymentEvent;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeploymentLifecycleServiceTest {
    private final KubernetesDeploymentPort kubernetes = mock(KubernetesDeploymentPort.class);
    private final GenerationRequestStatusPort status = mock(GenerationRequestStatusPort.class);
    private final DeploymentLifecycleService service = new DeploymentLifecycleService(kubernetes, status);
    private final DeploymentEvent event = new DeploymentEvent(UUID.randomUUID(), "hello", "s3://a/b",
            "image:tag", "generated-dev", 1, OffsetDateTime.parse("2026-10-08T12:00:00Z"));

    @Test void checksPendingBeforeDeploymentAndReportsReadinessAfterwards() {
        when(status.pending(event, true)).thenReturn(true);
        service.deploy(event);
        var order = inOrder(status, kubernetes);
        order.verify(status).pending(event, true);
        order.verify(kubernetes).deploy(event);
        order.verify(status).callback(event, "DEPLOYED", "Kubernetes rollout ready");
        order.verifyNoMoreInteractions();
    }

    @Test void pendingCheckFailureEscapesWithoutKubernetesWritesOrCallback() {
        when(status.pending(event, true)).thenThrow(new IllegalStateException("not yet visible"));
        assertThatThrownBy(() -> service.deploy(event)).hasMessage("not yet visible");
        verifyNoInteractions(kubernetes);
        verify(status).pending(event, true);
        verifyNoMoreInteractions(status);
    }

    @Test void successfulDeploymentCallbackFailureEscapesForRetry() {
        when(status.pending(event, true)).thenReturn(true);
        doThrow(new IllegalStateException("API offline")).when(status)
                .callback(event, "DEPLOYED", "Kubernetes rollout ready");
        assertThatThrownBy(() -> service.deploy(event)).hasMessage("API offline");
        verify(kubernetes).deploy(event);
    }

    @Test void validatesDirectUseCaseCallsBeforeExternalOperations() {
        var invalid = new DeploymentEvent(event.generationRequestId(), event.name(), event.artifactRef(),
                event.imageRef(), "../bad", 1, event.requestedAt());
        assertThatThrownBy(() -> service.deploy(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.undeploy(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.cleanup(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.deploy(null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(kubernetes, status);
    }

    @Test void cleanupFailuresEscapeWithoutStatusChecksOrCallbacks() {
        doThrow(new IllegalStateException("delete failed")).when(kubernetes).undeploy(event);
        assertThatThrownBy(() -> service.cleanup(event)).hasMessage("delete failed");
        verifyNoInteractions(status);
    }

    @Test void failureMessagesRetainFallbackAndLengthLimit() {
        when(status.pending(event, true)).thenReturn(true);
        doThrow(new IllegalStateException()).when(kubernetes).deploy(event);
        service.deploy(event);
        verify(status).callback(event, "DEPLOYMENT_FAILED", "Kubernetes operation failed");
        doThrow(new IllegalStateException("x".repeat(2001))).when(kubernetes).deploy(event);
        service.deploy(event);
        verify(status).callback(event, "DEPLOYMENT_FAILED", "x".repeat(2000));
    }
}
