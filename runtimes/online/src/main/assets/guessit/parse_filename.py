import json
import sys
from datetime import date
from pathlib import Path


def values(value):
    if value is None:
        return []
    return value if isinstance(value, list) else [value]


def text(value):
    return next(
        (item.strip() for item in values(value) if isinstance(item, str) and item.strip()),
        None,
    )


def numbers(value):
    items = values(value)
    valid = all(type(item) is int and 0 <= item <= 9999 for item in items)
    return (list(dict.fromkeys(items)), False) if valid else ([], True)


def main():
    if len(sys.argv) != 2 or len(sys.argv[1]) > 4096:
        raise ValueError("Invalid filename input")

    directory = Path(__file__).resolve().parent
    with (directory / "packages.json").open(encoding="utf-8") as manifest_file:
        packages = json.load(manifest_file)["packages"]
    sys.path[:0] = [str(directory / package["file"]) for package in packages]

    from guessit import guessit

    result = guessit(sys.argv[1], {"no_user_config": True, "name_only": True})
    episodes, invalid_episodes = numbers(result.get("episode"))
    seasons, invalid_seasons = numbers(result.get("season"))
    ambiguous = invalid_episodes or invalid_seasons or len(seasons) > 1
    year = next(
        (
            item
            for item in values(result.get("year"))
            if type(item) is int and 1878 <= item <= date.today().year + 1
        ),
        None,
    )
    payload = {
        "title": text(result.get("title")),
        "year": str(year) if year is not None else None,
        "season": seasons[0] if len(seasons) == 1 else (1 if episodes and not ambiguous else None),
        "episode": episodes[0] if episodes and not ambiguous else None,
        "episodeEnd": episodes[-1] if len(episodes) > 1 else None,
        "episodeTitle": text(result.get("episode_title")),
        "type": "tv" if result.get("type") == "episode" else "movie",
        "isEpisodeAmbiguous": ambiguous,
    }
    print("MPVRX_GUESSIT=" + json.dumps(payload, ensure_ascii=True, separators=(",", ":")))


if __name__ == "__main__":
    main()