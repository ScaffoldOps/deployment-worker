package com.scaffoldops.deploymentworker.domain.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.UUID;
import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.*;
class DeploymentEventTest {
 DeploymentEvent event=new DeploymentEvent(UUID.fromString("a4bc394a-0888-494d-9769-30e2333a341d"),"Hello World!", "s3://a/b", "docker.io/a/b:tag", "generated-dev",1,OffsetDateTime.now(java.time.ZoneOffset.UTC));

 @Test void namesAreSafeDeterministicAndUnique() {
  assertThat(event.resourceName()).matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?").hasSizeLessThanOrEqualTo(63);
  assertThat(event.resourceName()).isEqualTo(event.resourceName());
  var other=new DeploymentEvent(UUID.randomUUID(),event.name(),null,null,event.namespace(),null,event.requestedAt());
  assertThat(other.resourceName()).isNotEqualTo(event.resourceName());
  new DeploymentEvent(event.generationRequestId(),"!!!",null,null,event.namespace(),null,event.requestedAt()).validate(false);
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
}
