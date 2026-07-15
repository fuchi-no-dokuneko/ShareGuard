#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  printf 'Usage: %s [--allow-unsigned] APK\n' "${0##*/}" >&2
}

allow_unsigned=false
if [[ "${1-}" == "--allow-unsigned" ]]; then
  allow_unsigned=true
  shift
fi
if (( $# != 1 )); then
  usage
  exit 2
fi

apk=$1
if [[ ! -f "${apk}" ]]; then
  printf 'APK not found: %s\n' "${apk}" >&2
  exit 1
fi

resolve_sdk_tool() {
  local name=$1
  local candidate
  if [[ -n "${ANDROID_SDK_ROOT-}" ]]; then
    if [[ -x "${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/${name}" ]]; then
      printf '%s\n' "${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/${name}"
      return
    fi
    if [[ -n "${ANDROID_BUILD_TOOLS_VERSION-}" \
          && -x "${ANDROID_SDK_ROOT}/build-tools/${ANDROID_BUILD_TOOLS_VERSION}/${name}" ]]; then
      printf '%s\n' \
        "${ANDROID_SDK_ROOT}/build-tools/${ANDROID_BUILD_TOOLS_VERSION}/${name}"
      return
    fi
    candidate=$(
      find "${ANDROID_SDK_ROOT}/build-tools" -mindepth 2 -maxdepth 2 \
        -type f -name "${name}" -perm -u+x -print 2>/dev/null \
        | sort -V | tail -n 1
    )
    if [[ -n "${candidate}" ]]; then
      printf '%s\n' "${candidate}"
      return
    fi
  fi
  if command -v "${name}" >/dev/null 2>&1; then
    command -v "${name}"
    return
  fi
  printf 'Required Android SDK tool is unavailable: %s\n' "${name}" >&2
  return 1
}

apkanalyzer=$(resolve_sdk_tool apkanalyzer)
apksigner=$(resolve_sdk_tool apksigner)
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)

unzip -tqq "${apk}"

tmp_manifest=$(mktemp)
trap 'rm -f -- "${tmp_manifest}"' EXIT
umask 077
"${apkanalyzer}" manifest print "${apk}" > "${tmp_manifest}"
bash "${script_dir}/verify-merged-manifest.sh" "${tmp_manifest}"

if "${apksigner}" verify "${apk}" >/dev/null 2>&1; then
  signature_status=signed
elif [[ "${allow_unsigned}" == true ]]; then
  signature_status=unsigned-allowed
else
  printf 'APK signature verification failed: %s\n' "${apk##*/}" >&2
  exit 1
fi

sha256=$(sha256sum "${apk}" | awk '{print $1}')
size_bytes=$(wc -c < "${apk}" | tr -d '[:space:]')
application_id=$("${apkanalyzer}" manifest application-id "${apk}")
version_code=$("${apkanalyzer}" manifest version-code "${apk}")
version_name=$("${apkanalyzer}" manifest version-name "${apk}")
min_sdk=$("${apkanalyzer}" manifest min-sdk "${apk}")
target_sdk=$("${apkanalyzer}" manifest target-sdk "${apk}")

printf 'APK metadata: file=%s sha256=%s bytes=%s signature=%s applicationId=%s versionCode=%s versionName=%s minSdk=%s targetSdk=%s\n' \
  "${apk##*/}" "${sha256}" "${size_bytes}" "${signature_status}" \
  "${application_id}" "${version_code}" "${version_name}" "${min_sdk}" "${target_sdk}"
