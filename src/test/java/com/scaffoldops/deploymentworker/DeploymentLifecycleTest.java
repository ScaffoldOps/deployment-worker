package com.scaffoldops.deploymentworker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class DeploymentLifecycleTest {
 ObjectMapper json=new ObjectMapper().findAndRegisterModules();
 DeploymentEvent event=new DeploymentEvent(UUID.fromString("a4bc394a-0888-494d-9769-30e2333a341d"),"Hello World!", "s3://a/b", "docker.io/a/b:tag", "generated-dev",1,OffsetDateTime.now(java.time.ZoneOffset.UTC));
 @Test void namesAreSafeDeterministicAndUnique() {
  assertThat(event.resourceName()).matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?").hasSizeLessThanOrEqualTo(63);
  assertThat(event.resourceName()).isEqualTo(event.resourceName());
  var other=new DeploymentEvent(UUID.randomUUID(),event.name(),null,null,event.namespace(),null,event.requestedAt());
  assertThat(other.resourceName()).isNotEqualTo(event.resourceName());
  new DeploymentEvent(event.generationRequestId(),"!!!",null,null,event.namespace(),null,event.requestedAt()).validate(false);
 }
 @Test void deployAppliesImageReplicasServiceAndWaitsForReadiness() throws Exception {
  Kubectl k=mock(Kubectl.class);when(k.run(null,"-n",event.namespace(),"get","deployment,service",event.resourceName(),"--ignore-not-found","-o","json")).thenReturn("{\"items\":[]}");
  var resources=new KubernetesResources(k,json,8080);
  resources.deploy(event);resources.deploy(event);
  var manifest=json.readTree(json.writeValueAsString(resources.manifests(event)));
  assertThat(manifest.at("/items/0/spec/replicas").asInt()).isEqualTo(1);
  assertThat(manifest.at("/items/0/spec/template/spec/containers/0/image").asText()).isEqualTo(event.imageRef());
  assertThat(manifest.at("/items/1/spec/type").asText()).isEqualTo("ClusterIP");
  verify(k,times(2)).run(json.writeValueAsString(resources.manifests(event)),"apply","-f","-");
  verify(k,times(2)).run(null,"-n",event.namespace(),"rollout","status","deployment/"+event.resourceName(),"--timeout=120s");
 }
 @Test void refusesForeignResources() {
  Kubectl k=mock(Kubectl.class);when(k.run(null,"-n",event.namespace(),"get","deployment,service",event.resourceName(),"--ignore-not-found","-o","json")).thenReturn("{\"items\":[{\"metadata\":{}}]}");
  assertThatThrownBy(()->new KubernetesResources(k,json,8080).deploy(event)).hasMessageContaining("another owner");
 }
 @Test void undeployMissingResourcesSucceedsWithOwnershipSelector() {
  Kubectl k=mock(Kubectl.class);new KubernetesResources(k,json,8080).undeploy(event);
  verify(k).run(null,"-n",event.namespace(),"delete","deployment,service","-l","scaffoldops.io/request-id="+event.generationRequestId()+",app.kubernetes.io/managed-by=scaffoldops","--ignore-not-found=true","--wait=true","--timeout=120s");
 }
 @Test void listenerReportsSuccessFailureAndRetainsCallbackRetries() throws Exception {
  var resources=mock(KubernetesResources.class);var api=mock(GeneratorApi.class);when(api.pending(event,true)).thenReturn(true);when(api.pending(event,false)).thenReturn(true);
  var listener=new DeploymentListener(json,resources,api);String payload=json.writeValueAsString(event);
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
  var resources=mock(KubernetesResources.class);var api=mock(GeneratorApi.class);var listener=new DeploymentListener(json,resources,api);
  assertThatThrownBy(()->listener.deploy(payload)).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(resources,api);
 }
 @Test void permanentDeletionCleansKubernetesWithoutCallback() {
  var resources=mock(KubernetesResources.class);var api=mock(GeneratorApi.class);
  var listener=new DeploymentListener(json,resources,api);
  listener.cleanup("{\"requestId\":\"a4bc394a-0888-494d-9769-30e2333a341d\",\"name\":\"Hello World!\",\"deploymentNamespace\":\"generated-dev\",\"deletedAt\":\"2026-10-08T12:00:00Z\"}");
  verify(resources).undeploy(org.mockito.ArgumentMatchers.argThat(e->e.generationRequestId().equals(event.generationRequestId())));verifyNoInteractions(api);
 }
 @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={0,-1,21})
 void rejectsInvalidReplicaCounts(int replicas) {
  var e=new DeploymentEvent(event.generationRequestId(),event.name(),event.artifactRef(),event.imageRef(),event.namespace(),replicas,event.requestedAt());
  assertThatThrownBy(()->e.validate(true)).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void undeployDoesNotRequireArtifactOrImage() {
  var e=new DeploymentEvent(event.generationRequestId(),event.name(),null,null,event.namespace(),null,event.requestedAt());
  e.validate(false);assertThatThrownBy(()->e.validate(true)).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void supersededEventsDoNotWriteResources() throws Exception {
  var resources=mock(KubernetesResources.class);var api=mock(GeneratorApi.class);
  new DeploymentListener(json,resources,api).deploy(json.writeValueAsString(event));verifyNoInteractions(resources);
 }
}
