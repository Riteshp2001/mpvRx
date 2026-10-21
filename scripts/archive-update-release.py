#!/usr/bin/env python3
"""Archive a signed update before Pages can replace it; publish no incomplete releases."""

import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote


def gh_json(*args, payload=None, allow_missing=False):
    command = ["gh", "api", *args]
    if payload is not None:
        command.extend(["--input", "-"])
    result = subprocess.run(
        command, input=json.dumps(payload) if payload is not None else None,
        capture_output=True, text=True, check=False,
    )
    if result.returncode:
        if allow_missing and "HTTP 404" in result.stderr:
            return None
        raise RuntimeError(result.stderr.strip() or "GitHub archive request failed")
    return json.loads(result.stdout)


def archive(manifest_path):
    if not manifest_path.is_file():
        return
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    channel = manifest.get("channel")
    if channel not in ("preview", "experimental"):
        raise ValueError("Only signed Preview and Experimental feeds can be archived")
    repo = os.environ["GH_REPO"]
    site_base = os.environ["SITE_BASE_URL"].rstrip("/")
    sha = manifest["commit_sha"]
    build = int(manifest["commit_count"])
    if not re.fullmatch(r"[0-9a-fA-F]{40}", sha) or build <= 0:
        raise ValueError("The archive needs an exact source commit and build number")
    assets = manifest["assets"]
    if not assets:
        raise ValueError("Cannot archive a build without signed APKs")
    # New feeds already point at their permanent release; restoring them needs no APK copies.
    archive_prefix = f"https://github.com/{repo}/releases/download/"
    if all(asset["browser_download_url"].startswith(archive_prefix) for asset in assets):
        return

    paths = []
    for asset in assets:
        name = asset["name"]
        digest = asset.get("sha256", "")
        if Path(name).name != name or not name.endswith(".apk") or not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise ValueError(f"Invalid archived APK metadata: {name}")
        url = asset["browser_download_url"]
        if not url.startswith(site_base + "/"):
            raise ValueError("Unarchived APK must belong to this Pages site")
        path = (manifest_path.parent / unquote(url[len(site_base) + 1:])).resolve()
        if not path.is_relative_to(manifest_path.parent.resolve()) or not path.is_file():
            raise ValueError(f"Missing staged APK: {name}")
        hasher = hashlib.sha256()
        with path.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                hasher.update(chunk)
        if path.stat().st_size != asset["size"] or hasher.hexdigest() != digest:
            raise ValueError(f"Staged APK does not match the published checksum: {name}")
        paths.append(path)

    # Content identity also separates signed rebuilds of the same source commit.
    fingerprint = hashlib.sha256(
        json.dumps(sorted((a["name"], a["sha256"]) for a in assets)).encode()
    ).hexdigest()[:12]
    tag = f"{channel}-r{build}-{sha[:7]}-{fingerprint}"
    api = f"repos/{repo}/releases"
    release = gh_json(f"{api}/tags/{tag}", allow_missing=True)
    commit = gh_json(f"repos/{repo}/commits/{sha}")
    message = commit["commit"]["message"]
    title = message.splitlines()[0] if message else manifest.get("commit_title", "")
    payload = {
        "tag_name": tag, "target_commitish": sha,
        "name": f"mpvRx {channel.title()} r{build} · {title}"[:240],
        "body": message, "draft": True, "prerelease": True, "make_latest": "false",
    }
    if release is None:
        release = gh_json("--method", "POST", api, payload=payload)
    if release["draft"]:
        # Only an unpublished draft may be repaired by a rerun. Published assets are immutable.
        subprocess.run(
            ["gh", "release", "upload", tag, *map(str, paths), "--repo", repo, "--clobber"],
            check=True,
        )
        release = gh_json(f"{api}/{release['id']}")
    published_assets = {asset["name"]: asset for asset in release["assets"]}
    for asset in assets:
        published = published_assets.get(asset["name"])
        if published is None or published["size"] != asset["size"]:
            raise ValueError(f"Archive upload is incomplete: {asset['name']}")
        digest = published.get("digest")
        if digest and digest != f"sha256:{asset['sha256']}":
            raise ValueError(f"Archive checksum differs: {asset['name']}")
    if release["draft"]:
        release = gh_json(
            "--method", "PATCH", f"{api}/{release['id']}",
            payload={"draft": False, "prerelease": True, "make_latest": "false"},
        )
    manifest["commit_title"] = title
    manifest["commit_message"] = message
    for asset in assets:
        asset["browser_download_url"] = published_assets[asset["name"]]["browser_download_url"]
    # Keep the rolling feed's build identity and dates, but use permanent APK URLs.
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Archived {channel} r{build} at {tag}")


if __name__ == "__main__":
    for argument in sys.argv[1:]:
        archive(Path(argument))
