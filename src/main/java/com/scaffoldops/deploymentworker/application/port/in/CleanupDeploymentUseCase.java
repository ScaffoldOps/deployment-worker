package com.scaffoldops.deploymentworker.application.port.in;

import com.scaffoldops.deploymentworker.domain.event.DeploymentEvent;

public interface CleanupDeploymentUseCase {
    void cleanup(DeploymentEvent event);
}
