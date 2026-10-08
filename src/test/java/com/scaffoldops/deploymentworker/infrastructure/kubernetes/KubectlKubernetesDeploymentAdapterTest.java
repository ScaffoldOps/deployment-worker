package com.scaffoldops.deploymentworker.infrastructure.kubernetes;

import com.scaffoldops.deploymentworker.domain.event.DeploymentEvent;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class KubectlKubernetesDeploymentAdapterTest {
 ObjectMapper json=new ObjectMapper().findAndRegisterModules();
 DeploymentEvent event=new DeploymentEvent(UUID.fromString("a4bc394a-0888-494d-9769-30e2333a341d"),"Hello World!", "s3://a/b", "docker.io/a/b:tag", "generated-dev",1,OffsetDateTime.now(java.time.ZoneOffset.UTC));

 @Test void deployAppliesImageReplicasServiceAndWaitsForReadiness() throws Exception {
  Kubectl k=mock(Kubectl.class);when(k.run(null,"-n",event.namespace(),"get","deployment,service",event.resourceName(),"--ignore-not-found","-o","json")).thenReturn("{\"items\":[]}");
  var resources=new KubectlKubernetesDeploymentAdapter(k,json,new KubernetesResourceFactory(8080));
  resources.deploy(event);resources.deploy(event);
  var manifest=json.readTree(json.writeValueAsString(new KubernetesResourceFactory(8080).manifests(event)));
  assertThat(manifest.at("/items/0/spec/replicas").asInt()).isEqualTo(1);
  assertThat(manifest.at("/items/0/spec/template/spec/containers/0/image").asText()).isEqualTo(event.imageRef());
  assertThat(manifest.at("/items/1/spec/type").asText()).isEqualTo("ClusterIP");
  verify(k,times(2)).run(json.writeValueAsString(new KubernetesResourceFactory(8080).manifests(event)),"apply","-f","-");
  verify(k,times(2)).run(null,"-n",event.namespace(),"rollout","status","deployment/"+event.resourceName(),"--timeout=120s");
 }
 @Test void refusesForeignResources() {
  Kubectl k=mock(Kubectl.class);when(k.run(null,"-n",event.namespace(),"get","deployment,service",event.resourceName(),"--ignore-not-found","-o","json")).thenReturn("{\"items\":[{\"metadata\":{}}]}");
  assertThatThrownBy(()->new KubectlKubernetesDeploymentAdapter(k,json,new KubernetesResourceFactory(8080)).deploy(event)).hasMessageContaining("another owner");
 }
 @Test void undeployMissingResourcesSucceedsWithOwnershipSelector() {
  Kubectl k=mock(Kubectl.class);new KubectlKubernetesDeploymentAdapter(k,json,new KubernetesResourceFactory(8080)).undeploy(event);
  verify(k).run(null,"-n",event.namespace(),"delete","deployment,service","-l","scaffoldops.io/request-id="+event.generationRequestId()+",app.kubernetes.io/managed-by=scaffoldops","--ignore-not-found=true","--wait=true","--timeout=120s");
 }
}
