#!/usr/bin/env bash
# Installs pinned CI tools, each verified against a sha256 recorded here (claude-factory ADR 0009).
# Dependabot can't update these: bump version + sha256 together (the sha256 is the "digest" of the
# release asset in the GitHub API, or the tool's published checksums file).
#   .github/scripts/ci-tools.sh cosign trivy kubeconform
set -euo pipefail

cosign_version=v3.1.4
cosign_sha256=424e2c97d0b45fd7a6b608a87f090f35633f4c23ece5f092d48bba11aaf4bbfa
trivy_version=0.76.1
trivy_sha256=c7cbe3137fb8a48a17574bede3dd6beb16742d1e9772929940fc90082c39d9c0
kubeconform_version=v0.8.0
kubeconform_sha256=9bc2bffbf71f261128533edaf912153948b7ff238f9a531ae6d34466ec287883

bin="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/ci-bin"
mkdir -p "$bin"

fetch() { # url sha256 output
  # Abort a stalled download (< 10 KB/s for 30s) and retry, instead of hanging the job.
  curl -sSfL --connect-timeout 20 --speed-limit 10240 --speed-time 30 --retry 4 --retry-all-errors -o "$3" "$1"
  echo "$2  $3" | sha256sum -c --quiet -
}

for tool in "$@"; do
  case "$tool" in
    cosign)
      fetch "https://github.com/sigstore/cosign/releases/download/$cosign_version/cosign-linux-amd64" \
        "$cosign_sha256" "$bin/cosign"
      chmod +x "$bin/cosign"
      ;;
    trivy)
      archive="$bin/trivy.tar.gz"
      fetch "https://github.com/aquasecurity/trivy/releases/download/v$trivy_version/trivy_${trivy_version}_Linux-64bit.tar.gz" \
        "$trivy_sha256" "$archive"
      tar -xzf "$archive" -C "$bin" trivy
      rm "$archive"
      ;;
    kubeconform)
      archive="$bin/kubeconform.tar.gz"
      fetch "https://github.com/yannh/kubeconform/releases/download/$kubeconform_version/kubeconform-linux-amd64.tar.gz" \
        "$kubeconform_sha256" "$archive"
      tar -xzf "$archive" -C "$bin" kubeconform
      rm "$archive"
      ;;
    *)
      echo "unknown tool: $tool" >&2
      exit 2
      ;;
  esac
  echo "installed $tool"
done

[[ -n "${GITHUB_PATH:-}" ]] && echo "$bin" >> "$GITHUB_PATH"
exit 0
