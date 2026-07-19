# mpvRx Lua and JavaScript Command Guide

This file documents the public scripting surface that mpvRx exposes to mpv Lua
and JavaScript scripts.

Scripts talk to mpvRx by writing string values to properties under:

```text
user-data/mpvrx/*
```

mpvRx observes those properties, performs the native player action, and clears
the command property after handling it. The only exception is the curl bridge:
`curl_request` and `curl_response` are kept long enough for async HTTP handling.

The examples below use public no-auth API endpoints from:

- JSONPlaceholder: https://jsonplaceholder.typicode.com/
- httpbin: https://httpbin.org/

## Quick Start

1. Enable Lua/JS scripting in mpvRx advanced settings.
2. Put script files in the selected mpv config folder.
3. Prefer the `scripts/` subfolder. mpvRx also falls back to the config root.
4. Use `.lua` for Lua scripts and `.js` for JavaScript scripts.
5. Select the scripts in the mpvRx scripts panel.
6. Reopen the video if a script was added before playback started.

mpvRx also syncs `script-opts/` from the selected mpv config folder.

## Important Rules

- Every mpvRx command property is handled as a string.
- Seek values must be integer seconds. Do not send decimals.
- `seek_to_with_text` and `seek_by_with_text` use the format `seconds|message`.
- `curl_request` is async. Always observe `curl_response`.
- Always give curl requests a unique `id` and ignore responses with a different
  `id`.
- JavaScript runs through mpv's JavaScript runtime. Use ES5-compatible syntax:
  `var` and `function` are safest.

## Supported Commands

| Property | Value | What it does |
| --- | --- | --- |
| `user-data/mpvrx/show_text` | Any string | Shows a native mpvRx text overlay. |
| `user-data/mpvrx/toggle_ui` | `show`, `hide`, `toggle` | Shows, hides, or toggles player controls. |
| `user-data/mpvrx/show_panel` | Panel id | Opens a native mpvRx sheet or panel. |
| `user-data/mpvrx/seek_to` | Integer seconds | Seeks to an absolute timestamp. |
| `user-data/mpvrx/seek_by` | Integer seconds | Seeks relative to the current timestamp. |
| `user-data/mpvrx/seek_to_with_text` | `seconds|message` | Absolute seek with overlay text. |
| `user-data/mpvrx/seek_by_with_text` | `seconds|message` | Relative seek with overlay text. |
| `user-data/mpvrx/software_keyboard` | `show`, `hide`, `toggle` | Controls the Android software keyboard. |
| `user-data/mpvrx/curl_request` | JSON string | Runs an async HTTP request through native curl. |
| `user-data/mpvrx/curl_response` | JSON string | Response written by mpvRx. Scripts should observe it. |

Supported panel ids:

| Panel id | Result |
| --- | --- |
| `frame_navigation` | Opens the frame navigation sheet. |
| `subtitle_settings` | Opens subtitle style settings. |
| `subtitle_delay` | Opens subtitle delay controls. |
| `audio_delay` | Opens audio delay controls. |
| `video_filters` | Opens video filter controls. |
| `lua_scripts` | Opens the scripts panel. |
| `hdr_screen_output` | Opens HDR screen output controls. |

Observed but not public commands:

`set_button_title`, `reset_button_title`, and `toggle_button` are currently
observed by the mpv property observer, but the player command dispatcher does
not implement public behavior for them. Treat them as reserved.

## Full Working Lua Example

Save this as `mpvrx_demo.lua` in your mpv `scripts/` folder.

It fetches a public JSONPlaceholder post, shows the post title, and includes
key bindings for common mpvRx commands.

```lua
-- mpvrx_demo.lua

local utils = require("mp.utils")

local REQUEST_ID = "mpvrx-demo-lua-post"

local function mpvrx(command, value)
    mp.set_property("user-data/mpvrx/" .. command, tostring(value))
end

local function show(message)
    mpvrx("show_text", message)
end

local function fetch_post()
    show("Fetching JSONPlaceholder post...")

    mpvrx("curl_request", utils.format_json({
        id = REQUEST_ID,
        url = "https://jsonplaceholder.typicode.com/posts/1",
        method = "GET",
        headers = {
            Accept = "application/json",
        },
        timeout = 15,
    }))
end

mp.observe_property("user-data/mpvrx/curl_response", "string", function(_, value)
    if value == nil or value == "" then return end

    local res = utils.parse_json(value)
    if res == nil or res.id ~= REQUEST_ID then return end

    if res.error ~= nil then
        show("Curl failed: " .. tostring(res.error))
        return
    end

    if tonumber(res.status) ~= 200 then
        show("HTTP " .. tostring(res.status))
        return
    end

    local body = utils.parse_json(res.body)
    if body == nil then
        show("Could not parse response body")
        return
    end

    show("Post #" .. tostring(body.id) .. "\n" .. tostring(body.title))
end)

mp.register_event("file-loaded", fetch_post)

mp.add_key_binding("J", "mpvrx-fetch-jsonplaceholder-post", fetch_post)
mp.add_key_binding("U", "mpvrx-toggle-ui", function()
    mpvrx("toggle_ui", "toggle")
end)
mp.add_key_binding("V", "mpvrx-open-video-filters", function()
    mpvrx("show_panel", "video_filters")
end)
mp.add_key_binding("RIGHT", "mpvrx-seek-forward", function()
    mpvrx("seek_by_with_text", "30|Forward 30 seconds")
end)
mp.add_key_binding("LEFT", "mpvrx-seek-back", function()
    mpvrx("seek_by_with_text", "-10|Back 10 seconds")
end)
```

## Full Working JavaScript Example

Save this as `mpvrx_demo.js` in your mpv `scripts/` folder.

This example uses ES5-style JavaScript for mpv compatibility. It sends a POST
request to JSONPlaceholder and shows the fake created post id returned by the
public test API.

```javascript
// mpvrx_demo.js

var REQUEST_ID = "mpvrx-demo-js-create-post";

function mpvrx(command, value) {
    mp.set_property("user-data/mpvrx/" + command, String(value));
}

function show(message) {
    mpvrx("show_text", message);
}

function createPost() {
    var payload = {
        title: "mpvRx JavaScript curl test",
        body: "Posted from an mpv JavaScript script through mpvRx curl.",
        userId: 1
    };

    show("Posting to JSONPlaceholder...");

    mpvrx("curl_request", JSON.stringify({
        id: REQUEST_ID,
        url: "https://jsonplaceholder.typicode.com/posts",
        method: "POST",
        headers: {
            "Accept": "application/json",
            "Content-Type": "application/json"
        },
        body: JSON.stringify(payload),
        content_type: "application/json",
        timeout: 15
    }));
}

mp.observe_property("user-data/mpvrx/curl_response", "string", function(name, value) {
    if (!value) return;

    var res;
    try {
        res = JSON.parse(value);
    } catch (e) {
        return;
    }

    if (!res || res.id !== REQUEST_ID) return;

    if (res.error) {
        show("Curl failed: " + res.error);
        return;
    }

    if (res.status < 200 || res.status >= 300) {
        show("HTTP " + res.status);
        return;
    }

    var body;
    try {
        body = JSON.parse(res.body);
    } catch (e2) {
        show("Could not parse response body");
        return;
    }

    show("Created fake post #" + body.id + "\nHTTP " + res.status);
});

mp.add_key_binding("P", "mpvrx-create-jsonplaceholder-post", createPost);
mp.add_key_binding("U", "mpvrx-toggle-ui-js", function() {
    mpvrx("toggle_ui", "toggle");
});
mp.add_key_binding("S", "mpvrx-open-subtitle-settings-js", function() {
    mpvrx("show_panel", "subtitle_settings");
});
mp.add_key_binding("K", "mpvrx-show-keyboard-js", function() {
    mpvrx("software_keyboard", "show");
});
```

## Command Reference

### `show_text`

Shows a short native mpvRx overlay.

Lua:

```lua
mp.set_property("user-data/mpvrx/show_text", "Shaders enabled")
```

JavaScript:

```javascript
mp.set_property("user-data/mpvrx/show_text", "Shaders enabled");
```

### `toggle_ui`

Controls the player controls overlay.

Accepted values:

- `show`
- `hide`
- `toggle`

Lua:

```lua
mp.set_property("user-data/mpvrx/toggle_ui", "hide")
```

JavaScript:

```javascript
mp.set_property("user-data/mpvrx/toggle_ui", "toggle");
```

### `show_panel`

Opens a native mpvRx sheet or panel.

Lua:

```lua
mp.set_property("user-data/mpvrx/show_panel", "frame_navigation")
mp.set_property("user-data/mpvrx/show_panel", "video_filters")
```

JavaScript:

```javascript
mp.set_property("user-data/mpvrx/show_panel", "lua_scripts");
mp.set_property("user-data/mpvrx/show_panel", "hdr_screen_output");
```

### `seek_to`

Seeks to an absolute timestamp in integer seconds.

```lua
mp.set_property("user-data/mpvrx/seek_to", "600")
```

### `seek_by`

Seeks relative to the current timestamp in integer seconds.

```lua
mp.set_property("user-data/mpvrx/seek_by", "30")
mp.set_property("user-data/mpvrx/seek_by", "-10")
```

### `seek_to_with_text`

Seeks to an absolute timestamp and shows custom overlay text.

Value format:

```text
seconds|message
```

Lua:

```lua
mp.set_property("user-data/mpvrx/seek_to_with_text", "90|Jumping to intro")
```

JavaScript:

```javascript
mp.set_property("user-data/mpvrx/seek_to_with_text", "3600|Jumping to final act");
```

### `seek_by_with_text`

Seeks relative to the current timestamp and shows custom overlay text.

Lua:

```lua
mp.set_property("user-data/mpvrx/seek_by_with_text", "85|Skipping opening")
mp.set_property("user-data/mpvrx/seek_by_with_text", "-15|Back 15 seconds")
```

JavaScript:

```javascript
mp.set_property("user-data/mpvrx/seek_by_with_text", "30|Forward 30 seconds");
```

### `software_keyboard`

Controls the Android software keyboard.

Accepted values:

- `show`
- `hide`
- `toggle`

Lua:

```lua
mp.set_property("user-data/mpvrx/software_keyboard", "show")
```

JavaScript:

```javascript
mp.set_property("user-data/mpvrx/software_keyboard", "hide");
```

## Curl Bridge

The curl bridge lets Lua and JavaScript scripts make HTTP requests through the
native libcurl bridge. Scripts write a JSON request to:

```text
user-data/mpvrx/curl_request
```

mpvRx writes the response to:

```text
user-data/mpvrx/curl_response
```

Requests are async. Playback continues while the request runs.

### Curl Request JSON

| Field | Type | Required | Default | Notes |
| --- | --- | --- | --- | --- |
| `id` | string | No | UUID generated by mpvRx | Use your own id so scripts can match responses. |
| `url` | string | Yes | none | Must not be blank. Use `http://` or `https://`. |
| `method` | string | No | `GET` | Supported: `GET`, `HEAD`, `POST`, `PUT`, `PATCH`, `DELETE`. |
| `headers` | object | No | `{}` | String key/value request headers. Maximum 64 headers. |
| `body` | string | No | null | Sent for `POST`, `PUT`, and `PATCH`. |
| `content_type` | string | No | `text/plain; charset=utf-8` | Sent as `Content-Type` when not blank. |
| `timeout` | integer | No | `30` | Clamped to 1 through 120 seconds. |

Lua request:

```lua
local utils = require("mp.utils")

mp.set_property("user-data/mpvrx/curl_request", utils.format_json({
    id = "lua-httpbin-get",
    url = "https://httpbin.org/get",
    method = "GET",
    headers = {
        Accept = "application/json",
    },
    timeout = 10,
}))
```

JavaScript request:

```javascript
mp.set_property("user-data/mpvrx/curl_request", JSON.stringify({
    id: "js-httpbin-get",
    url: "https://httpbin.org/get",
    method: "GET",
    headers: {
        "Accept": "application/json"
    },
    timeout: 10
}));
```

### Curl Response JSON

| Field | Type | Notes |
| --- | --- | --- |
| `id` | string | Echoes the request id, or generated id if omitted. |
| `status` | integer | HTTP status code. `0` means bridge/network/native error. |
| `body` | string | UTF-8 response body. Capped at 2 MB. |
| `headers` | object | Response headers as string key/value pairs. |
| `error` | string or null | Missing/null on success. String message on failure. |

Lua observer:

```lua
local utils = require("mp.utils")

mp.observe_property("user-data/mpvrx/curl_response", "string", function(_, value)
    if value == nil or value == "" then return end

    local res = utils.parse_json(value)
    if res == nil or res.id ~= "lua-httpbin-get" then return end

    if res.error ~= nil then
        mp.set_property("user-data/mpvrx/show_text", "Curl error: " .. res.error)
        return
    end

    mp.set_property("user-data/mpvrx/show_text", "HTTP " .. tostring(res.status))
end)
```

JavaScript observer:

```javascript
mp.observe_property("user-data/mpvrx/curl_response", "string", function(name, value) {
    if (!value) return;

    var res;
    try {
        res = JSON.parse(value);
    } catch (e) {
        return;
    }

    if (!res || res.id !== "js-httpbin-get") return;

    if (res.error) {
        mp.set_property("user-data/mpvrx/show_text", "Curl error: " + res.error);
        return;
    }

    mp.set_property("user-data/mpvrx/show_text", "HTTP " + res.status);
});
```

### Curl Limits and Behavior

- Body capture is capped at 2 MB.
- Header capture is capped at 256 KB.
- Request headers are capped at 64 entries.
- Redirects are followed for HTTP and HTTPS.
- Only HTTP and HTTPS URLs are allowed.
- Timeout applies to connect and total request time.
- `DELETE` requests do not send a body in the native bridge.
- `curl_response` is not cleared automatically. Always check `id`.

## Custom Buttons

mpvRx custom buttons support Lua and JavaScript actions.

In the Custom Button editor:

- `Button title` is the text shown in the player UI.
- `Tap action` is required.
- `Long press action` is optional.
- `On startup` is optional.
- `Script language` can be Lua or JavaScript.

Paste only the action body into the editor. mpvRx wraps it in a generated
script and registers the correct script message internally.

Lua tap action example:

```lua
mp.set_property("deband", "yes")
mp.set_property("user-data/mpvrx/show_text", "Deband enabled")
```

Lua long press action example:

```lua
mp.set_property("deband", "no")
mp.set_property("user-data/mpvrx/show_text", "Deband disabled")
```

JavaScript tap action example:

```javascript
mp.set_property("video-zoom", "0.25");
mp.set_property("user-data/mpvrx/show_text", "Zoom 25%");
```

JavaScript long press action example:

```javascript
mp.set_property("video-zoom", "0");
mp.set_property("user-data/mpvrx/show_text", "Zoom reset");
```

Generated custom button internals:

- mpvRx writes generated scripts to the app internal `scripts/` directory.
- Lua buttons are generated into `custombuttons.lua`.
- JavaScript buttons are generated into `custombuttons.js`.
- Tapping a button sends `script-message call_button_<safe_id>`.
- Long pressing a button sends `script-message call_button_long_<safe_id>`.
- `<safe_id>` is the internal button id with `-` replaced by `_`.
- Generated Lua actions are guarded by `is_active_instance()`.
- Generated JavaScript actions are guarded by `isActiveInstance()`.

Do not call `is_active_instance()` or `isActiveInstance()` from normal script
files. They exist only inside generated custom button scripts.

## Android Telemetry Properties

mpvRx writes Android device state into mpv `user-data/android/*` properties.
Scripts can read or observe these values.

| Property | Type | Meaning |
| --- | --- | --- |
| `user-data/android/battery-level` | integer | Battery level from 0 to 100. |
| `user-data/android/battery-charging` | boolean | `true` when charging. |
| `user-data/android/battery-plugged` | boolean | `true` when plugged into power. |

Lua telemetry example:

```lua
mp.observe_property("user-data/android/battery-level", "native", function(_, level)
    if level == nil then return end

    local charging = mp.get_property_native("user-data/android/battery-charging")
    if tonumber(level) < 15 and not charging then
        mp.set_property("deband", "no")
        mp.set_property("user-data/mpvrx/show_text", "Low battery: deband disabled")
    end
end)
```

JavaScript telemetry example:

```javascript
mp.observe_property("user-data/android/battery-level", "native", function(name, level) {
    if (level === null || level === undefined) return;

    var charging = mp.get_property_native("user-data/android/battery-charging");
    if (Number(level) < 15 && !charging) {
        mp.set_property("deband", "no");
        mp.set_property("user-data/mpvrx/show_text", "Low battery: deband disabled");
    }
});
```

## Troubleshooting

If a command does nothing:

- Confirm scripting is enabled in mpvRx settings.
- Confirm the script is selected in the scripts panel.
- Confirm the script file extension is `.lua` or `.js`.
- Use integer seconds for seek commands.
- Use the exact command property path.
- For curl, check `res.id` before handling the response.
- For curl, check `res.error` and `res.status`.
- Reopen the video after adding a new script.

Minimal smoke test:

Lua:

```lua
mp.register_event("file-loaded", function()
    mp.set_property("user-data/mpvrx/show_text", "Lua script loaded")
end)
```

JavaScript:

```javascript
mp.register_event("file-loaded", function() {
    mp.set_property("user-data/mpvrx/show_text", "JavaScript script loaded");
});
```
