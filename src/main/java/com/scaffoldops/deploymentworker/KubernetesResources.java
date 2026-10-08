package com.scaffoldops.deploymentworker;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Map;
import java.util.List;
@Component
public class KubernetesResources {
 private final Kubectl kubectl; private final ObjectMapper json; private final int port;
 public KubernetesResources(Kubectl kubectl,ObjectMapper json,@Value("${app.kubernetes.container-port:8080}") int port) {
  this.kubectl=kubectl;this.json=json;this.port=port;
 }
 public Map<String,String> labels(DeploymentEvent e) { return Map.of("app.kubernetes.io/name",e.resourceName(),"app.kubernetes.io/managed-by","scaffoldops","scaffoldops.io/request-id",e.generationRequestId().toString()); }
 public Map<String,Object> manifests(DeploymentEvent e) {
  var labels=labels(e);var selector=Map.of("scaffoldops.io/request-id",e.generationRequestId().toString());
  var metadata=Map.of("name",e.resourceName(),"namespace",e.namespace(),"labels",labels);
  var deployment=Map.of("apiVersion","apps/v1","kind","Deployment","metadata",metadata,"spec",Map.of("replicas",e.replicas(),"selector",Map.of("matchLabels",selector),"template",Map.of("metadata",Map.of("labels",labels),"spec",Map.of("containers",List.of(Map.of("name","service","image",e.imageRef(),"ports",List.of(Map.of("containerPort",port)),"readinessProbe",Map.of("tcpSocket",Map.of("port",port),"initialDelaySeconds",5,"periodSeconds",5)))))));
  var service=Map.of("apiVersion","v1","kind","Service","metadata",metadata,"spec",Map.of("type","ClusterIP","selector",selector,"ports",List.of(Map.of("port",port,"targetPort",port))));
  return Map.of("apiVersion","v1","kind","List","items",List.of(deployment,service));
 }
 public void deploy(DeploymentEvent e) {
  // Fail closed if deterministic names belong to a different owner.
  String existing=kubectl.run(null,"-n",e.namespace(),"get","deployment,service",e.resourceName(),"--ignore-not-found","-o","json");
  try {
   var resources=json.readTree(existing).path("items");
   for(var resource:resources) {
    var l=resource.path("metadata").path("labels");
    if(!e.generationRequestId().toString().equals(l.path("scaffoldops.io/request-id").asText()) || !"scaffoldops".equals(l.path("app.kubernetes.io/managed-by").asText()))
     throw new IllegalStateException("Existing Kubernetes resource has another owner");
   }
   kubectl.run(json.writeValueAsString(manifests(e)),"apply","-f","-");
  } catch(JsonProcessingException ex) { throw new IllegalStateException("Invalid Kubernetes JSON",ex); }
  kubectl.run(null,"-n",e.namespace(),"rollout","status","deployment/"+e.resourceName(),"--timeout=120s");
 }
 public void undeploy(DeploymentEvent e) {
  kubectl.run(null,"-n",e.namespace(),"delete","deployment,service","-l","scaffoldops.io/request-id="+e.generationRequestId()+",app.kubernetes.io/managed-by=scaffoldops","--ignore-not-found=true","--wait=true","--timeout=120s");
 }
}
