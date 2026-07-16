#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  printf 'Usage: %s RELEASE_TAG OUTPUT_DIRECTORY\n' "${0##*/}" >&2
}

verify_zip_archive() {
  python3 - "$1" <<'PY'
import sys
import zipfile

try:
    with zipfile.ZipFile(sys.argv[1]) as archive:
        corrupt_entry = archive.testzip()
except (OSError, zipfile.BadZipFile) as exc:
    raise SystemExit(f"Release archive is invalid: {exc}") from exc
if corrupt_entry is not None:
    raise SystemExit(f"Release archive contains a corrupt entry: {corrupt_entry}")
PY
}

contains_jar_signature() {
  python3 - "$1" <<'PY'
import re
import sys
import zipfile

signature = re.compile(r"^META-INF/[^/]+\.(RSA|DSA|EC)$", re.IGNORECASE)
with zipfile.ZipFile(sys.argv[1]) as archive:
    raise SystemExit(0 if any(signature.match(name) for name in archive.namelist()) else 1)
PY
}

if (( $# != 2 )); then
  usage
  exit 2
fi

release_tag=$1
output_dir=$2
if [[ ! "${release_tag}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]]; then
  echo "Release tag must match vMAJOR.MINOR.PATCH with an optional suffix." >&2
  exit 1
fi
version_name=$(sed -n 's/^shareguardVersionName=//p' gradle.properties)
if [[ -z "${version_name}" || "${release_tag}" != "v${version_name}" ]]; then
  printf 'Release tag %s does not match app version v%s.\n' \
    "${release_tag}" "${version_name:-missing}" >&2
  exit 1
fi
if [[ -e "${output_dir}" ]] && find "${output_dir}" -mindepth 1 -print -quit | grep -q .; then
  printf 'Output directory must be empty: %s\n' "${output_dir}" >&2
  exit 1
fi
mkdir -p -- "${output_dir}"

mapfile -d '' apk_inputs < <(
  find app/build/outputs/apk/release -maxdepth 1 -type f -name '*.apk' -print0
)
mapfile -d '' aab_inputs < <(
  find app/build/outputs/bundle/release -maxdepth 1 -type f -name '*.aab' -print0
)
mapfile -d '' sbom_inputs < <(
  find build/reports/cyclonedx -maxdepth 1 -type f \( -name 'bom.json' -o -name 'bom.xml' \) -print0
)
if (( ${#apk_inputs[@]} == 0 || ${#aab_inputs[@]} == 0 || ${#sbom_inputs[@]} != 2 )); then
  echo "Expected at least one release APK, one release AAB, and CycloneDX JSON/XML SBOMs." >&2
  exit 1
fi
python3 scripts/ci/verify-sbom.py \
  build/reports/cyclonedx/bom.json \
  build/reports/cyclonedx/bom.xml

present=0
[[ -n "${ANDROID_KEYSTORE_BASE64-}" ]] && ((present += 1))
[[ -n "${ANDROID_KEYSTORE_PASSWORD-}" ]] && ((present += 1))
[[ -n "${ANDROID_KEY_ALIAS-}" ]] && ((present += 1))
[[ -n "${ANDROID_KEY_PASSWORD-}" ]] && ((present += 1))
if (( present == 0 )); then
  signing_enabled=false
elif (( present == 4 )); then
  signing_enabled=true
else
  echo "Android signing secrets must be either all configured or all absent." >&2
  exit 1
fi

keystore=
cleanup() {
  if [[ -n "${keystore}" ]]; then
    rm -f -- "${keystore}"
  fi
}
trap cleanup EXIT

if [[ "${signing_enabled}" == true ]]; then
  keystore=$(mktemp)
  umask 077
  printf '%s' "${ANDROID_KEYSTORE_BASE64}" | base64 --decode > "${keystore}"
  if [[ ! -s "${keystore}" ]]; then
    echo "Decoded Android keystore is empty." >&2
    exit 1
  fi
fi

resolve_apksigner() {
  local candidate
  if [[ -n "${ANDROID_SDK_ROOT-}" ]]; then
    if [[ -n "${ANDROID_BUILD_TOOLS_VERSION-}" \
          && -x "${ANDROID_SDK_ROOT}/build-tools/${ANDROID_BUILD_TOOLS_VERSION}/apksigner" ]]; then
      printf '%s\n' \
        "${ANDROID_SDK_ROOT}/build-tools/${ANDROID_BUILD_TOOLS_VERSION}/apksigner"
      return
    fi
    candidate=$(
      find "${ANDROID_SDK_ROOT}/build-tools" -mindepth 2 -maxdepth 2 \
        -type f -name apksigner -perm -u+x -print 2>/dev/null \
        | sort -V | tail -n 1
    )
    if [[ -n "${candidate}" ]]; then
      printf '%s\n' "${candidate}"
      return
    fi
  fi
  if command -v apksigner >/dev/null 2>&1; then
    command -v apksigner
    return
  fi
  echo "Required Android SDK tool is unavailable: apksigner" >&2
  return 1
}

apksigner_tool=
if [[ "${signing_enabled}" == true ]]; then
  apksigner_tool=$(resolve_apksigner)
fi

safe_tag=${release_tag//[^0-9A-Za-z._-]/_}

for index in "${!apk_inputs[@]}"; do
  source_apk=${apk_inputs[$index]}
  suffix=
  if (( ${#apk_inputs[@]} > 1 )); then
    suffix="-$((index + 1))"
  fi
  if [[ "${signing_enabled}" == true ]]; then
    output_apk="${output_dir}/ShareGuard-${safe_tag}${suffix}.apk"
    "${apksigner_tool}" sign \
      --ks "${keystore}" \
      --ks-key-alias "${ANDROID_KEY_ALIAS}" \
      --ks-pass env:ANDROID_KEYSTORE_PASSWORD \
      --key-pass env:ANDROID_KEY_PASSWORD \
      --out "${output_apk}" \
      "${source_apk}"
    "${apksigner_tool}" verify "${output_apk}" >/dev/null
  else
    output_apk="${output_dir}/ShareGuard-${safe_tag}${suffix}-unsigned.apk"
    cp -- "${source_apk}" "${output_apk}"
  fi
done

for index in "${!aab_inputs[@]}"; do
  source_aab=${aab_inputs[$index]}
  suffix=
  if (( ${#aab_inputs[@]} > 1 )); then
    suffix="-$((index + 1))"
  fi
  if [[ "${signing_enabled}" == true ]]; then
    output_aab="${output_dir}/ShareGuard-${safe_tag}${suffix}.aab"
    cp -- "${source_aab}" "${output_aab}"
    jarsigner \
      -keystore "${keystore}" \
      -storepass:env ANDROID_KEYSTORE_PASSWORD \
      -keypass:env ANDROID_KEY_PASSWORD \
      "${output_aab}" \
      "${ANDROID_KEY_ALIAS}" >/dev/null
    jarsigner -verify "${output_aab}" >/dev/null
    if ! contains_jar_signature "${output_aab}"; then
      echo "AAB signature verification failed." >&2
      exit 1
    fi
  else
    output_aab="${output_dir}/ShareGuard-${safe_tag}${suffix}-unsigned.aab"
    cp -- "${source_aab}" "${output_aab}"
  fi
  verify_zip_archive "${output_aab}"
done

for sbom in "${sbom_inputs[@]}"; do
  case "${sbom}" in
    *.json) extension=json ;;
    *.xml) extension=xml ;;
    *) echo "Unexpected SBOM format: ${sbom}" >&2; exit 1 ;;
  esac
  cp -- "${sbom}" "${output_dir}/ShareGuard-${safe_tag}-sbom.cdx.${extension}"
done

mapfile -d '' artifacts < <(
  find "${output_dir}" -maxdepth 1 -type f \
    \( -name '*.apk' -o -name '*.aab' -o -name '*.cdx.json' -o -name '*.cdx.xml' \) -print0 \
    | sort -z
)
if (( ${#artifacts[@]} == 0 )); then
  echo "No release artifacts were prepared." >&2
  exit 1
fi
(
  cd -- "${output_dir}"
  for artifact in "${artifacts[@]}"; do
    sha256sum -- "${artifact##*/}"
  done
) > "${output_dir}/SHA256SUMS"

printf 'Prepared %d Android release artifact(s); signed=%s\n' \
  "${#artifacts[@]}" "${signing_enabled}"
