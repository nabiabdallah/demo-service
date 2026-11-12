#!/usr/bin/env bash
# Deploy gate (claude-factory ADR 0012). Run from the project root; CI runs it on every push and PR.
#   .github/scripts/check-deploy.sh [<base-commit>]
# For every overlay in deploy/k8s/overlays:
#   1. it renders (kustomize) and matches the Kubernetes schemas (kubeconform -strict);
#   2. every image is pinned to a commit on main (40-hex tag) or is "unreleased": never main, latest or no tag;
#   3. if its tag changed since <base-commit> (a promotion), the migrations between the old and new tag decide the
#      rollout: a migration headed "-- rollout: recreate" needs components/recreate in the overlay, and the
#      component must be gone when no such migration is crossed. Released migrations may not change, and a
#      rollback may not cross a migration.
# Env: MAIN_REF (default origin/main), KUSTOMIZE (default "kubectl kustomize"),
#      CHECK_DEPLOY_NO_SCHEMA=1 skips kubeconform (local runs without it; CI always validates).
set -euo pipefail

base="${1:-}"
main_ref="${MAIN_REF:-origin/main}"
read -r -a kustomize <<<"${KUSTOMIZE:-kubectl kustomize}"
migrations=src/main/resources/db/migration
fail=0
problem() { echo "FAIL: $*"; fail=1; }

[[ -d deploy/k8s/overlays ]] || { echo "FAIL: no deploy/k8s/overlays (run from the project root)"; exit 1; }
git rev-parse --verify -q "$main_ref^{commit}" >/dev/null || { echo "FAIL: $main_ref not found (CI checks out full history)"; exit 1; }
if [[ -n "$base" && ( "$base" =~ ^0+$ || -z "$(git rev-parse --verify -q "$base^{commit}")" ) ]]; then
  echo "note: base $base is not a known commit; promotion rules skipped"
  base=""
fi
if [[ "${CHECK_DEPLOY_NO_SCHEMA:-}" != 1 ]] && ! command -v kubeconform >/dev/null; then
  echo "FAIL: kubeconform not on PATH (.github/scripts/ci-tools.sh kubeconform), or set CHECK_DEPLOY_NO_SCHEMA=1"
  exit 1
fi

is_commit_tag() { [[ "$1" =~ ^[0-9a-f]{40}$ ]]; }
recreate_migrations() { # <from> <to>: migrations added in from..to whose first line declares recreate
  local f
  git diff --name-only --diff-filter=A "$1" "$2" -- "$migrations" | while IFS= read -r f; do
    git show "$2:$f" | sed -n 1p | grep -q '^-- rollout: recreate' && echo "$f"
  done
  return 0
}

for overlay in deploy/k8s/overlays/*/; do
  overlay="${overlay%/}"
  env="$(basename "$overlay")"
  if ! rendered="$("${kustomize[@]}" "$overlay" 2>&1)"; then
    problem "$env: does not render: $rendered"
    continue
  fi
  if [[ "${CHECK_DEPLOY_NO_SCHEMA:-}" != 1 ]]; then
    kubeconform -strict -summary -kubernetes-version 1.31.0 - <<<"$rendered" || problem "$env: schema validation failed"
  fi

  # 2. Images: pinned to a commit on main.
  tag=""
  while IFS= read -r image; do
    image="${image//\"/}"
    t=""
    [[ "${image##*/}" == *:* ]] && t="${image##*:}"
    if [[ "$t" == unreleased ]]; then
      :
    elif ! is_commit_tag "$t"; then
      problem "$env: $image is not pinned to a commit (tags like main/latest move; promote a commit sha)"
    elif ! git cat-file -e "$t^{commit}" 2>/dev/null; then
      problem "$env: $image: commit $t is not in this repository"
    elif ! git merge-base --is-ancestor "$t" "$main_ref"; then
      problem "$env: $image: commit $t is not on $main_ref (only verified, pushed builds of main can be deployed)"
    fi
    [[ -z "$tag" ]] && tag="$t"
  done < <(sed -n 's/^ *-\{0,1\} *image: *//p' <<<"$rendered")
  [[ -n "$tag" ]] || { problem "$env: renders no container image"; continue; }

  # 3. Promotion rules.
  [[ -n "$base" ]] || continue
  old="$({ git show "$base:$overlay/kustomization.yaml" 2>/dev/null || true; } | sed -n 's/^ *newTag: *//p' | sed -n 1p | tr -d '"')"
  [[ "$old" != "$tag" ]] || continue
  if [[ -z "$old" || "$old" == unreleased ]]; then
    echo "$env: first release ($tag): nothing running, rollout rules don't apply"
    continue
  fi
  if [[ "$tag" == unreleased ]]; then
    problem "$env: newTag went back to unreleased; promote a commit instead"
    continue
  fi
  if ! is_commit_tag "$old" || ! git cat-file -e "$old^{commit}" 2>/dev/null; then
    problem "$env: the previous tag '$old' is not a commit; can't check the migrations in between"
    continue
  fi
  recreate=0
  grep -Eq '^ +type: Recreate$' <<<"$rendered" && recreate=1
  if ! git merge-base --is-ancestor "$old" "$tag"; then
    crossed="$(git diff --name-only "$tag" "$old" -- "$migrations")"
    [[ -z "$crossed" ]] || problem "$env: rollback from $old to $tag crosses migrations (roll forward instead): ${crossed//$'\n'/, }"
    [[ "$recreate" -eq 0 ]] || problem "$env: components/recreate on a rollback that crosses no migration; remove it"
    continue
  fi
  edited="$(git diff --name-only --diff-filter=MDR "$old" "$tag" -- "$migrations")"
  [[ -z "$edited" ]] || problem "$env: released migrations changed between $old and $tag (add a new one instead): ${edited//$'\n'/, }"
  needs="$(recreate_migrations "$old" "$tag")"
  if [[ -n "$needs" && "$recreate" -eq 0 ]]; then
    problem "$env: $old..$tag crosses migrations the old version can't run against (${needs//$'\n'/, }); add components/recreate to the overlay (downtime) for this promotion"
  elif [[ -z "$needs" && "$recreate" -eq 1 ]]; then
    problem "$env: components/recreate is in the overlay but $old..$tag crosses no '-- rollout: recreate' migration; remove it (rolling update is the default)"
  else
    echo "$env: promotion $old -> $tag OK ($([[ "$recreate" -eq 1 ]] && echo Recreate || echo RollingUpdate))"
  fi
done

[[ "$fail" -eq 0 ]] && echo "check-deploy: OK" || exit 1
