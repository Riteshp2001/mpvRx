import base64
import json
import os
import shutil
import sys
import urllib.request

import mutagen
from mutagen.flac import FLAC, Picture
from mutagen.id3 import APIC, TALB, TIT2, TPE1, TPE2, TDRC, TRCK
from mutagen.mp3 import MP3
from mutagen.mp4 import MP4, MP4Cover
from mutagen.oggopus import OggOpus
from mutagen.oggvorbis import OggVorbis


def text(metadata, *keys):
    for key in keys:
        value = metadata.get(key)
        if value is not None and str(value).strip():
            return str(value).strip()
    return None


def cover(metadata):
    url = metadata.get("thumbnail")
    if not isinstance(url, str) or not url.startswith("https://"):
        return None
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "mpvRx"})
        with urllib.request.urlopen(request, timeout=8) as response:
            data = response.read(3 * 1024 * 1024 + 1)
        if len(data) > 3 * 1024 * 1024:
            return None
        if data.startswith(b"\xff\xd8\xff"):
            return data, "image/jpeg"
        if data.startswith(b"\x89PNG\r\n\x1a\n"):
            return data, "image/png"
    except Exception:
        return None
    return None


def embed(path, metadata, temporary):
    original = mutagen.File(path)
    if not isinstance(original, (MP3, MP4, FLAC, OggOpus, OggVorbis)):
        return
    tags = {
        "title": text(metadata, "track", "title"),
        "artist": text(metadata, "artist", "album_artist", "uploader"),
        "album": text(metadata, "album"),
        "albumartist": text(metadata, "album_artist"),
        "date": text(metadata, "release_year"),
        "tracknumber": text(metadata, "track_number"),
    }
    artwork = cover(metadata)
    try:
        shutil.copy2(path, temporary)
        audio = mutagen.File(temporary)
        if audio.tags is None:
            audio.add_tags()
        if isinstance(audio, MP3):
            frames = {"title": TIT2, "artist": TPE1, "album": TALB, "albumartist": TPE2, "date": TDRC, "tracknumber": TRCK}
            for key, value in tags.items():
                if value:
                    audio.tags.add(frames[key](encoding=3, text=[value]))
            if artwork:
                data, mime = artwork
                audio.tags.setall("APIC", [APIC(encoding=3, mime=mime, type=3, desc="Cover", data=data)])
        elif isinstance(audio, MP4):
            atoms = {"title": "\xa9nam", "artist": "\xa9ART", "album": "\xa9alb", "albumartist": "aART", "date": "\xa9day"}
            for key, atom in atoms.items():
                if tags[key]:
                    audio.tags[atom] = [tags[key]]
            track = tags["tracknumber"]
            if track and track.isdecimal():
                audio.tags["trkn"] = [(int(track), 0)]
            if artwork:
                data, mime = artwork
                audio.tags["covr"] = [MP4Cover(data, imageformat=MP4Cover.FORMAT_PNG if mime == "image/png" else MP4Cover.FORMAT_JPEG)]
        else:
            for key, value in tags.items():
                if value:
                    audio.tags[key] = [value]
            if artwork:
                data, mime = artwork
                picture = Picture()
                picture.type = 3
                picture.mime = mime
                picture.desc = "Cover"
                picture.data = data
                if isinstance(audio, FLAC):
                    audio.clear_pictures()
                    audio.add_picture(picture)
                else:
                    audio.tags["metadata_block_picture"] = [base64.b64encode(picture.write()).decode("ascii")]
        audio.save()
        with open(temporary, "rb") as saved:
            os.fsync(saved.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


if __name__ == "__main__":
    with open(sys.argv[2], encoding="utf-8") as metadata_file:
        embed(os.path.abspath(sys.argv[1]), json.load(metadata_file), os.path.abspath(sys.argv[3]))