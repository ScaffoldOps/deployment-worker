package com.scaffoldops.deploymentworker.application.port.out;

import com.scaffoldops.deploymentworker.domain.event.DeploymentEvent;

public interface GenerationRequestStatusPort {
    boolean pending(DeploymentEvent event, boolean deploy);
    void callback(DeploymentEvent event, String status, String message);
}
