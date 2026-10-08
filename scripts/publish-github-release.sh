#!/usr/bin/env bash
set -euo pipefail

GITHUB_RELEASE_TOKEN="${GH_TOKEN:-${GITHUB_TOKEN:-}}"
: "${GITHUB_RELEASE_TOKEN:?Set secret GITHUB_TOKEN (or GH_TOKEN) in Codemagic environment group github_release}"
export GH_TOKEN="$GITHUB_RELEASE_TOKEN"
repo="${GITHUB_RELEASE_REPO:-starfall-org/multigateway}"
cd "${CM_BUILD_DIR:-${AC_REPOSITORY_DIR:-$(git rev-parse --show-toplevel)}}"
command -v gh >/dev/null || { echo 'GitHub CLI (gh) is required.' >&2; exit 1; }

# Keep the GitHub tag in sync with the version embedded in the APK.
metadata_json="${RELEASE_METADATA_JSON:-app/build/outputs/apk/release/output-metadata.json}"
version="$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["elements"][0]["versionName"])' "$metadata_json")"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9.-]+)?$ ]] || { echo 'Invalid app version.' >&2; exit 1; }
expected_tag="$version"
tag="${GITHUB_RELEASE_TAG:-$expected_tag}"
[[ "$tag" == "$expected_tag" ]] || { echo "Configured GitHub release tag $tag does not match app version $expected_tag." >&2; exit 1; }
if [[ -n "${CM_TAG:-}" && "$CM_TAG" != "$tag" ]]; then
  echo "Build tag $CM_TAG does not match configured release tag $tag." >&2
  exit 1
fi

commit="$(git rev-parse HEAD)"
short_commit="$(git rev-parse --short=12 HEAD)"
shopt -s nullglob
apk_dir="${RELEASE_APK_DIR:-app/build/outputs/apk/release}"
apks=("$apk_dir"/*.apk)
(( ${#apks[@]} > 0 )) || { echo 'Missing release APKs.' >&2; exit 1; }
checksums=()
for apk in "${apks[@]}"; do
  checksum="$apk.sha1"
  hash="$(shasum -a 1 "$apk" | awk '{print $1}')"
  printf '%s  %s\n' "$hash" "$(basename "$apk")" > "$checksum"
  checksums+=("$checksum")
done

if gh api "repos/$repo/git/ref/tags/$tag" >/dev/null 2>&1; then
  gh api --method PATCH "repos/$repo/git/refs/tags/$tag" \
    -f sha="$commit" -F force=true >/dev/null
else
  gh api --method POST "repos/$repo/git/refs" \
    -f ref="refs/tags/$tag" -f sha="$commit" >/dev/null
fi

if gh release view "$tag" --repo "$repo" >/dev/null 2>&1; then
  echo "Release $tag already exists; tag moved to $short_commit and matching artifacts will be replaced."
else
  gh release create "$tag" --repo "$repo" \
    --title "MultiGateway $tag" --generate-notes --draft
fi

# Prefix GitHub's generated changelog with release metadata that makes the
# downloadable files and verification method obvious at a glance.
release_body="$(gh release view "$tag" --repo "$repo" --json body --jq '.body // ""')"
release_marker='<!-- multigateway-release-info -->'
if [[ "$release_body" != *"$release_marker"* ]]; then
  notes_file="$(mktemp)"
  trap 'rm -f "$notes_file"' EXIT

  {
    printf '%s\n' "$release_marker"
    printf '## MultiGateway %s\n\n' "$tag"
    printf 'Android release build for **MultiGateway %s**.\n\n' "$version"
    printf -- '- **Version:** `%s`\n' "$version"
    printf -- '- **Git tag:** `%s`\n' "$tag"
    printf -- '- **Commit:** `%s`\n\n' "$short_commit"

    printf '### Downloads\n\n'
    for apk in "${apks[@]}"; do
      printf -- '- `%s`\n' "$(basename "$apk")"
      printf -- '  - SHA-1 checksum: `%s.sha1`\n' "$(basename "$apk")"
    done

    printf '\n### Verify a downloaded APK\n\n'
    printf 'Run `shasum -a 1 <apk-file>` and compare the result with the matching `.sha1` file attached to this release.\n'

    if [[ -n "$release_body" ]]; then
      printf '\n### Changes\n\n%s\n' "$release_body"
    fi
  } > "$notes_file"

  gh release edit "$tag" --repo "$repo" --title "MultiGateway $tag" --notes-file "$notes_file"
fi

gh release upload "$tag" "${apks[@]}" "${checksums[@]}" --repo "$repo" --clobber
gh release edit "$tag" --repo "$repo" --draft=false
gh release view "$tag" --repo "$repo" --json url --jq .url
