#!/usr/bin/env python3
"""Fail closed when ShareGuard's JSON/XML CycloneDX release evidence is incomplete."""

from __future__ import annotations

import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


EXPECTED_NAMESPACE = "http://cyclonedx.org/schema/bom/1.6"
EXPECTED_COMPONENTS = {
    "activity-compose",
    "barcode-scanning",
    "kotlin-stdlib",
    "room-runtime",
    "text-recognition",
    "work-runtime-ktx",
}


def fail(message: str) -> "NoReturn":
    raise SystemExit(f"Invalid ShareGuard SBOM: {message}")


def require_file(path: Path) -> None:
    if not path.is_file() or path.stat().st_size == 0:
        fail(f"missing or empty file: {path}")


def validate_component(component: dict[str, object], source: str) -> str:
    if component.get("type") != "application":
        fail(f"{source} root component is not an application")
    if component.get("group") != "app.shareguard":
        fail(f"{source} root component group is not app.shareguard")
    if component.get("name") != "ShareGuard":
        fail(f"{source} root component name is not ShareGuard")
    version = component.get("version")
    if not isinstance(version, str) or not version or version == "unspecified":
        fail(f"{source} root component version is missing")
    reference = component.get("bom-ref")
    if not isinstance(reference, str) or not reference:
        fail(f"{source} root component has no bom-ref")
    return reference


def load_json(path: Path) -> tuple[dict[str, object], set[str], dict[str, tuple[str, ...]]]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        fail(f"cannot parse JSON: {exc}")
    if not isinstance(data, dict):
        fail("JSON root is not an object")
    if data.get("bomFormat") != "CycloneDX" or data.get("specVersion") != "1.6":
        fail("JSON is not CycloneDX 1.6")
    if data.get("version") != 1:
        fail("JSON BOM version is not 1")
    if "serialNumber" in data:
        fail("JSON contains a nondeterministic serial number")

    metadata = data.get("metadata")
    if not isinstance(metadata, dict) or not isinstance(metadata.get("component"), dict):
        fail("JSON metadata root component is missing")
    root_ref = validate_component(metadata["component"], "JSON")

    components = data.get("components")
    if not isinstance(components, list) or not components:
        fail("JSON has no runtime components")
    component_names = {
        component.get("name")
        for component in components
        if isinstance(component, dict) and isinstance(component.get("name"), str)
    }
    missing = EXPECTED_COMPONENTS - component_names
    if missing:
        fail(f"JSON runtime graph omits expected components: {', '.join(sorted(missing))}")

    component_refs = {
        component.get("bom-ref")
        for component in components
        if isinstance(component, dict) and isinstance(component.get("bom-ref"), str)
    }
    if len(component_refs) != len(components):
        fail("JSON contains a missing or duplicate component bom-ref")
    known_refs = {root_ref, *component_refs}

    dependencies = data.get("dependencies")
    if not isinstance(dependencies, list) or not dependencies:
        fail("JSON has no dependency graph")
    dependency_map: dict[str, tuple[str, ...]] = {}
    for dependency in dependencies:
        if not isinstance(dependency, dict) or not isinstance(dependency.get("ref"), str):
            fail("JSON contains a dependency without a ref")
        reference = dependency["ref"]
        targets = dependency.get("dependsOn", [])
        if not isinstance(targets, list) or not all(isinstance(item, str) for item in targets):
            fail(f"JSON dependency {reference} has malformed dependsOn entries")
        if reference in dependency_map:
            fail(f"JSON contains duplicate dependency ref {reference}")
        dependency_map[reference] = tuple(targets)
    if root_ref not in dependency_map or not dependency_map[root_ref]:
        fail("JSON root component is disconnected from the runtime dependency graph")
    unknown_refs = (set(dependency_map) | {item for values in dependency_map.values() for item in values}) - known_refs
    if unknown_refs:
        fail(f"JSON graph references unknown components: {', '.join(sorted(unknown_refs)[:3])}")
    return data, component_refs, dependency_map


def load_xml(path: Path) -> tuple[set[str], dict[str, tuple[str, ...]]]:
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as exc:
        fail(f"cannot parse XML: {exc}")
    namespace = f"{{{EXPECTED_NAMESPACE}}}"
    if root.tag != f"{namespace}bom" or root.get("version") != "1":
        fail("XML is not a CycloneDX 1.6 BOM version 1")
    if root.get("serialNumber") is not None:
        fail("XML contains a nondeterministic serial number")

    root_component = root.find(f"{namespace}metadata/{namespace}component")
    if root_component is None:
        fail("XML metadata root component is missing")
    component_dict: dict[str, object] = {
        "type": root_component.get("type"),
        "bom-ref": root_component.get("bom-ref"),
    }
    for field in ("group", "name", "version"):
        element = root_component.find(f"{namespace}{field}")
        component_dict[field] = element.text if element is not None else None
    root_ref = validate_component(component_dict, "XML")

    components = root.findall(f"{namespace}components/{namespace}component")
    if not components:
        fail("XML has no runtime components")
    component_refs = {component.get("bom-ref") for component in components}
    if None in component_refs or len(component_refs) != len(components):
        fail("XML contains a missing or duplicate component bom-ref")

    dependency_map: dict[str, tuple[str, ...]] = {}
    for dependency in root.findall(f"{namespace}dependencies/{namespace}dependency"):
        reference = dependency.get("ref")
        if not reference or reference in dependency_map:
            fail("XML contains a missing or duplicate dependency ref")
        dependency_map[reference] = tuple(
            child.get("ref", "") for child in dependency.findall(f"{namespace}dependency")
        )
    if root_ref not in dependency_map or not dependency_map[root_ref]:
        fail("XML root component is disconnected from the runtime dependency graph")
    return {reference for reference in component_refs if reference}, dependency_map


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(f"Usage: {Path(sys.argv[0]).name} BOM_JSON BOM_XML")
    json_path, xml_path = map(Path, sys.argv[1:])
    require_file(json_path)
    require_file(xml_path)
    _, json_components, json_dependencies = load_json(json_path)
    xml_components, xml_dependencies = load_xml(xml_path)
    if json_components != xml_components:
        fail("JSON and XML component sets differ")
    if json_dependencies != xml_dependencies:
        fail("JSON and XML dependency graphs differ")
    print(
        f"Verified CycloneDX 1.6 application SBOM: "
        f"{len(json_components)} components, {len(json_dependencies)} dependency nodes"
    )


if __name__ == "__main__":
    main()
