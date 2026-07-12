"""Config flow for Reclaimed Player."""

from __future__ import annotations

from typing import Any

import voluptuous as vol

from homeassistant.config_entries import ConfigFlow, ConfigFlowResult
from homeassistant.helpers.aiohttp_client import async_get_clientsession

from .const import CONF_JELLYFIN_TOKEN, CONF_JELLYFIN_URL, DOMAIN


class ReclaimedPlayerConfigFlow(ConfigFlow, domain=DOMAIN):
    """Configure the Jellyfin connection used for Sonos handoff."""

    VERSION = 1

    async def async_step_user(
        self,
        user_input: dict[str, Any] | None = None,
    ) -> ConfigFlowResult:
        if self._async_current_entries():
            return self.async_abort(reason="single_instance_allowed")
        errors: dict[str, str] = {}
        if user_input is not None:
            jellyfin_url = user_input[CONF_JELLYFIN_URL].strip().rstrip("/")
            jellyfin_token = user_input[CONF_JELLYFIN_TOKEN].strip()
            try:
                async with async_get_clientsession(self.hass).get(
                    f"{jellyfin_url}/System/Info",
                    headers={"X-Emby-Token": jellyfin_token},
                    timeout=10,
                ) as response:
                    if response.status in (401, 403):
                        errors["base"] = "invalid_auth"
                    elif response.status >= 400:
                        errors["base"] = "cannot_connect"
            except Exception:  # noqa: BLE001
                errors["base"] = "cannot_connect"

            if not errors:
                await self.async_set_unique_id(jellyfin_url.lower())
                self._abort_if_unique_id_configured()
                return self.async_create_entry(
                    title="Reclaimed Player",
                    data={
                        CONF_JELLYFIN_URL: jellyfin_url,
                        CONF_JELLYFIN_TOKEN: jellyfin_token,
                    },
                )

        schema = vol.Schema(
            {
                vol.Required(CONF_JELLYFIN_URL): str,
                vol.Required(CONF_JELLYFIN_TOKEN): str,
            },
        )
        return self.async_show_form(step_id="user", data_schema=schema, errors=errors)
