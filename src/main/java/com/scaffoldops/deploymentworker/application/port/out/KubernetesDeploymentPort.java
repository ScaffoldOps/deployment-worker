package com.scaffoldops.deploymentworker.application.port.out;

import com.scaffoldops.deploymentworker.domain.event.DeploymentEvent;

public interface KubernetesDeploymentPort {
    void deploy(DeploymentEvent event);
    void undeploy(DeploymentEvent event);
}
