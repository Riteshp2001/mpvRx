#!/usr/bin/env bash
# Restores the live GitHub Pages site into DEST. Every Pages deploy replaces the whole site, so the
# preview and experimental workflows each redeploy the other channel's feed and APKs from here.
#
# The site comes from the artifact of the latest successful Pages deployment, which is exactly what
# is live. The Pages CDN caches feeds for up to ten minutes, so copying them over HTTP right after the
# other channel deployed can resurrect its previous build. HTTP is only the fallback for when that
# artifact has expired, by which time the CDN has long caught up.
#
# Environment: GH_TOKEN (actions:read, deployments:read), GH_REPO (owner/name), SITE_BASE_URL.
set -euo pipefail

dest="${1:?usage: fetch-live-pages-site.sh <dest-dir>}"
repo="${GH_REPO:?GH_REPO is required}"
base="${SITE_BASE_URL:-https://riteshp2001.github.io/mpvRx}"
base="${base%/}"
feeds=(latest.json experimental.json)

mkdir -p "$dest"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

live_run_id() {
  local id status log_url
  for id in $(gh api "repos/$repo/deployments?environment=github-pages&per_page=20" --jq '.[].id'); do
    status="$(gh api "repos/$repo/deployments/$id/statuses?per_page=1" --jq '.[0] | "\(.state) \(.log_url // "")"')"
    [[ "$status" == success\ * ]] || continue
    log_url="${status#success }"
    if [[ "$log_url" =~ /actions/runs/([0-9]+) ]]; then
      echo "${BASH_REMATCH[1]}"
      return 0
    fi
    return 1
  done
  return 1
}

restore_from_artifact() {
  local run_id
  run_id="$(live_run_id)" || return 1
  gh run download "$run_id" -R "$repo" -n github-pages -D "$work/artifact" >/dev/null 2>&1 || return 1
  tar -xf "$work/artifact/artifact.tar" -C "$dest" || return 1
  echo "Restored the live Pages site from workflow run $run_id."
}

restore_from_cdn() {
  local feed code url rel
  for feed in "${feeds[@]}"; do
    code="$(curl -sSL --retry 3 --retry-delay 5 -o "$work/$feed" -w '%{http_code}' "$base/$feed")"
    if [[ "$code" == 404 ]]; then
      continue
    fi
    [[ "$code" == 200 ]] || { echo "::error::Could not read $base/$feed (HTTP $code)."; return 1; }
    jq -e '.assets | type == "array"' "$work/$feed" >/dev/null
    cp "$work/$feed" "$dest/$feed"
    while read -r url; do
      # Archived APKs are already durable GitHub release assets and need no Pages copy.
      [[ "$url" == "https://github.com/$repo/releases/download/"* ]] && continue
      rel="${url#"$base/"}"
      [[ "$rel" != "$url" ]] || { echo "::error::$feed links outside the site: $url"; return 1; }
      mkdir -p "$dest/$(dirname "$rel")"
      curl -fsSL --retry 3 --retry-delay 5 -o "$dest/$rel" "$url"
    done < <(jq -r '.assets[].browser_download_url' "$work/$feed")
  done
  echo "Restored the live Pages feeds over HTTP."
}

# Never redeploy a feed whose APKs are missing or differ from what the feed advertises.
verify_feed() {
  local feed="$1" url size sha rel file
  while IFS=$'\t' read -r url size sha; do
    [[ "$url" == "https://github.com/$repo/releases/download/"* ]] && continue
    rel="${url#"$base/"}"
    file="$dest/$rel"
    [[ -f "$file" ]] || { echo "::error::$feed lists $rel, which is missing."; return 1; }
    [[ "$(stat -c %s "$file")" == "$size" ]] || { echo "::error::$rel does not match the size in $feed."; return 1; }
    if [[ -n "$sha" ]]; then
      [[ "$(sha256sum "$file" | cut -d' ' -f1)" == "$sha" ]] || {
        echo "::error::$rel does not match the checksum in $feed."
        return 1
      }
    fi
  done < <(jq -r '.assets[] | [.browser_download_url, (.size | tostring), (.sha256 // "")] | @tsv' "$dest/$feed")
}

if ! restore_from_artifact; then
  echo "The live deployment artifact is unavailable; falling back to the published site."
  restore_from_cdn
fi

for feed in "${feeds[@]}"; do
  if [[ -f "$dest/$feed" ]]; then
    verify_feed "$feed"
    echo "$feed: $(jq -r '.name' "$dest/$feed") ($(jq -r '.commit_sha // "unknown"' "$dest/$feed" | cut -c1-7))"
  fi
done
