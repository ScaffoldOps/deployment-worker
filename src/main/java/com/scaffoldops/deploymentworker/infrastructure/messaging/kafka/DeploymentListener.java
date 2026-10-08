package com.scaffoldops.deploymentworker.infrastructure.messaging.kafka;

import com.scaffoldops.deploymentworker.application.port.in.CleanupDeploymentUseCase;
import com.scaffoldops.deploymentworker.application.port.in.DeployGeneratedServiceUseCase;
import com.scaffoldops.deploymentworker.application.port.in.UndeployGeneratedServiceUseCase;
import com.scaffoldops.deploymentworker.domain.event.DeploymentEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class DeploymentListener {
    private final ObjectMapper json;
    private final DeployGeneratedServiceUseCase deployUseCase;
    private final UndeployGeneratedServiceUseCase undeployUseCase;
    private final CleanupDeploymentUseCase cleanupUseCase;

    public DeploymentListener(ObjectMapper json, DeployGeneratedServiceUseCase deployUseCase,
            UndeployGeneratedServiceUseCase undeployUseCase, CleanupDeploymentUseCase cleanupUseCase) {
        this.json = json;
        this.deployUseCase = deployUseCase;
        this.undeployUseCase = undeployUseCase;
        this.cleanupUseCase = cleanupUseCase;
    }

    @KafkaListener(topics = "${app.kafka.topics.deployment-requested}")
    public void deploy(String payload) {
        deployUseCase.deploy(readEvent(payload));
    }

    @KafkaListener(topics = "${app.kafka.topics.undeployment-requested}")
    public void undeploy(String payload) {
        undeployUseCase.undeploy(readEvent(payload));
    }

    @KafkaListener(topics = "${app.kafka.topics.artifact-cleanup-requested:artifact-cleanup-requested}")
    public void cleanup(String payload) {
        try {
            var node = json.readTree(payload);
            if (node == null || node.path("deploymentNamespace").isNull()
                    || node.path("deploymentNamespace").asText().isBlank()) return;
            var event = new DeploymentEvent(UUID.fromString(node.path("requestId").asText()),
                    node.path("name").asText(), null, null, node.path("deploymentNamespace").asText(),
                    null, OffsetDateTime.parse(node.path("deletedAt").asText()));
            cleanupUseCase.cleanup(event);
        } catch (JsonProcessingException | DateTimeParseException ex) {
            throw new IllegalArgumentException("Invalid cleanup event", ex);
        }
    }

    private DeploymentEvent readEvent(String payload) {
        try {
            return json.readValue(payload, DeploymentEvent.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid deployment event JSON", ex);
        }
    }
}
