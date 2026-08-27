#!/usr/bin/env bash
set -euo pipefail

current_tag="${1:-${GITHUB_REF_NAME:-}}"
current_sha="${2:-${GITHUB_SHA:-HEAD}}"
output_path="${3:-build/release-context.md}"

if [[ -z "$current_tag" ]]; then
  echo "::error::Current release tag is required"
  exit 1
fi
if [[ -z "${GITHUB_REPOSITORY:-}" ]]; then
  echo "::error::GITHUB_REPOSITORY is required"
  exit 1
fi

mkdir -p "$(dirname "$output_path")"
git fetch --force --tags origin

previous_tag="$(git describe --tags --abbrev=0 "${current_sha}^" 2>/dev/null || true)"
if [[ -n "$previous_tag" ]]; then
  commit_range="${previous_tag}..${current_sha}"
else
  commit_range="$current_sha"
fi

mapfile -t commits < <(git rev-list --reverse "$commit_range")

pr_numbers_file="$(mktemp)"
sorted_pr_numbers_file="$(mktemp)"
trap 'rm -f "$pr_numbers_file" "$sorted_pr_numbers_file"' EXIT

{
  echo "# Release context"
  echo
  echo "- Current tag: $current_tag"
  echo "- Previous tag: ${previous_tag:-none}"
  echo "- Commit range: $commit_range"
  echo "- Commit count: ${#commits[@]}"
  echo
  echo "## Commits"
  echo
} > "$output_path"

for sha in "${commits[@]}"; do
  subject="$(git show -s --format=%s "$sha")"
  body="$(git show -s --format=%b "$sha")"
  body="${body:0:2000}"

  {
    echo "### ${sha:0:12} — $subject"
    if [[ -n "$body" ]]; then
      echo
      printf '%s\n' "$body"
    fi
    echo
  } >> "$output_path"

  gh api "repos/${GITHUB_REPOSITORY}/commits/${sha}/pulls" \
    --jq '.[].number' >> "$pr_numbers_file" 2>/dev/null || true
done

sort -nu "$pr_numbers_file" > "$sorted_pr_numbers_file"
pr_count="$(wc -l < "$sorted_pr_numbers_file" | tr -d ' ')"

{
  echo "## Associated pull requests"
  echo
  echo "Associated PR count: $pr_count"
  echo
} >> "$output_path"

processed_prs=0
while IFS= read -r pr_number; do
  [[ -n "$pr_number" ]] || continue
  if (( processed_prs >= 120 )); then
    echo "_Additional associated PRs omitted after the first 120 entries._" >> "$output_path"
    break
  fi

  pr_json="$(gh api "repos/${GITHUB_REPOSITORY}/pulls/${pr_number}" 2>/dev/null || true)"
  [[ -n "$pr_json" ]] || continue

  title="$(jq -r '.title // ""' <<< "$pr_json")"
  body="$(jq -r '.body // ""' <<< "$pr_json")"
  body="${body:0:6000}"
  labels="$(jq -r '[.labels[].name] | join(", ")' <<< "$pr_json")"
  files="$(gh api --paginate "repos/${GITHUB_REPOSITORY}/pulls/${pr_number}/files" \
    --jq '.[].filename' 2>/dev/null | head -n 40 || true)"

  {
    echo "### PR #${pr_number} — $title"
    if [[ -n "$labels" ]]; then
      echo
      echo "Labels: $labels"
    fi
    if [[ -n "$body" ]]; then
      echo
      echo "PR description:"
      echo
      printf '%s\n' "$body"
    fi
    if [[ -n "$files" ]]; then
      echo
      echo "Changed files (up to 40):"
      while IFS= read -r file; do
        [[ -n "$file" ]] && echo "- $file"
      done <<< "$files"
    fi
    echo
  } >> "$output_path"

  processed_prs=$((processed_prs + 1))
done < "$sorted_pr_numbers_file"

echo "Release context written to $output_path"
