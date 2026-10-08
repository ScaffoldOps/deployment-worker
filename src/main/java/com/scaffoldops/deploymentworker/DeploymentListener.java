package com.scaffoldops.deploymentworker;
import org.springframework.stereotype.Component;
import org.springframework.kafka.annotation.KafkaListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
@Component
public class DeploymentListener {
 private final ObjectMapper json;private final KubernetesResources resources;private final GeneratorApi api;
 public DeploymentListener(ObjectMapper json,KubernetesResources resources,GeneratorApi api) {this.json=json;this.resources=resources;this.api=api;}
 @KafkaListener(topics="${app.kafka.topics.deployment-requested}")
 public void deploy(String payload) { process(payload,true); }
 @KafkaListener(topics="${app.kafka.topics.undeployment-requested}")
 public void undeploy(String payload) { process(payload,false); }
 @KafkaListener(topics="${app.kafka.topics.artifact-cleanup-requested:artifact-cleanup-requested}")
 public void cleanup(String payload) {
  try {
   var node=json.readTree(payload);
   if(node==null || node.path("deploymentNamespace").isNull() || node.path("deploymentNamespace").asText().isBlank()) return;
   var e=new DeploymentEvent(java.util.UUID.fromString(node.path("requestId").asText()),node.path("name").asText(),null,null,
    node.path("deploymentNamespace").asText(),null,java.time.OffsetDateTime.parse(node.path("deletedAt").asText()));
   e.validate(false);resources.undeploy(e);
  } catch(JsonProcessingException | java.time.format.DateTimeParseException ex) {throw new IllegalArgumentException("Invalid cleanup event",ex);}
 }
 private void process(String payload,boolean deploy) {
  DeploymentEvent e;
  try { e=json.readValue(payload,DeploymentEvent.class); } catch(JsonProcessingException ex) {throw new IllegalArgumentException("Invalid deployment event JSON",ex);}
  if(e==null) throw new IllegalArgumentException("Empty deployment event");
  e.validate(deploy);
  if(!api.pending(e,deploy)) return;
  String status=deploy?"DEPLOYED":"NOT_DEPLOYED",message=deploy?"Kubernetes rollout ready":"Kubernetes resources removed";
  try { if(deploy) resources.deploy(e);else resources.undeploy(e); }
  catch(RuntimeException ex) {status="DEPLOYMENT_FAILED";message=ex.getMessage()==null?"Kubernetes operation failed":ex.getMessage();message=message.substring(0,Math.min(2000,message.length()));}
  // Callback failures escape so Kafka retries; repeated Kubernetes operations are idempotent.
  api.callback(e,status,message);
 }
}
