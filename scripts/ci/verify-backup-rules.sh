#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  printf 'Usage: %s FULL_BACKUP_RULES_XML DATA_EXTRACTION_RULES_XML\n' "${0##*/}" >&2
}

if (( $# != 2 )); then
  usage
  exit 2
fi
for rules_file in "$@"; do
  if [[ ! -r "${rules_file}" ]]; then
    printf 'Backup rules file not found: %s\n' "${rules_file}" >&2
    exit 1
  fi
done

python3 - "$1" "$2" <<'PY'
import pathlib
import sys
import xml.etree.ElementTree as ET

REQUIRED_DOMAINS = {
    "root",
    "file",
    "database",
    "sharedpref",
    "external",
    "device_root",
    "device_file",
    "device_database",
    "device_sharedpref",
}


def parse(path_text: str) -> ET.Element:
    path = pathlib.Path(path_text)
    try:
        return ET.parse(path).getroot()
    except (ET.ParseError, OSError) as exc:
        raise SystemExit(f"Cannot parse backup-rule metadata: {exc}") from exc


def assert_excludes_everything(parent: ET.Element, label: str) -> None:
    if any(child.tag == "include" for child in parent):
        raise SystemExit(f"{label} must not contain backup <include> rules")
    excluded = {
        child.attrib.get("domain")
        for child in parent
        if child.tag == "exclude" and child.attrib.get("path") == "."
    }
    missing = sorted(REQUIRED_DOMAINS - excluded)
    if missing:
        raise SystemExit(f"{label} does not exclude all data for: {', '.join(missing)}")


full_backup = parse(sys.argv[1])
if full_backup.tag != "full-backup-content":
    raise SystemExit("Legacy backup rules must use <full-backup-content>")
assert_excludes_everything(full_backup, "full-backup-content")

data_extraction = parse(sys.argv[2])
if data_extraction.tag != "data-extraction-rules":
    raise SystemExit("Modern backup rules must use <data-extraction-rules>")
for section_name in ("cloud-backup", "device-transfer"):
    section = data_extraction.find(section_name)
    if section is None:
        raise SystemExit(f"data-extraction-rules is missing <{section_name}>")
    assert_excludes_everything(section, section_name)

print("Verified backup metadata: legacy, cloud backup, and device transfer exclude all app domains")
PY
