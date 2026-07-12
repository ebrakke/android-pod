"""Opaque authenticated Jellyfin stream proxy for Sonos."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
import secrets
import time
from urllib.parse import quote

from aiohttp import web

from homeassistant.components.http import HomeAssistantView
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.network import get_url

from .const import CONF_JELLYFIN_TOKEN, CONF_JELLYFIN_URL
from .media import jellyfin_stream_url

STREAM_PATH = "/api/reclaimed_player/stream/{token}.{extension}"
STREAM_URL = "/api/reclaimed_player/stream/{}.{}"
STREAM_TOKEN_LIFETIME_SECONDS = 24 * 60 * 60
SONOS_AUDIO_CONTAINERS = frozenset(
    {"aac", "aiff", "alac", "flac", "m4a", "mp3", "mp4", "oga", "ogg", "wav", "wma"},
)
CONTENT_TYPES = {
    "aac": "audio/aac",
    "aiff": "audio/aiff",
    "alac": "audio/mp4",
    "flac": "audio/flac",
    "m4a": "audio/mp4",
    "mp3": "audio/mpeg",
    "mp4": "audio/mp4",
    "oga": "audio/ogg",
    "ogg": "audio/ogg",
    "wav": "audio/wav",
    "wma": "audio/x-ms-wma",
}


@dataclass(frozen=True, slots=True)
class StreamGrant:
    """A temporary mapping from an opaque URL token to one Jellyfin item."""

    entry: ConfigEntry
    track_id: str
    container: str
    expires_at: float


class StreamProxy:
    """Create and serve temporary Sonos-reachable stream URLs."""

    def __init__(self, hass: HomeAssistant) -> None:
        self.hass = hass
        self.grants: dict[str, StreamGrant] = {}
        self.user_ids: dict[str, str | None] = {}
        self.user_id_lock = asyncio.Lock()

    async def _get_user_id(self, entry: ConfigEntry) -> str | None:
        """Resolve the user attached to an access token; API keys have no user."""
        if entry.entry_id in self.user_ids:
            return self.user_ids[entry.entry_id]
        async with self.user_id_lock:
            if entry.entry_id in self.user_ids:
                return self.user_ids[entry.entry_id]
            headers = {"X-Emby-Token": entry.data[CONF_JELLYFIN_TOKEN]}
            session = async_get_clientsession(self.hass)
            url = f"{entry.data[CONF_JELLYFIN_URL]}/Users/Me"
            async with session.get(url, headers=headers, timeout=10) as response:
                user_id = None
                if response.status < 400:
                    user_id = str((await response.json()).get("Id", "")).strip() or None
            self.user_ids[entry.entry_id] = user_id
            return user_id

    async def _get_track_container(
        self,
        entry: ConfigEntry,
        track_id: str,
    ) -> str:
        """Read a track container with either a user token or server API key."""
        headers = {"X-Emby-Token": entry.data[CONF_JELLYFIN_TOKEN]}
        encoded_track_id = quote(track_id, safe="")
        user_id = await self._get_user_id(entry)
        if user_id:
            item_url = (
                f"{entry.data[CONF_JELLYFIN_URL]}/Users/"
                f"{quote(user_id, safe='')}/Items/{encoded_track_id}"
            )
        else:
            item_url = (
                f"{entry.data[CONF_JELLYFIN_URL]}/Items"
                f"?Ids={encoded_track_id}&Limit=1&EnableImages=false"
            )
        session = async_get_clientsession(self.hass)
        async with session.get(item_url, headers=headers, timeout=10) as response:
            if response.status >= 400:
                detail = (await response.text()).strip()[:240]
                raise RuntimeError(
                    f"Jellyfin item lookup returned HTTP {response.status}"
                    f"{f': {detail}' if detail else ''}",
                )
            payload = await response.json()
        item = payload
        if not user_id:
            items = payload.get("Items", [])
            if not items:
                raise RuntimeError(f"Jellyfin track {track_id} was not found")
            item = items[0]
        return str(item.get("Container", "")).lower().split(",", 1)[0].strip()

    async def create_url(self, entry: ConfigEntry, track_id: str) -> str:
        """Create an opaque URL that remains valid for a long album or paused queue."""
        container = await self._get_track_container(entry, track_id)
        if container not in SONOS_AUDIO_CONTAINERS:
            raise ValueError(
                f"Jellyfin track {track_id} uses unsupported container {container or 'unknown'}",
            )

        now = time.monotonic()
        self.grants = {
            token: grant
            for token, grant in self.grants.items()
            if grant.expires_at > now
        }
        token = secrets.token_urlsafe(32)
        self.grants[token] = StreamGrant(
            entry=entry,
            track_id=track_id,
            container=container,
            expires_at=now + STREAM_TOKEN_LIFETIME_SECONDS,
        )
        base_url = get_url(
            self.hass,
            allow_internal=True,
            allow_external=True,
            allow_cloud=False,
            prefer_external=False,
        ).rstrip("/")
        return f"{base_url}{STREAM_URL.format(token, container)}"

    async def serve(
        self,
        request: web.Request,
        token: str,
        extension: str,
    ) -> web.StreamResponse:
        """Proxy a Sonos request to Jellyfin without disclosing Jellyfin credentials."""
        grant = self.grants.get(token)
        if grant is None:
            raise web.HTTPNotFound()
        if extension != grant.container:
            raise web.HTTPNotFound()
        if grant.expires_at <= time.monotonic():
            self.grants.pop(token, None)
            raise web.HTTPGone()

        headers = {"X-Emby-Token": grant.entry.data[CONF_JELLYFIN_TOKEN]}
        if range_header := request.headers.get("Range"):
            headers["Range"] = range_header
        upstream_url = jellyfin_stream_url(
            grant.entry.data[CONF_JELLYFIN_URL],
            grant.track_id,
            grant.container,
        )
        session = async_get_clientsession(self.hass)
        try:
            upstream = await session.request(
                request.method,
                upstream_url,
                headers=headers,
                timeout=None,
            )
        except Exception as error:
            raise web.HTTPBadGateway(text="Unable to reach Jellyfin") from error

        response_headers = {
            name: value
            for name, value in upstream.headers.items()
            if name.lower()
            in {
                "accept-ranges",
                "content-disposition",
                "content-length",
                "content-range",
                "content-type",
                "etag",
                "last-modified",
            }
        }
        if not any(name.lower() == "content-type" for name in response_headers):
            response_headers["Content-Type"] = CONTENT_TYPES[grant.container]
        response = web.StreamResponse(
            status=upstream.status,
            reason=upstream.reason,
            headers=response_headers,
        )
        await response.prepare(request)
        if request.method != "HEAD":
            try:
                async for chunk in upstream.content.iter_chunked(64 * 1024):
                    await response.write(chunk)
            finally:
                upstream.release()
        else:
            upstream.release()
        await response.write_eof()
        return response


class ReclaimedPlayerStreamView(HomeAssistantView):
    """Unauthenticated view secured by an unguessable, expiring URL token."""

    url = STREAM_PATH
    name = "api:reclaimed_player:stream"
    requires_auth = False

    def __init__(self, proxy: StreamProxy) -> None:
        self.proxy = proxy

    async def get(
        self,
        request: web.Request,
        token: str,
        extension: str,
    ) -> web.StreamResponse:
        """Serve a granted Jellyfin track to Sonos."""
        return await self.proxy.serve(request, token, extension)

    async def head(
        self,
        request: web.Request,
        token: str,
        extension: str,
    ) -> web.StreamResponse:
        """Expose audio metadata before Sonos requests the stream body."""
        return await self.proxy.serve(request, token, extension)
