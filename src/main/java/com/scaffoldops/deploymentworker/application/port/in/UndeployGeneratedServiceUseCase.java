package com.scaffoldops.deploymentworker.application.port.in;

import com.scaffoldops.deploymentworker.domain.event.DeploymentEvent;

public interface UndeployGeneratedServiceUseCase {
    void undeploy(DeploymentEvent event);
}
