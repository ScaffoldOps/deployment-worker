package com.scaffoldops.deploymentworker.domain.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DeploymentEvent(UUID generationRequestId, String name, String artifactRef, String imageRef,
        String namespace, Integer replicas, OffsetDateTime requestedAt) {

    public void validate(boolean deploy) {
        if (generationRequestId == null || name == null || name.isBlank() || requestedAt == null
                || namespace == null || namespace.length() > 63
                || !namespace.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?")) {
            throw new IllegalArgumentException("Invalid request identity, namespace or timestamp");
        }
        if (deploy && (artifactRef == null || artifactRef.isBlank() || imageRef == null || imageRef.isBlank()
                || replicas == null || replicas < 1 || replicas > 20)) {
            throw new IllegalArgumentException("Invalid deployment references or replicas");
        }
    }

    public String resourceName() {
        String prefix = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-")
                .replaceAll("^-+|-+$", "");
        if (prefix.isEmpty()) prefix = "service";
        prefix = prefix.substring(0, Math.min(30, prefix.length())).replaceAll("-+$", "");
        return prefix + "-" + generationRequestId.toString().replace("-", "");
    }
}
