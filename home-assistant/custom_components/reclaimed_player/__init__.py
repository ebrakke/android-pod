"""Reclaimed Player Jellyfin-to-Sonos handoff integration."""

from __future__ import annotations

import asyncio
import logging

import voluptuous as vol

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import ATTR_ENTITY_ID
from homeassistant.core import (
    HomeAssistant,
    ServiceCall,
    ServiceResponse,
    SupportsResponse,
)
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers import config_validation as cv, entity_registry as er

from .const import (
    ATTR_COMMAND,
    ATTR_CURRENT_INDEX,
    ATTR_POSITION_MS,
    ATTR_TRACK_IDS,
    ATTR_WAS_PLAYING,
    DOMAIN,
    SERVICE_CONTINUE_ON,
    SERVICE_CONTROL,
    SERVICE_LIST_PLAYERS,
)
from .stream import ReclaimedPlayerStreamView, StreamProxy

DATA_ENTRIES = "entries"
DATA_STREAM_PROXY = "stream_proxy"
DATA_VIEW_REGISTERED = "view_registered"

_LOGGER = logging.getLogger(__name__)

CONTINUE_ON_SCHEMA = vol.Schema(
    {
        vol.Required(ATTR_ENTITY_ID): cv.entity_id,
        vol.Required(ATTR_TRACK_IDS): vol.All([cv.string], vol.Length(min=1)),
        vol.Required(ATTR_CURRENT_INDEX): vol.Coerce(int),
        vol.Required(ATTR_POSITION_MS): vol.All(vol.Coerce(int), vol.Range(min=0)),
        vol.Required(ATTR_WAS_PLAYING): cv.boolean,
    },
)

CONTROL_SCHEMA = vol.Schema(
    {
        vol.Required(ATTR_ENTITY_ID): cv.entity_id,
        vol.Required(ATTR_COMMAND): vol.In(
            ("play", "pause", "volume_up", "volume_down"),
        ),
    },
)


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Set up the Reclaimed Player actions."""
    domain_data = hass.data.setdefault(
        DOMAIN,
        {
            DATA_ENTRIES: {},
            DATA_STREAM_PROXY: StreamProxy(hass),
            DATA_VIEW_REGISTERED: False,
        },
    )
    entries: dict[str, ConfigEntry] = domain_data[DATA_ENTRIES]
    entries[entry.entry_id] = entry
    if not domain_data[DATA_VIEW_REGISTERED]:
        hass.http.register_view(
            ReclaimedPlayerStreamView(domain_data[DATA_STREAM_PROXY]),
        )
        domain_data[DATA_VIEW_REGISTERED] = True

    if not hass.services.has_service(DOMAIN, SERVICE_LIST_PLAYERS):

        async def list_players(_: ServiceCall) -> ServiceResponse:
            registry = er.async_get(hass)
            players = []
            for entity_entry in registry.entities.values():
                if (
                    entity_entry.domain != "media_player"
                    or entity_entry.platform != "sonos"
                    or entity_entry.disabled
                ):
                    continue
                state = hass.states.get(entity_entry.entity_id)
                if state is None:
                    continue
                players.append(
                    {
                        "entity_id": entity_entry.entity_id,
                        "name": state.name,
                        "state": state.state,
                    },
                )
            players.sort(key=lambda player: player["name"].casefold())
            return {"players": players}

        hass.services.async_register(
            DOMAIN,
            SERVICE_LIST_PLAYERS,
            list_players,
            supports_response=SupportsResponse.ONLY,
        )

    if not hass.services.has_service(DOMAIN, SERVICE_CONTINUE_ON):

        async def continue_on(call: ServiceCall) -> ServiceResponse:
            configured_entry = next(iter(hass.data[DOMAIN][DATA_ENTRIES].values()))
            try:
                await _continue_on(hass, configured_entry, call)
            except Exception as error:  # noqa: BLE001
                _LOGGER.exception("Continue on Sonos failed")
                return {
                    "success": False,
                    "error": str(error) or type(error).__name__,
                }
            return {"success": True}

        hass.services.async_register(
            DOMAIN,
            SERVICE_CONTINUE_ON,
            continue_on,
            schema=CONTINUE_ON_SCHEMA,
            supports_response=SupportsResponse.ONLY,
        )

    if not hass.services.has_service(DOMAIN, SERVICE_CONTROL):

        async def control(call: ServiceCall) -> ServiceResponse:
            entity_id: str = call.data[ATTR_ENTITY_ID]
            command: str = call.data[ATTR_COMMAND]
            try:
                coordinator_id = _sonos_coordinator(hass, entity_id)
                service = {
                    "play": "media_play",
                    "pause": "media_pause",
                    "volume_up": "volume_up",
                    "volume_down": "volume_down",
                }[command]
                await hass.services.async_call(
                    "media_player",
                    service,
                    {},
                    target={ATTR_ENTITY_ID: coordinator_id},
                    blocking=True,
                )
            except Exception as error:  # noqa: BLE001
                _LOGGER.exception("Sonos playback control failed")
                return {
                    "success": False,
                    "error": str(error) or type(error).__name__,
                }
            return {
                "success": True,
                "is_playing": command == "play" if command in ("play", "pause") else None,
                "entity_id": coordinator_id,
            }

        hass.services.async_register(
            DOMAIN,
            SERVICE_CONTROL,
            control,
            schema=CONTROL_SCHEMA,
            supports_response=SupportsResponse.ONLY,
        )

    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Unload Reclaimed Player and remove actions when the last entry is gone."""
    entries: dict[str, ConfigEntry] = hass.data[DOMAIN][DATA_ENTRIES]
    entries.pop(entry.entry_id, None)
    if not entries:
        hass.services.async_remove(DOMAIN, SERVICE_CONTINUE_ON)
        hass.services.async_remove(DOMAIN, SERVICE_CONTROL)
        hass.services.async_remove(DOMAIN, SERVICE_LIST_PLAYERS)
    return True


def _sonos_coordinator(hass: HomeAssistant, entity_id: str) -> str:
    """Validate a Sonos entity and return its current group coordinator."""
    registry = er.async_get(hass)
    registry_entry = registry.async_get(entity_id)
    if registry_entry is None or registry_entry.platform != "sonos":
        raise HomeAssistantError(f"{entity_id} is not a Sonos media player")

    state = hass.states.get(entity_id)
    group_members = state.attributes.get("group_members", []) if state else []
    if isinstance(group_members, list) and group_members:
        candidate = group_members[0]
        candidate_entry = registry.async_get(candidate)
        if candidate_entry is not None and candidate_entry.platform == "sonos":
            return candidate
    return entity_id


async def _continue_on(
    hass: HomeAssistant,
    entry: ConfigEntry,
    call: ServiceCall,
) -> None:
    entity_id: str = call.data[ATTR_ENTITY_ID]
    track_ids: list[str] = call.data[ATTR_TRACK_IDS]
    current_index = min(max(call.data[ATTR_CURRENT_INDEX], 0), len(track_ids) - 1)
    position_seconds = call.data[ATTR_POSITION_MS] / 1000
    was_playing: bool = call.data[ATTR_WAS_PLAYING]

    coordinator_id = _sonos_coordinator(hass, entity_id)

    stream_proxy: StreamProxy = hass.data[DOMAIN][DATA_STREAM_PROXY]
    target = {ATTR_ENTITY_ID: coordinator_id}

    stage = "creating a Home Assistant stream URL"
    try:
        urls = await asyncio.gather(
            *(stream_proxy.create_url(entry, track_id) for track_id in track_ids),
        )
        stage = "clearing the Sonos queue"
        await hass.services.async_call(
            "media_player",
            "clear_playlist",
            {},
            target=target,
            blocking=True,
        )
        for url in urls:
            stage = "adding tracks to the Sonos queue"
            await hass.services.async_call(
                "media_player",
                "play_media",
                {
                    "media_content_id": url,
                    "media_content_type": "music",
                    "enqueue": "add",
                },
                target=target,
                blocking=True,
            )
        stage = "starting the selected Sonos queue item"
        await hass.services.async_call(
            "sonos",
            "play_queue",
            {"queue_position": current_index},
            target=target,
            blocking=True,
        )
        if position_seconds > 0:
            stage = "seeking to the saved playback position"
            await hass.services.async_call(
                "media_player",
                "media_seek",
                {"seek_position": position_seconds},
                target=target,
                blocking=True,
            )
        stage = "restoring the play/pause state"
        await hass.services.async_call(
            "media_player",
            "media_play" if was_playing else "media_pause",
            {},
            target=target,
            blocking=True,
        )
    except Exception as error:
        raise HomeAssistantError(
            f"Failed while {stage}: {error or type(error).__name__}",
        ) from error
