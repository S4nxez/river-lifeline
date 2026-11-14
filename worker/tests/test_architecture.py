"""Enforces the clean architecture dependency rule: each layer may only import the layers inside it."""

import ast
from pathlib import Path

import pytest

PACKAGE = Path(__file__).parents[1] / "src" / "worker"

# Layer -> the worker layers its modules must not import
FORBIDDEN = {
    "domain": {"application", "adapters", "entrypoints", "config"},
    "application": {"adapters", "entrypoints", "config"},
    "adapters": {"entrypoints", "config"},
}


def worker_imports(module: Path) -> set[str]:
    """The top-level worker layers a module imports (worker.domain.jobs -> domain)."""
    layers = set()
    for node in ast.walk(ast.parse(module.read_text())):
        names = [alias.name for alias in node.names] if isinstance(node, ast.Import) else []
        if isinstance(node, ast.ImportFrom) and node.module:
            names = [node.module]
        layers |= {name.split(".")[1] for name in names if name.startswith("worker.") and name.count(".") >= 1}
    return layers


@pytest.mark.parametrize("layer", FORBIDDEN)
def test_layers_only_depend_inwards(layer):
    violations = {
        str(module.relative_to(PACKAGE)): sorted(worker_imports(module) & FORBIDDEN[layer])
        for module in (PACKAGE / layer).rglob("*.py")
    }

    assert {module: layers for module, layers in violations.items() if layers} == {}


def test_the_domain_has_no_third_party_dependencies():
    allowed = {"dataclasses", "datetime", "enum", "pathlib", "typing", "worker", "__future__"}
    for module in (PACKAGE / "domain").rglob("*.py"):
        for node in ast.walk(ast.parse(module.read_text())):
            if isinstance(node, ast.ImportFrom) and node.module:
                assert node.module.split(".")[0] in allowed, f"{module.name} imports {node.module}"
            if isinstance(node, ast.Import):
                for alias in node.names:
                    assert alias.name.split(".")[0] in allowed, f"{module.name} imports {alias.name}"
