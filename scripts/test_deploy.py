import contextlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import unittest
from unittest.mock import patch

import deploy


DIGEST = "sha256:" + "a" * 64


class DeployTest(unittest.TestCase):
    def environment(self, name="DEV"):
        namespace, generated, _ = deploy.ENVIRONMENTS[name]
        return dict(ENVIRONMENT_NAME=name, NAMESPACE=namespace,
                    GENERATED_NAMESPACE=generated, IMAGE_DIGEST=DIGEST)

    def resources(self, name="DEV"):
        namespace, generated, _ = deploy.ENVIRONMENTS[name]
        return [
            {"apiVersion": "v1", "kind": "Namespace", "metadata": {"name": generated}},
            {"apiVersion": "apps/v1", "kind": "Deployment",
             "metadata": {"name": "deployment-worker", "namespace": namespace},
             "spec": {"template": {"spec": {"containers": [
                 {"name": "deployment-worker", "image": f"{deploy.IMAGE}@{DIGEST}"},
             ]}}}},
        ]

    def test_create_and_replace_use_one_deployment_write_with_digest(self):
        for name in deploy.ENVIRONMENTS:
            for version in ("", "123"):
                with self.subTest(environment=name, version=version):
                    resources = self.resources(name)
                    def respond(*args, payload=None):
                        return version if args[:2] == ("get", "deployment") else ""
                    with patch.object(deploy, "render", return_value=resources) as render, \
                            patch.object(deploy, "kubectl", side_effect=respond) as kubectl, \
                            contextlib.redirect_stdout(io.StringIO()):
                        deploy.deploy(self.environment(name))
                    render.assert_called_once_with(name.lower(), DIGEST)
                    writes = [call for call in kubectl.call_args_list
                              if call.args[0] in ("apply", "create", "replace", "patch", "set")]
                    self.assertEqual(len(writes), 2)
                    prerequisites = json.loads(writes[0].kwargs["payload"])
                    self.assertFalse(any(item["kind"] == "Deployment" for item in prerequisites["items"]))
                    self.assertEqual(writes[1].args[0], "replace" if version else "create")
                    desired = json.loads(writes[1].kwargs["payload"])
                    self.assertEqual(desired["metadata"].get("resourceVersion", ""), version)
                    pod = desired["spec"]["template"]["spec"]
                    self.assertEqual(pod["containers"][0]["image"], f"{deploy.IMAGE}@{DIGEST}")
                    self.assertNotIn("volumes", pod)
                    self.assertNotIn("volumeMounts", pod["containers"][0])
                    self.assertNotIn("KUBECONFIG", writes[1].kwargs["payload"])
                    self.assertFalse(any("secret" in arg for call in kubectl.call_args_list for arg in call.args))

    def test_invalid_environment_or_mutable_image_fails_before_kubectl(self):
        for override in ({"NAMESPACE": "scaffoldops-pre"},
                         {"GENERATED_NAMESPACE": "generated-pre"},
                         {"ENVIRONMENT_NAME": "PROD"},
                         {"IMAGE_DIGEST": "latest"}, {"IMAGE_DIGEST": "sha256:bad"}):
            with self.subTest(override=override), patch.object(deploy, "kubectl") as kubectl:
                with self.assertRaises(ValueError):
                    deploy.deploy(self.environment() | override)
                kubectl.assert_not_called()

    def test_wrong_rendered_image_fails_before_any_cluster_write(self):
        resources = self.resources()
        resources[1]["spec"]["template"]["spec"]["containers"][0]["image"] = "wrong:latest"
        with patch.object(deploy, "render", return_value=resources), patch.object(deploy, "kubectl") as kubectl:
            with self.assertRaises(ValueError):
                deploy.deploy(self.environment())
            kubectl.assert_not_called()

    def test_replace_failure_does_not_force_delete_or_restart(self):
        def respond(*args, payload=None):
            if args[0] == "replace":
                raise subprocess.CalledProcessError(1, "kubectl replace")
            return "123" if args[:2] == ("get", "deployment") else ""
        with patch.object(deploy, "render", return_value=self.resources()), \
                patch.object(deploy, "kubectl", side_effect=respond) as kubectl:
            with self.assertRaises(subprocess.CalledProcessError):
                deploy.deploy(self.environment())
            self.assertEqual(kubectl.call_args_list[-1].args[0], "replace")

    def test_render_uses_structured_image_override_and_no_apply(self):
        for name in deploy.ENVIRONMENTS:
            resources = self.resources(name)
            def respond(*args, payload=None):
                if args[0] == "kustomize":
                    config = json.loads(Path(args[1], "kustomization.yaml").read_text())
                    self.assertEqual(len(config["resources"]), 1)
                    self.assertEqual((Path(args[1]) / config["resources"][0]).resolve(),
                                     deploy.ROOT / "k8s" / "overlays" / name.lower())
                    self.assertEqual(config["images"], [{"name": deploy.IMAGE, "newName": deploy.IMAGE, "digest": DIGEST}])
                    return "rendered YAML"
                self.assertEqual(args, ("create", "--dry-run=client", "--validate=false", "-f", "-", "-o", "json"))
                self.assertEqual(payload, "rendered YAML")
                return json.dumps({"kind": "List", "items": resources})
            with patch.object(deploy, "kubectl", side_effect=respond):
                self.assertEqual(deploy.render(name.lower(), DIGEST), resources)

    def test_render_accepts_pretty_printed_kubectl_json_stream(self):
        for name in deploy.ENVIRONMENTS:
            resources = self.resources(name)
            resources[1]["metadata"]["annotations"] = {"example": 'braces }{ and quote " in a value'}
            output = "\n" + "\n".join(json.dumps(item, indent=4) for item in resources) + "\n\t "
            with self.subTest(environment=name), patch.object(
                    deploy, "kubectl", side_effect=["rendered YAML", output]):
                self.assertEqual(deploy.render(name.lower(), DIGEST), resources)

    def test_decode_resources_supports_single_objects_lists_and_streams(self):
        resources = self.resources()
        for output in (json.dumps({"kind": "List", "items": resources}),
                       "".join(json.dumps(item) for item in resources),
                       json.dumps({"kind": "List", "items": resources[:1]}) + "\r\n"
                       + json.dumps(resources[1])):
            with self.subTest(output=output):
                self.assertEqual(deploy.decode_resources(output), resources)
        self.assertEqual(deploy.decode_resources(json.dumps(resources[0])), resources[:1])
        self.assertEqual(deploy.decode_resources(" \r\n\t"), [])

    def test_decode_resources_rejects_truncated_or_non_json_trailing_output(self):
        valid = json.dumps(self.resources()[0])
        for suffix in ('\n{"kind":', "\nnot JSON"):
            with self.subTest(suffix=suffix), self.assertRaises(json.JSONDecodeError):
                deploy.decode_resources(valid + suffix)

    @unittest.skipUnless(os.environ.get("DEPLOYMENT_WORKER_CLIENT_RENDER_TEST") == "1",
                         "set DEPLOYMENT_WORKER_CLIENT_RENDER_TEST=1 for real kubectl client dry-run")
    def test_real_kubectl_client_render_both_environments(self):
        # Uses configured cluster discovery only; create is --dry-run=client.
        for name, (namespace, generated, overlay) in deploy.ENVIRONMENTS.items():
            with self.subTest(environment=name):
                resources = deploy.render(overlay, DIGEST)
                self.assertEqual(len(resources), 5)
                self.assertEqual({item["kind"] for item in resources},
                                 {"Namespace", "ServiceAccount", "Role", "RoleBinding", "Deployment"})
                for item in resources:
                    kind = item["kind"]
                    if kind == "Namespace":
                        self.assertEqual(item["metadata"]["name"], generated)
                    else:
                        self.assertEqual(item["metadata"]["namespace"],
                                         generated if kind in ("Role", "RoleBinding") else namespace)
                    if kind == "RoleBinding":
                        self.assertEqual(item["subjects"][0]["namespace"], namespace)
                    if kind == "Deployment":
                        container, = item["spec"]["template"]["spec"]["containers"]
                        self.assertEqual(container["image"], f"{deploy.IMAGE}@{DIGEST}")

    @unittest.skipUnless(shutil.which("kubectl"), "kubectl is needed for offline Kustomize checks")
    def test_real_kustomize_renders_both_digest_overlays_offline(self):
        real_kubectl = deploy.kubectl
        for name, (namespace, generated, overlay) in deploy.ENVIRONMENTS.items():
            with self.subTest(environment=name):
                def offline(*args, payload=None):
                    if args[0] == "kustomize":
                        return real_kubectl(*args, payload=payload)
                    self.assertEqual(args[:2], ("create", "--dry-run=client"))
                    self.assertIn(f"image: {deploy.IMAGE}@{DIGEST}", payload)
                    self.assertIn(f"namespace: {namespace}\n", payload)
                    self.assertIn(f"namespace: {generated}\n", payload)
                    other = "pre" if name == "DEV" else "dev"
                    self.assertNotIn(f"scaffoldops-{other}", payload)
                    self.assertNotIn(f"generated-{other}", payload)
                    self.assertIn("http://generator-api-service/api/generator/v1", payload)
                    self.assertNotIn("KUBECONFIG", payload)
                    self.assertNotIn("must-be-set-by-deployer", payload)
                    return json.dumps({"kind": "List", "items": []})
                with patch.object(deploy, "kubectl", side_effect=offline):
                    deploy.render(overlay, DIGEST)


if __name__ == "__main__":
    unittest.main()
