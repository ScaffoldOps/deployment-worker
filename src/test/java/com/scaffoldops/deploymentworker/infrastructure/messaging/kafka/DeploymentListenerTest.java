package com.scaffoldops.deploymentworker.infrastructure.messaging.kafka;

import com.scaffoldops.deploymentworker.domain.event.DeploymentEvent;
import com.scaffoldops.deploymentworker.application.port.out.KubernetesDeploymentPort;
import com.scaffoldops.deploymentworker.application.port.out.GenerationRequestStatusPort;
import com.scaffoldops.deploymentworker.application.service.DeploymentLifecycleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class DeploymentListenerTest {
 ObjectMapper json=new ObjectMapper().findAndRegisterModules();
 DeploymentEvent event=new DeploymentEvent(UUID.fromString("a4bc394a-0888-494d-9769-30e2333a341d"),"Hello World!", "s3://a/b", "docker.io/a/b:tag", "generated-dev",1,OffsetDateTime.now(java.time.ZoneOffset.UTC));
 private DeploymentListener listener(KubernetesDeploymentPort resources, GenerationRequestStatusPort api) {
  var service=new DeploymentLifecycleService(resources,api);
  return new DeploymentListener(json,service,service,service);
 }
 @Test void listenerReportsSuccessFailureAndRetainsCallbackRetries() throws Exception {
  var resources=mock(KubernetesDeploymentPort.class);var api=mock(GenerationRequestStatusPort.class);when(api.pending(event,true)).thenReturn(true);when(api.pending(event,false)).thenReturn(true);
  var listener=listener(resources,api);String payload=json.writeValueAsString(event);
  listener.deploy(payload);verify(api).callback(event,"DEPLOYED","Kubernetes rollout ready");
  listener.undeploy(payload);verify(api).callback(event,"NOT_DEPLOYED","Kubernetes resources removed");
  doThrow(new IllegalStateException("image pull failed")).when(resources).deploy(event);
  listener.deploy(payload);verify(api).callback(event,"DEPLOYMENT_FAILED","image pull failed");
  doThrow(new IllegalStateException("delete failed")).when(resources).undeploy(event);
  listener.undeploy(payload);verify(api).callback(event,"DEPLOYMENT_FAILED","delete failed");
  doThrow(new IllegalStateException("API offline")).when(api).callback(event,"DEPLOYMENT_FAILED","image pull failed");
  assertThatThrownBy(()->listener.deploy(payload)).hasMessage("API offline");
 }
 @ParameterizedTest @ValueSource(strings={"null","{}","bad json","{\"namespace\":\"../bad\"}"})
 void invalidMessagesDoNotWriteResources(String payload) {
  var resources=mock(KubernetesDeploymentPort.class);var api=mock(GenerationRequestStatusPort.class);var listener=listener(resources,api);
  assertThatThrownBy(()->listener.deploy(payload)).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(resources,api);
 }
 @Test void permanentDeletionCleansKubernetesWithoutCallback() {
  var resources=mock(KubernetesDeploymentPort.class);var api=mock(GenerationRequestStatusPort.class);
  var listener=listener(resources,api);
  listener.cleanup("{\"requestId\":\"a4bc394a-0888-494d-9769-30e2333a341d\",\"name\":\"Hello World!\",\"deploymentNamespace\":\"generated-dev\",\"deletedAt\":\"2026-10-08T12:00:00Z\"}");
  verify(resources).undeploy(org.mockito.ArgumentMatchers.argThat(e->e.generationRequestId().equals(event.generationRequestId())));verifyNoInteractions(api);
 }
 @Test void supersededEventsDoNotWriteResources() throws Exception {
  var resources=mock(KubernetesDeploymentPort.class);var api=mock(GenerationRequestStatusPort.class);
  listener(resources,api).deploy(json.writeValueAsString(event));verifyNoInteractions(resources);
 }
 @ParameterizedTest @ValueSource(strings={"null","{}","{\"deploymentNamespace\":null}","{\"deploymentNamespace\":\"\"}"})
 void cleanupWithoutNamespaceDoesNothing(String payload) {
  var resources=mock(KubernetesDeploymentPort.class);var api=mock(GenerationRequestStatusPort.class);
  listener(resources,api).cleanup(payload);verifyNoInteractions(resources,api);
 }

}
