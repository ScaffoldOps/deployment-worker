"""Render once with the pushed digest; reconcile only repository-owned resources."""

import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
IMAGE = "victodomvar/scaffoldops-deployment-worker"
ENVIRONMENTS = {
    "DEV": ("scaffoldops-dev", "generated-dev", "dev"),
    "PRE": ("scaffoldops-pre", "generated-pre", "pre"),
}


def kubectl(*args, payload=None):
    return subprocess.run(
        ["kubectl", *args], input=payload, text=True, check=True,
        stdout=subprocess.PIPE,
    ).stdout


def render(overlay, digest):
    with tempfile.TemporaryDirectory(prefix="deployment-worker-") as directory:
        # JSON is valid YAML; no additional runner libraries or global text substitutions.
        Path(directory, "kustomization.yaml").write_text(json.dumps({
            "apiVersion": "kustomize.config.k8s.io/v1beta1",
            "kind": "Kustomization",
            "resources": [os.path.relpath(ROOT / "k8s" / "overlays" / overlay, directory)],
            "images": [{"name": IMAGE, "newName": IMAGE, "digest": digest}],
        }), encoding="utf-8")
        manifests = kubectl("kustomize", directory)
    rendered = json.loads(kubectl(
        "create", "--dry-run=client", "--validate=false", "-f", "-", "-o", "json",
        payload=manifests,
    ))
    return rendered["items"] if rendered.get("kind") == "List" else [rendered]


def deploy(environ):
    environment = environ["ENVIRONMENT_NAME"]
    if environment not in ENVIRONMENTS:
        raise ValueError("Only DEV and PRE deployments are supported")
    namespace, generated_namespace, overlay = ENVIRONMENTS[environment]
    if (environ["NAMESPACE"], environ["GENERATED_NAMESPACE"]) != (namespace, generated_namespace):
        raise ValueError("Environment and namespace pair do not match")
    digest = environ["IMAGE_DIGEST"]
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
        raise ValueError("A pushed sha256 image digest is required")

    resources = render(overlay, digest)
    deployment, = [item for item in resources if item["kind"] == "Deployment"]
    if deployment["spec"]["template"]["spec"]["containers"][0]["image"] != f"{IMAGE}@{digest}":
        raise ValueError("Rendered image does not match the pushed digest")
    # Infrastructure owns the worker namespace and all credentials. Never read secrets.
    kubectl("get", "namespace", namespace, "-o", "name")
    prerequisites = [item for item in resources if item is not deployment]
    kubectl("apply", "-f", "-", payload=json.dumps({
        "apiVersion": "v1", "kind": "List", "items": prerequisites,
    }))
    version = kubectl(
        "get", "deployment", "deployment-worker", "-n", namespace,
        "--ignore-not-found", "-o", "jsonpath={.metadata.resourceVersion}",
    ).strip()
    if version:
        # A full, version-guarded replacement drops unmanaged kubectl/kubeconfig
        # workaround env/mounts/volumes in the same update as the correct image.
        deployment["metadata"]["resourceVersion"] = version
    kubectl("replace" if version else "create", "-f", "-", payload=json.dumps(deployment))
    print(kubectl(
        "rollout", "status", "deployment/deployment-worker", "-n", namespace,
        "--timeout=300s",
    ), end="")


if __name__ == "__main__":
    deploy(os.environ)
