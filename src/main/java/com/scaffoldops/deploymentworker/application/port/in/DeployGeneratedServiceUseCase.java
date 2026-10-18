package com.scaffoldops.deploymentworker.application.port.in;

import com.scaffoldops.deploymentworker.domain.event.DeploymentEvent;

public interface DeployGeneratedServiceUseCase {
    void deploy(DeploymentEvent event);
}
