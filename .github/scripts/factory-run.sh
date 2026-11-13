#!/usr/bin/env bash
# Unattended factory runs (claude-factory ADRs 0018 and 0019), started by a label on an issue:
#   factory:spec    the issue is an idea; the java-pipeline `intake` skill drafts a spec and the run ends in a
#                   pull request that adds it. Merging that pull request is how the owner approves the spec.
#   factory:build   the issue names an approved spec (in its text, or in the run's own "spec drafted" comment);
#                   the `new-feature` skill builds it and the run ends in a pull request with the code.
# The workflow .github/workflows/factory-feature.yml runs the phases in four jobs, so nothing the agent touched
# ever runs with a token that can write (claude-factory ADR 0023):
#   agent job    (no token that can write; the Claude credential)
#     resolve   who asked, what to do, which branch, which plugin release
#     tools     install the pinned Claude Code CLI and fetch the pinned plugin
#     agent     run the skill
#     pack      everything committed, a report written; hand over a git bundle of the commits and the report
#   gate job     (a fresh checkout of the base commit, read-only token: this script is the one the owner merged)
#     unpack    the bundle holds commits on top of the base
#     gate      spec: exactly one new, complete spec file. build: protected files untouched, `./mvnw verify` green
#   publish job  (a fresh checkout of the base commit, the write token; it builds nothing)
#     unpack, gate (checks only, no build), publish   push the gated commit and open the pull request
#   report job   tell the issue which phase stopped the run
#   failed       in a job that failed: say why in the job summary and as a job output
# and, from .github/workflows/factory-chain.yml on a push to the default branch (claude-factory ADR 0026):
#   chain        a merged factory:spec pull request left its spec approved: label the issue factory:build and
#                start the build run
# State is kept between phases in $FACTORY_STATE. Tested with a fake `gh` and a stand-in agent by
# claude-factory's ci/test-factory-run.sh.
set -euo pipefail

# Dependabot can't update this: bump it together with the plugin release the project is pinned to.
claude_code_version=2.1.322

spec_path='docs/specs/[A-Za-z0-9][A-Za-z0-9._-]*\.md'
state="${FACTORY_STATE:-${RUNNER_TEMP:-${TMPDIR:-/tmp}}/factory-run}"
mkdir -p "$state"

die() { echo "factory-run: $*" >&2; echo "$*" > "$state/failure"; exit 1; }
need() { local v; for v in "$@"; do [[ -n "${!v:-}" ]] || die "$v is not set"; done; }
saved() { [[ -s "$state/$1" ]] || die "no '$1' from an earlier phase (run the phases in order)"; cat "$state/$1"; }
output() { [[ -z "${GITHUB_OUTPUT:-}" ]] || echo "$1=$2" >> "$GITHUB_OUTPUT"; }

phase="${1:-}"
[[ -n "$phase" ]] || { echo "usage: factory-run.sh resolve|tools|agent|pack|unpack|gate|publish|failed|report|chain" >&2; exit 2; }
[[ "$phase" == failed || "$phase" == report || "$phase" == chain ]] || echo "$phase" > "$state/phase"

case "$phase" in
  resolve)
    need GITHUB_REPOSITORY ISSUE_NUMBER
    [[ "$ISSUE_NUMBER" =~ ^[0-9]+$ ]] || die "issue number is not a number"

    if [[ "${GITHUB_EVENT_NAME:-}" == workflow_dispatch ]]; then
      # Started by the chain workflow or by hand (claude-factory ADR 0026). GitHub lets only people and workflows
      # with write access dispatch a workflow, so there is nobody to check. The event carries no issue: read it.
      issue="$(gh api "repos/$GITHUB_REPOSITORY/issues/$ISSUE_NUMBER" 2>/dev/null)" || die "issue #$ISSUE_NUMBER not found"
      [[ "$(jq -r 'has("pull_request")' <<<"$issue")" == false ]] || die "#$ISSUE_NUMBER is a pull request, not an issue"
      ISSUE_TITLE="$(jq -r '.title // ""' <<<"$issue")"; ISSUE_BODY="$(jq -r '.body // ""' <<<"$issue")"
    else
      # Only someone who may push to this repository can start a run.
      need SENDER
      permission="$(gh api "repos/$GITHUB_REPOSITORY/collaborators/$SENDER/permission" --jq .permission 2>/dev/null || true)"
      case "$permission" in
        admin|maintain|write) ;;
        *) die "$SENDER may not start a factory run (repository permission: ${permission:-none})" ;;
      esac
    fi

    # The project is pinned to a plugin release (claude-factory ADR 0015); the run uses exactly that one.
    ref="$(jq -r '.extraKnownMarketplaces["claude-factory"].source.ref // empty' .claude/settings.json 2>/dev/null || true)"
    [[ "$ref" =~ ^java-pipeline-v[0-9]+\.[0-9]+\.[0-9]+$ ]] \
      || die ".claude/settings.json does not pin the plugin (extraKnownMarketplaces.claude-factory.source.ref = java-pipeline-vX.Y.Z)"

    case "${FACTORY_LABEL:-factory:build}" in
      factory:build)
        mode=build; branch="factory/issue-$ISSUE_NUMBER"
        # The issue only points at a spec. The spec's text comes from the default branch, where the owner approved it.
        spec="$(grep -oE "$spec_path" <<<"${ISSUE_BODY:-}" | head -n 1 || true)"
        if [[ -z "$spec" ]]; then
          # Or an earlier factory:spec run on this issue named it. Only comments by this workflow or by people
          # who can push count: anyone can comment on a public repository.
          trusted="$(gh api "repos/$GITHUB_REPOSITORY/issues/$ISSUE_NUMBER/comments?per_page=100" 2>/dev/null \
            | jq -r '[.[] | select(.user.login == "github-actions[bot]" or (.author_association | IN("OWNER", "MEMBER", "COLLABORATOR"))) | .body] | join("\n")' 2>/dev/null || true)"
          spec="$(grep -oE "$spec_path" <<<"$trusted" | tail -n 1 || true)"
        fi
        [[ -n "$spec" ]] || die "the issue names no spec: put its path (docs/specs/NNNN-name.md) in the issue text, or draft one with the factory:spec label"
        [[ -f "$spec" && ! -L "$spec" ]] || die "$spec is not on the default branch: merge the spec first"
        grep -qE '^- Status: *Approved *$' "$spec" || die "$spec is not approved: its header must read '- Status: Approved'"
        sed -n '/^## Acceptance criteria/,/^## /p' "$spec" | grep -qE '^1\. ' \
          || die "$spec has no numbered acceptance criteria"
        echo "$spec" > "$state/spec"
        ;;
      factory:spec)
        mode=spec; branch="factory/spec-$ISSUE_NUMBER"
        [[ -n "${ISSUE_TITLE:-}${ISSUE_BODY:-}" ]] || die "the issue is empty: describe the idea in its title and text"
        # The idea reaches the agent as a file to read, never as part of a command or of the prompt.
        printf '# %s\n\n%s\n' "${ISSUE_TITLE:-}" "${ISSUE_BODY:-}" > "$state/idea.md"
        ;;
      *) die "label '${FACTORY_LABEL}' does not start a factory run" ;;
    esac

    if gh api "repos/$GITHUB_REPOSITORY/branches/${branch//\//%2F}" --jq .name >/dev/null 2>&1; then
      die "branch $branch already exists: delete it (or close its pull request and delete it) before a new run"
    fi

    echo "$mode" > "$state/mode"
    echo "$branch" > "$state/branch"
    echo "$ref" > "$state/plugin-ref"
    git rev-parse HEAD > "$state/base"
    git rev-parse --abbrev-ref HEAD > "$state/base-branch"
    # What the later jobs need, fixed before the agent runs.
    output mode "$mode"; output branch "$branch"; output base "$(cat "$state/base")"
    output base_branch "$(cat "$state/base-branch")"; output spec "$(cat "$state/spec" 2>/dev/null || true)"
    echo "factory-run: issue #$ISSUE_NUMBER, $mode run, branch $branch, plugin $ref"
    ;;

  tools)
    ref="$(saved plugin-ref)"
    npm install --global --no-fund --no-audit "@anthropic-ai/claude-code@$claude_code_version"
    rm -rf "$state/claude-factory"
    git clone --quiet --depth 1 --branch "$ref" "${FACTORY_PLUGIN_REPO:-https://github.com/nabiabdallah/claude-factory}" \
      "$state/claude-factory"
    [[ -f "$state/claude-factory/plugins/java-pipeline/.claude-plugin/plugin.json" ]] || die "no java-pipeline plugin at $ref"
    ;;

  agent)
    need ISSUE_NUMBER
    mode="$(saved mode)"; branch="$(saved branch)"
    plugin="${FACTORY_PLUGIN_DIR:-$state/claude-factory/plugins/java-pipeline}"
    [[ -d "$plugin" ]] || die "plugin directory $plugin not found (run the tools phase)"
    [[ -n "${CLAUDE_CODE_OAUTH_TOKEN:-}${ANTHROPIC_API_KEY:-}${FACTORY_AGENT_CMD:-}" ]] \
      || die "no Claude credential: set the repository secret CLAUDE_CODE_OAUTH_TOKEN (from 'claude setup-token') or ANTHROPIC_API_KEY"

    git config user.name "${FACTORY_GIT_NAME:-github-actions[bot]}"
    git config user.email "${FACTORY_GIT_EMAIL:-41898282+github-actions[bot]@users.noreply.github.com}"
    git switch --quiet --create "$branch"

    export FACTORY_HEADLESS=1
    export FACTORY_REPORT="$state/report.md"
    rm -f "$FACTORY_REPORT"
    if [[ "$mode" == spec ]]; then
      export FACTORY_IDEA="$state/idea.md"
      prompt="/java-pipeline:intake Headless run for issue #$ISSUE_NUMBER. The idea is in the file $FACTORY_IDEA. Follow the skill's \"Headless runs\" section. Write the report to $FACTORY_REPORT."
    else
      prompt="/java-pipeline:new-feature Headless run for issue #$ISSUE_NUMBER. Implement the approved spec $(saved spec). Follow the skill's \"Headless runs\" section. Write the hand-off report to $FACTORY_REPORT."
    fi
    # No person can answer a permission prompt, so nothing asks. What limits the agent instead: it holds no
    # token that can write to GitHub, and the gate phase refuses changes to protected files.
    set +e
    "${FACTORY_AGENT_CMD:-claude}" --print "$prompt" \
      --plugin-dir "$plugin" \
      --permission-mode "${FACTORY_PERMISSION_MODE:-bypassPermissions}" \
      --max-budget-usd "${FACTORY_MAX_BUDGET_USD:-25}" \
      --output-format json > "$state/agent.json"
    status=$?
    set -e
    [[ $status -eq 0 ]] || die "the agent stopped with exit code $status (see the agent-run artifact)"
    ;;

  pack)
    mode="$(saved mode)"; branch="$(saved branch)"; base="$(saved base)"
    [[ "$(git rev-parse --abbrev-ref HEAD)" == "$branch" ]] || die "the agent left the branch $branch"
    [[ "$(git rev-list --count "$base..HEAD")" -gt 0 ]] || die "the agent committed nothing"
    leftovers="$(git status --porcelain)"
    [[ -z "$leftovers" ]] || die "uncommitted or untracked files left behind: $(tr '\n' ' ' <<<"$leftovers")"
    [[ -s "$state/report.md" ]] || die "no hand-off report was written"
    git bundle create "$state/agent.bundle" "refs/heads/$branch" "^$base" >/dev/null 2>&1 || die "could not bundle the commits"
    echo "factory-run: packed $(git rev-list --count "$base..HEAD") commits"
    ;;

  unpack)
    # Runs from a checkout of the base commit, with this script as the owner merged it. MODE, BRANCH, BASE, BASE_BRANCH
    # and SPEC are the agent job's outputs from before the agent ran; the bundle and the report come from the agent
    # and are checked, not trusted.
    FACTORY_HANDOFF="${FACTORY_HANDOFF:-${RUNNER_TEMP:-}/handoff}" # where the workflow downloads the hand-over
    need FACTORY_MODE FACTORY_BRANCH FACTORY_BASE FACTORY_BASE_BRANCH FACTORY_HANDOFF ISSUE_NUMBER
    [[ "$ISSUE_NUMBER" =~ ^[0-9]+$ ]] || die "issue number is not a number"
    case "$FACTORY_MODE" in
      build) [[ "$FACTORY_BRANCH" == "factory/issue-$ISSUE_NUMBER" ]] || die "unexpected branch from the agent job"
             [[ "${FACTORY_SPEC:-}" =~ ^$spec_path$ ]] || die "unexpected spec from the agent job"
             echo "$FACTORY_SPEC" > "$state/spec" ;;
      spec) [[ "$FACTORY_BRANCH" == "factory/spec-$ISSUE_NUMBER" ]] || die "unexpected branch from the agent job" ;;
      *) die "unexpected mode from the agent job" ;;
    esac
    [[ "$FACTORY_BASE" =~ ^[0-9a-f]{40}$ && "$FACTORY_BASE_BRANCH" =~ ^[A-Za-z0-9._/-]+$ ]] || die "unexpected base from the agent job"
    [[ "$(git rev-parse HEAD)" == "$FACTORY_BASE" ]] || die "this checkout is not the base commit $FACTORY_BASE"
    bundle="$FACTORY_HANDOFF/agent.bundle"; report="$FACTORY_HANDOFF/report.md"
    [[ -f "$bundle" && -s "$report" ]] || die "the agent job handed over no bundle or no report"
    (( $(wc -c < "$report") <= 60000 )) || die "the report is over 60000 bytes"
    git bundle verify -q "$bundle" >/dev/null 2>&1 || die "the bundle is not valid on top of the base"
    git fetch -q "$bundle" "refs/heads/$FACTORY_BRANCH:refs/heads/$FACTORY_BRANCH" 2>/dev/null || die "the bundle has no $FACTORY_BRANCH"
    git merge-base --is-ancestor "$FACTORY_BASE" "$FACTORY_BRANCH" || die "$FACTORY_BRANCH does not start from the base"
    [[ "$(git rev-list --count "$FACTORY_BASE..$FACTORY_BRANCH")" -gt 0 ]] || die "the agent committed nothing"
    git switch --quiet "$FACTORY_BRANCH"
    cp "$report" "$state/report.md"
    echo "$FACTORY_MODE" > "$state/mode"; echo "$FACTORY_BRANCH" > "$state/branch"
    echo "$FACTORY_BASE" > "$state/base"; echo "$FACTORY_BASE_BRANCH" > "$state/base-branch"
    echo "factory-run: unpacked $(git rev-list --count "$FACTORY_BASE..HEAD") commits on $FACTORY_BRANCH"
    ;;

  gate)
    mode="$(saved mode)"; base="$(saved base)"
    problems=()

    [[ "$(git rev-list --count "$base..HEAD")" -gt 0 ]] || problems+=("the agent committed nothing")
    leftovers="$(git status --porcelain)"
    [[ -z "$leftovers" ]] || problems+=("uncommitted or untracked files left behind: $(tr '\n' ' ' <<<"$leftovers")")
    [[ -s "$state/report.md" ]] || problems+=("no hand-off report was written")

    if [[ "$mode" == spec ]]; then
      # A spec run adds one spec file and nothing else. It is complete, and "Approved" only with no open question.
      changes="$(git diff --name-status "$base..HEAD")"
      if [[ "$changes" =~ ^A[[:space:]]+(docs/specs/[0-9]{4}-[a-z0-9]+(-[a-z0-9]+)*\.md)$ ]]; then
        spec="${BASH_REMATCH[1]}"
        for heading in Problem Scope Contract Authorization "Acceptance criteria" "Failure modes" Rollout Non-functional "Open questions"; do
          grep -qxF "## $heading" "$spec" || problems+=("$spec has no '## $heading' section")
        done
        grep -qE '^# Spec: .*[^ >]$' "$spec" || problems+=("$spec has no '# Spec: <name>' title")
        sed -n '/^## Acceptance criteria/,/^## /p' "$spec" | grep -qE '^1\. ' || problems+=("$spec has no numbered acceptance criteria")
        status="$(sed -n 's/^- Status: *//p' "$spec" | head -n 1)"
        open="$(sed -n '/^## Open questions/,$p' "$spec" | tail -n +2 | grep -vE '^\s*$' | grep -viE '^-? *\(?none\.?\)?$' || true)"
        case "$status" in
          Draft) ;;
          Approved) [[ -z "$open" ]] || problems+=("$spec says Approved but still has open questions") ;;
          *) problems+=("$spec must say '- Status: Draft' or '- Status: Approved' (found '${status:-nothing}')") ;;
        esac
        echo "$spec" > "$state/spec"; echo "$status" > "$state/spec-status"
      else
        problems+=("a spec run must add exactly one file docs/specs/NNNN-name.md and change nothing else; found: $(tr -s '\t\n' ' ' <<<"$changes")")
      fi
      if [[ ${#problems[@]} -gt 0 ]]; then
        printf 'factory-run: %s\n' "${problems[@]}" >&2
        die "gate refused the spec: ${problems[0]}$([[ ${#problems[@]} -gt 1 ]] && echo " (and $(( ${#problems[@]} - 1 )) more, see the log)")"
      fi
      output head "$(git rev-parse HEAD)"
      echo "factory-run: gate passed (spec $spec, status $status)"
      exit 0
    fi

    # Files only the owner changes: CI and this runner, agent rules and permissions, the architecture rules,
    # the build wrapper, deploy promotions, scan exceptions and specs.
    while IFS= read -r f; do
      [[ -n "$f" ]] || continue
      case "$f" in
        .github/*|.claude/*|.mvn/*|mvnw|mvnw.cmd|CLAUDE.md|.trivyignore.yaml|.gitignore|deploy/k8s/overlays/*|docs/specs/*|\
        src/test/resources/archunit.properties|src/test/java/*/ArchitectureTest.java)
          problems+=("changed a protected file: $f") ;;
      esac
    done < <(git diff --name-only "$base..HEAD")

    deleted="$(git diff --diff-filter=D --name-only "$base..HEAD" -- src/test)"
    [[ -z "$deleted" ]] || problems+=("deleted tests: $(tr '\n' ' ' <<<"$deleted")")

    # Build rules can't be loosened to get green (claude-factory ADR 0013).
    weakened="$(git diff "$base..HEAD" -- pom.xml | grep -E '^\+[^+]' \
      | grep -E 'factory\.coverage|enforcer\.skip|jacoco\.skip|skipTests|maven\.test\.skip|<excludes?>' || true)"
    [[ -z "$weakened" ]] || problems+=("pom.xml loosens a build rule: $(tr -s ' \n' ' ' <<<"$weakened")")

    if [[ ${#problems[@]} -gt 0 ]]; then
      printf 'factory-run: %s\n' "${problems[@]}" >&2
      die "gate refused the change: ${problems[0]}$([[ ${#problems[@]} -gt 1 ]] && echo " (and $(( ${#problems[@]} - 1 )) more, see the log)")"
    fi

    # The build decides, not the agent's word: the Stop hook gives up after 3 attempts, this doesn't. It runs the
    # agent's code, so it runs in the gate job, which holds no token that can write. The publish job repeats the
    # checks above with FACTORY_GATE_BUILD=0 and builds nothing.
    if [[ "${FACTORY_GATE_BUILD:-1}" != 0 ]]; then
      if ! ${FACTORY_VERIFY_CMD:-./mvnw -B -q verify} > "$state/verify.log" 2>&1; then
        tail -n 60 "$state/verify.log" >&2
        die "./mvnw verify is red on the agent's change"
      fi
      [[ -z "$(git status --porcelain)" ]] || die "the build changed tracked files (formatting not applied before commit?)"
    fi
    output head "$(git rev-parse HEAD)"
    echo "factory-run: gate passed ($(git rev-list --count "$base..HEAD") commits$([[ "${FACTORY_GATE_BUILD:-1}" != 0 ]] && echo ", verify green"))"
    ;;

  publish)
    need GITHUB_REPOSITORY ISSUE_NUMBER GH_TOKEN
    spec="$(saved spec)"; branch="$(saved branch)"; base_branch="$(saved base-branch)"
    mode="$(saved mode)"
    # Only the commit the gate job built and tested is published.
    need FACTORY_GATED_SHA
    [[ "$(git rev-parse HEAD)" == "$FACTORY_GATED_SHA" ]] || die "the commit to publish is not the one the gate job verified"
    title="$(sed -n 's/^# Spec: *//p' "$spec" | head -n 1)"
    [[ -n "$title" ]] || title="$(basename "$spec" .md)"

    # The reason may reach a public issue comment, where secrets aren't masked: strip credentials from URLs.
    git push --quiet "${FACTORY_PUSH_URL:-https://x-access-token:$GH_TOKEN@github.com/$GITHUB_REPOSITORY}" \
      "HEAD:refs/heads/$branch" 2>"$state/push.err" || die "could not push $branch: $(sed -E 's#https://[^@/[:space:]]*@#https://#g' "$state/push.err" | tr -s '\n' ' ')"

    run="${FACTORY_RUN_URL:+ ($FACTORY_RUN_URL)}"
    if [[ "$mode" == spec ]]; then
      status="$(saved spec-status)"
      pr_title="spec: $title (#$ISSUE_NUMBER)"
      if [[ "$status" == Approved ]]; then
        next="It has no open questions and says \`- Status: Approved\`: merging this pull request approves it and starts the build."
        todo="Review it and merge to approve it"
      else
        next="It has open questions, so it says \`- Status: Draft\`. Answer them in the file, set \`- Status: Approved\`, then merge: that approves it and starts the build."
        todo="Answer its open questions in the file, set \`- Status: Approved\` and merge"
      fi
      footer="$(printf 'Adds `%s`, drafted unattended from issue #%s by the java-pipeline factory run%s. %s\n\nRefs #%s' "$spec" "$ISSUE_NUMBER" "$run" "$next" "$ISSUE_NUMBER")"
      done_note="Spec drafted: PR_URL adds \`$spec\`. $todo: the build then starts by itself (the label \`factory:build\` is put on this issue for you)."
    else
      pr_title="feat: $title (#$ISSUE_NUMBER)"
      footer="$(printf 'Spec: `%s`. Built unattended by the java-pipeline factory run%s; `./mvnw verify` was green on this commit.\n\nCloses #%s' "$spec" "$run" "$ISSUE_NUMBER")"
      done_note="Factory run finished: PR_URL is ready for review."
    fi
    { cat "$state/report.md"; printf '\n\n---\n%s\n' "$footer"; } > "$state/pr-body.md"
    pr_url="$(gh api "repos/$GITHUB_REPOSITORY/pulls" -f title="$pr_title" -f head="$branch" \
      -f base="$base_branch" -F body=@"$state/pr-body.md" --jq .html_url 2>"$state/pr.err")" || {
      hint=""
      grep -q 'not permitted to create or approve pull requests' "$state/pr.err" && hint=" Turn on Settings → Actions → General → \"Allow GitHub Actions to create and approve pull requests\" (claude-factory's ci/protect-repo.sh does it), delete the branch $branch, then remove and re-add the label."
      die "GitHub refused the pull request: $(tr -s '\n' ' ' < "$state/pr.err")$hint"
    }
    [[ -n "$pr_url" ]] || die "the pull request was not created"
    echo "$pr_url" > "$state/pr-url"

    # GitHub starts no workflow on a pull request opened with the workflow's own token, but that token may
    # dispatch one: start CI on the branch, so its checks land on the pull request's commit (ADR 0026).
    note=""
    if [[ "${FACTORY_PR_TOKEN_SET:-false}" != true ]]; then
      if gh api --method POST "repos/$GITHUB_REPOSITORY/actions/workflows/ci.yml/dispatches" -f ref="$branch" >/dev/null 2>"$state/ci.err"; then
        note=" CI was started on the branch; its checks show on the pull request."
      else
        echo "factory-run: could not start CI: $(tr -s '\n' ' ' < "$state/ci.err")" >&2
        note=" CI could not be started on the branch (see the run's log): close and reopen the pull request to start it."
      fi
    fi
    gh api "repos/$GITHUB_REPOSITORY/issues/$ISSUE_NUMBER/comments" \
      -f body="${done_note//PR_URL/$pr_url}$note" --jq .html_url >/dev/null
    echo "factory-run: opened $pr_url"
    ;;

  failed)
    # In a job that failed: no GitHub call, so no token is needed. The report job tells the issue.
    why="$(cat "$state/failure" 2>/dev/null || echo "the step failed before it could say why")"
    at="$(cat "$state/phase" 2>/dev/null || echo "start")"
    why="$(tr -s '\r\n\t' ' ' <<<"$why" | cut -c1-500)"
    output why "$why"; output at "$at"
    echo "factory-run: stopped in the $at phase: $why" >&2
    [[ -z "${GITHUB_STEP_SUMMARY:-}" ]] || echo "Stopped in the \`$at\` phase: $why" >> "$GITHUB_STEP_SUMMARY"
    ;;

  report)
    need GITHUB_REPOSITORY ISSUE_NUMBER
    why="${FACTORY_WHY:-$(cat "$state/failure" 2>/dev/null || true)}"
    at="${FACTORY_AT:-$(cat "$state/phase" 2>/dev/null || true)}"
    [[ -n "$why" ]] || { why="the run was cancelled or timed out"; at="${at:-unknown}"; }
    why="$(tr -s '\r\n\t' ' ' <<<"$why" | cut -c1-500)"
    gh api "repos/$GITHUB_REPOSITORY/issues/$ISSUE_NUMBER/comments" \
      -f body="Factory run stopped in the \`${at:-start}\` phase: $why${FACTORY_RUN_URL:+ ([run]($FACTORY_RUN_URL))}. No pull request was opened. Fix the cause, then remove and re-add the label." \
      --jq .html_url >/dev/null || echo "factory-run: could not comment on the issue" >&2
    ;;

  chain)
    # On a push to the default branch, from that checkout (trusted code only). A factory:spec run's pull request
    # was merged and its spec now says Approved: put factory:build on the issue and start the build. A label set
    # with the workflow's own token starts nothing, so the build workflow is dispatched (ADR 0026).
    need GITHUB_REPOSITORY GITHUB_SHA GH_TOKEN
    skip() { echo "factory-run: chain: $*"; [[ -z "${GITHUB_STEP_SUMMARY:-}" ]] || echo "No build started: $*" >> "$GITHUB_STEP_SUMMARY"; exit 0; }
    before="${FACTORY_BEFORE:-}"
    if [[ ! "$before" =~ ^[0-9a-f]{40}$ || "$before" =~ ^0+$ ]] || ! git rev-parse --verify -q "$before^{commit}" >/dev/null; then
      before="$(git rev-parse --verify -q "HEAD~1^{commit}" || true)"
    fi
    [[ -n "$before" ]] || skip "no earlier commit to compare with"

    # Specs this push approved: Approved now, and absent or not Approved before.
    approved=""
    while IFS= read -r f; do
      [[ "$f" =~ ^docs/specs/[0-9]{4}-[a-z0-9]+(-[a-z0-9]+)*\.md$ && -f "$f" && ! -L "$f" ]] || continue
      grep -qE '^- Status: *Approved *$' "$f" || continue
      ! git show "$before:$f" 2>/dev/null | grep -qE '^- Status: *Approved *$' || continue
      approved="$f"; break
    done < <(git diff --name-only --diff-filter=AM "$before" HEAD -- docs/specs)
    [[ -n "$approved" ]] || skip "this push approved no spec"

    # The issue is the one the factory:spec run drafted the spec for: its pull request's branch is factory/spec-<n>.
    prs="$(gh api "repos/$GITHUB_REPOSITORY/commits/$GITHUB_SHA/pulls")" || die "could not read the pull request of $GITHUB_SHA"
    issue="$(jq -r --arg repo "$GITHUB_REPOSITORY" '[.[] | select(.merged_at != null and .head.repo.full_name == $repo)
      | .head.ref | select(test("^factory/spec-[0-9]+$")) | sub("^factory/spec-"; "")] | first // empty' <<<"$prs")"
    [[ -n "$issue" ]] || skip "$approved was approved outside a factory:spec pull request: put factory:build on an issue that names it"
    [[ "${FACTORY_PAUSED:-}" != true ]] || skip "the factory is paused (variable FACTORY_PAUSED=true): put factory:build on issue #$issue to build $approved"

    info="$(gh api "repos/$GITHUB_REPOSITORY/issues/$issue")" || die "could not read issue #$issue"
    [[ "$(jq -r .state <<<"$info")" == open ]] || skip "issue #$issue is closed"
    # Someone already put the label there: that started a run (or the owner will, by re-adding it).
    [[ "$(jq -r '[.labels[].name] | index("factory:build") != null' <<<"$info")" == false ]] || skip "issue #$issue already has factory:build"
    if gh api "repos/$GITHUB_REPOSITORY/branches/factory%2Fissue-$issue" --jq .name >/dev/null 2>&1; then
      skip "branch factory/issue-$issue already exists"
    fi

    gh api --method POST "repos/$GITHUB_REPOSITORY/issues/$issue/labels" -f 'labels[]=factory:build' >/dev/null \
      || die "could not label issue #$issue"
    gh api --method POST "repos/$GITHUB_REPOSITORY/actions/workflows/factory-feature.yml/dispatches" \
      -f ref="${GITHUB_REF_NAME:-main}" -f "inputs[issue]=$issue" -f 'inputs[label]=factory:build' >/dev/null \
      || die "labelled issue #$issue but could not start the build: remove and re-add the label factory:build"
    gh api "repos/$GITHUB_REPOSITORY/issues/$issue/comments" \
      -f body="Spec approved: \`$approved\` is on ${GITHUB_REF_NAME:-main}. The build has started." --jq .html_url >/dev/null \
      || echo "factory-run: could not comment on the issue" >&2
    echo "factory-run: chain: started the build of $approved for issue #$issue"
    [[ -z "${GITHUB_STEP_SUMMARY:-}" ]] || echo "Started the build of \`$approved\` for issue #$issue" >> "$GITHUB_STEP_SUMMARY"
    ;;

  *)
    echo "factory-run: unknown phase '$phase'" >&2; exit 2 ;;
esac
