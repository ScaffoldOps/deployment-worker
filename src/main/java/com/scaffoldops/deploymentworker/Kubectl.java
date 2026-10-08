package com.scaffoldops.deploymentworker;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
@Component
public class Kubectl {
 private final String executable;
 public Kubectl(@Value("${app.kubernetes.kubectl:kubectl}") String executable) { this.executable=executable; }
 public String run(String input, String... args) {
  Path output=null;
  try {
   output=Files.createTempFile("deployment-kubectl-", ".log");
   List<String> command=new ArrayList<>();command.add(executable);command.add("--request-timeout=30s");command.addAll(List.of(args));
   Process process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
   try(var stdin=process.getOutputStream()) { if(input!=null) stdin.write(input.getBytes(StandardCharsets.UTF_8)); }
   if(!process.waitFor(150,TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("Kubernetes command timed out"); }
   String result=Files.readString(output);
   if(process.exitValue()!=0) throw new IllegalStateException("Kubernetes command failed: "+result.substring(0,Math.min(2000,result.length())));
   return result;
  } catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException("Kubernetes command interrupted",e);
  } catch(java.io.IOException e) { throw new IllegalStateException("Cannot execute kubectl",e);
  } finally { if(output!=null) try { Files.deleteIfExists(output); } catch(java.io.IOException ignored) {} }
 }
}
