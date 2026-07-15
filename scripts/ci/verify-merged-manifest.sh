#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  printf 'Usage: %s MERGED_ANDROID_MANIFEST_XML [...]\n' "${0##*/}" >&2
}

if (( $# == 0 )); then
  usage
  exit 2
fi

for manifest in "$@"; do
  if [[ ! -r "${manifest}" ]]; then
    printf 'Merged manifest not found: %s\n' "${manifest}" >&2
    exit 1
  fi

  python3 - "${manifest}" <<'PY'
import pathlib
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
FORBIDDEN_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.CHANGE_NETWORK_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.CHANGE_WIFI_STATE",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.MANAGE_MEDIA",
    "android.permission.ACCESS_MEDIA_LOCATION",
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.READ_MEDIA_VIDEO",
    "android.permission.READ_MEDIA_AUDIO",
    "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
}

path = pathlib.Path(sys.argv[1])
try:
    root = ET.parse(path).getroot()
except (ET.ParseError, OSError) as exc:
    raise SystemExit(f"Cannot parse merged manifest metadata: {exc}") from exc

permission_tags = {
    "uses-permission",
    "uses-permission-sdk-23",
    "uses-permission-sdk-m",
}
permissions = {
    element.attrib.get(ANDROID + "name")
    for element in root
    if element.tag.rsplit("}", 1)[-1] in permission_tags
}
permissions.discard(None)
forbidden = sorted(permissions & FORBIDDEN_PERMISSIONS)
if forbidden:
    raise SystemExit("Forbidden merged permission(s): " + ", ".join(forbidden))

applications = [
    element for element in root if element.tag.rsplit("}", 1)[-1] == "application"
]
if len(applications) != 1:
    raise SystemExit(f"Expected exactly one merged <application>; found {len(applications)}")
application = applications[0]

allow_backup = application.attrib.get(ANDROID + "allowBackup")
if allow_backup != "false":
    raise SystemExit("Merged application must set android:allowBackup=\"false\"")
if ANDROID + "backupAgent" in application.attrib:
    raise SystemExit("Merged application must not configure android:backupAgent")
if not application.attrib.get(ANDROID + "fullBackupContent"):
    raise SystemExit("Merged application must reference restrictive fullBackupContent rules")
if not application.attrib.get(ANDROID + "dataExtractionRules"):
    raise SystemExit("Merged application must reference restrictive dataExtractionRules")

cleartext = application.attrib.get(ANDROID + "usesCleartextTraffic")
if cleartext != "false":
    raise SystemExit("Merged application must set android:usesCleartextTraffic=\"false\"")

package_name = root.attrib.get("package", "<unset>")
print(
    "Verified merged manifest metadata: "
    f"package={package_name} permissions={len(permissions)} "
    "allowBackup=false cleartext=false"
)
PY
done
