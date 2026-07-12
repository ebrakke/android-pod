"""Pure Jellyfin media helpers for Reclaimed Player."""

from urllib.parse import quote


def jellyfin_stream_url(jellyfin_url: str, track_id: str, container: str) -> str:
    """Build the authenticated upstream URL for a stable Jellyfin item ID."""
    return (
        f"{jellyfin_url}/Audio/{quote(track_id, safe='')}/"
        f"stream.{quote(container, safe='')}?static=true"
    )
