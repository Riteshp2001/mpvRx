# MPV_RX Custom Commands & User Data Integration Guide

This guide details the custom `user-data` properties, OSD messaging, panel triggers, soft-keyboard control, dynamic button script-messages, and Android system telemetry available in **MpvRx**.

Developers and power users can use these custom properties and messages within their `mpv` Lua/JS scripts to build customized UI overlays, launch native Android player panels, or coordinate custom interactions.

---

## 1. Custom Player Action Properties (`user-data/mpvrx/*`)

MpvRx listens for updates to the observed properties under `user-data/mpvrx/*`. When a script sets one of these properties, MpvRx captures the value, performs the action, and clears the property to prepare for the next command.

### 📑 Summary of Supported Actions

| Property Subpath | Expected Value Format | Action Performed |
| :--- | :--- | :--- |
| [`show_text`](#1-show_text) | `String` | Displays a premium text overlay / OSD message on the player UI. |
| [`toggle_ui`](#2-toggle_ui) | `"show"` \| `"hide"` \| `"toggle"` | Controls visibility of the player UI controls. |
| [`show_panel`](#3-show_panel) | Panel Identifier (`String`) | Opens a native MpvRx bottom sheet or settings panel. |
| [`seek_to_with_text`](#4-seek_to_with_text) | `"<seconds>\|<message>"` | Absolute seek to timestamp while showing custom overlay text. |
| [`seek_by_with_text`](#5-seek_by_with_text) | `"<seconds>\|<message>"` | Relative seek by delta (seconds) showing custom overlay text. |
| [`seek_to`](#6-seek_to) | `String` (representing Integer seconds) | Absolute seek without any text overlay. |
| [`seek_by`](#7-seek_by) | `String` (representing Integer seconds) | Relative seek without any text overlay. |
| [`software_keyboard`](#8-software_keyboard) | `"show"` \| `"hide"` \| `"toggle"` | Forces visibility state of the system software keyboard. |
| [`curl_request`](#9-curl_request) | JSON Object (see below) | Fires an async HTTP request via native libcurl (HTTP/2, HTTP/3, TLS). Response is written to `curl_response`. |
| [`curl_response`](#9-curl_request) | JSON Object (read-only, observe) | Receives the HTTP response for a previously issued `curl_request`. |

---

### Detailed Properties & Examples

#### 1. `show_text`
Displays a text string directly on the player screen via MpvRx's native UI overlay.
*   **Property:** `user-data/mpvrx/show_text`
*   **Lua Example:**
    ```lua
    mp.set_property("user-data/mpvrx/show_text", "Anime4K Shaders Activated!")
    ```
*   **JS Example:**
    ```javascript
    mp.set_property("user-data/mpvrx/show_text", "Anime4K Shaders Activated!");
    ```

#### 2. `toggle_ui`
Controls the visibility of the primary video controls overlay.
*   **Property:** `user-data/mpvrx/toggle_ui`
*   **Accepted Values:**
    *   `"show"`: Forces the control overlay to appear.
    *   `"hide"`: Forces all controls, sheets, and active panels to slide out of view.
    *   `"toggle"`: Toggles the controls overlay visibility state.
*   **Lua Example:**
    ```lua
    mp.set_property("user-data/mpvrx/toggle_ui", "toggle")
    ```

#### 3. `show_panel`
Launches native player panels and bottom sheets directly from scripts.
*   **Property:** `user-data/mpvrx/show_panel`
*   **Accepted Values:**
    *   `"frame_navigation"`: Launches the high-precision frame navigation bottom sheet.
    *   `"subtitle_settings"`: Launches the subtitle style & typography settings panel.
    *   `"subtitle_delay"`: Launches the subtitle delay adjustment panel.
    *   `"audio_delay"`: Launches the audio delay adjustment panel.
    *   `"video_filters"`: Launches the color correction & sharpness filters panel.
    *   `"lua_scripts"`: Launches the Lua scripts configuration panel.
    *   `"hdr_screen_output"`: Launches the Vulkan HDR screen configuration panel.
*   **Lua Example:**
    ```lua
    -- Open Video Filters directly when an option is selected
    mp.set_property("user-data/mpvrx/show_panel", "video_filters")
    ```

#### 4. `seek_to_with_text`
Performs an absolute seek while presenting a clean, modern seek overlay with descriptive text.
*   **Property:** `user-data/mpvrx/seek_to_with_text`
*   **Value Format:** `"<seek_position_in_seconds>|<overlay_message_text>"`
*   **Lua Example:**
    ```lua
    -- Seek to exactly 5 minutes (300 seconds) and notify the user
    mp.set_property("user-data/mpvrx/seek_to_with_text", "300|Skipping Opening Theme")
    ```

#### 5. `seek_by_with_text`
Performs a relative seek by delta seconds and shows a descriptive OSD overlay.
*   **Property:** `user-data/mpvrx/seek_by_with_text`
*   **Value Format:** `"<seek_delta_in_seconds>|<overlay_message_text>"`
*   **Lua Example:**
    ```lua
    -- Seek forward by 85 seconds
    mp.set_property("user-data/mpvrx/seek_by_with_text", "85|Fast Forwarding to Recap")
    
    -- Seek backward by 10 seconds
    mp.set_property("user-data/mpvrx/seek_by_with_text", "-10|Rewinding...")
    ```

#### 6. `seek_to`
Performs a silent absolute seek to the specified timestamp.
*   **Property:** `user-data/mpvrx/seek_to`
*   **Value Format:** `"seconds"` (String)
*   **Lua Example:**
    ```lua
    mp.set_property("user-data/mpvrx/seek_to", "600")
    ```

#### 7. `seek_by`
Performs a silent relative seek by a delta.
*   **Property:** `user-data/mpvrx/seek_by`
*   **Value Format:** `"delta_seconds"` (String)
*   **Lua Example:**
    ```lua
    mp.set_property("user-data/mpvrx/seek_by", "-30")
    ```

#### 8. `software_keyboard`
Forces the Android IME software keyboard to show or hide. Useful for scripts requiring keyboard entry.
*   **Property:** `user-data/mpvrx/software_keyboard`
*   **Accepted Values:**
    *   `"show"`: Forces showing the keyboard.
    *   `"hide"`: Forces hiding the keyboard.
    *   `"toggle"`: Toggles keyboard visibility based on current focus state.
*   **Lua Example:**
    ```lua
    mp.set_property("user-data/mpvrx/software_keyboard", "show")
    ```

---

## 2. Dynamic Custom Buttons API

MpvRx allows users to configure custom buttons through the application preferences (via JSON schema slot configuration). These custom buttons are compiled dynamically into LUA/JS script files during player initialization.

### Event Routing Workflow
```
[User Taps Custom Button in UI]
             │
             ▼
[MpvRx fires Native command("script-message", "call_button_<safe_id>")]
             │
             ▼
[Registered LUA/JS script-message callback executes custom logic]
```

### Script Message Registration
When you create a custom button, MpvRx wraps your action/startup code in a clean, stateful lifecycle. The target script registers the following script-messages:
1.  **Click Action:** `call_button_<safe_id>`
2.  **Long Press Action:** `call_button_long_<safe_id>`

> [!NOTE]
> `<safe_id>` is the custom button's identifier with hyphens (`-`) automatically replaced by underscores (`_`).

#### Lua Custom Button Script Example
```lua
-- MpvRx automatically manages active instance safety via: is_active_instance()
function button_custom_toggle_deband()
    if not is_active_instance() then return end
    
    local current = mp.get_property("deband")
    if current == "yes" then
        mp.set_property("deband", "no")
        mp.set_property("user-data/mpvrx/show_text", "Debanding: Disabled")
    else
        mp.set_property("deband", "yes")
        mp.set_property("user-data/mpvrx/show_text", "Debanding: Enabled")
    end
end

-- Register to the script message called by the MpvRx button tap event
mp.register_script_message('call_button_custom_toggle_deband', button_custom_toggle_deband)
```

---

## 3. Real-Time Android Telemetry (`user-data/android/*`)

MpvRx writes system status information directly into MPV properties. Scripts can read or observe these properties to update script-rendered OSDs or trigger custom power-saving modes.

*   `user-data/android/battery-level` (Integer, `0` - `100`): Current battery percentage level.
*   `user-data/android/battery-charging` (Boolean, `true`/`false`): Whether the device is actively charging.
*   `user-data/android/battery-plugged` (Boolean, `true`/`false`): Whether the device is connected to a power outlet (AC, USB, or wireless).

#### Lua Telemetry Observer Example
```lua
function handle_battery_change(name, level)
    if level == nil then return end
    
    local level_num = tonumber(level)
    if level_num < 15 and not mp.get_property_bool("user-data/android/battery-charging") then
        -- Enable low power mode inside MPV
        mp.set_property("deband", "no")
        mp.set_property("user-data/mpvrx/show_text", "Battery Low! Disabling deband filter to save power.")
    end
end

-- Observe battery level changes
mp.observe_property("user-data/android/battery-level", "native", handle_battery_change)
```

---

## 4. HTTP / Curl Bridge (`user-data/mpvrx/curl_request` + `curl_response`)

MpvRx exposes a full async HTTP client to Lua and JS scripts via native libcurl (built from curl-android, supporting HTTP/2, HTTP/3 with BoringSSL). Scripts write a JSON request object to `user-data/mpvrx/curl_request`; the response is written back to `user-data/mpvrx/curl_response` once the network call completes.

> [!IMPORTANT]
> Requests are **non-blocking** — the script continues executing immediately after setting `curl_request`. Observe `curl_response` to receive the result. Use the `id` field to correlate requests with responses when making multiple concurrent calls.

### How it works

```
[Script sets user-data/mpvrx/curl_request = JSON]
                    │
                    ▼
        [MpvRx parses the request]
                    │
                    ▼
     [libcurl executes on background thread]
          (video playback unaffected)
                    │
                    ▼
[MpvRx writes user-data/mpvrx/curl_response = JSON]
                    │
                    ▼
  [Script observer fires → handle the result]
                    │
                    ▼
  [Script updates show_text → OSD appears on screen]
```

---

### Request Format

Write a JSON string to `user-data/mpvrx/curl_request`:

| Field | Type | Required | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| `id` | `string` | ✅ | — | Unique identifier echoed back in the response. Use this to match responses to requests. |
| `url` | `string` | ✅ | — | Full URL including scheme (`https://...`). |
| `method` | `string` | ❌ | `"GET"` | HTTP method: `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`. |
| `headers` | `object` | ❌ | `{}` | Key-value map of request headers. |
| `body` | `string` | ❌ | `""` | Raw request body (used for `POST`, `PUT`, `PATCH`). |
| `content_type` | `string` | ❌ | `"text/plain; charset=utf-8"` | `Content-Type` header for the request body. |
| `timeout` | `integer` | ❌ | `30` | Timeout in seconds (1–120). |

### Response Format

Observe `user-data/mpvrx/curl_response` to receive a JSON string:

| Field | Type | Description |
| :--- | :--- | :--- |
| `id` | `string` | The `id` from the original request. |
| `status` | `integer` | HTTP status code (e.g. `200`, `404`). `0` on network/timeout error. |
| `body` | `string` | Response body as a UTF-8 string. Truncated at 2 MB with `[truncated]` suffix. |
| `headers` | `object` | Response headers as a key-value map. |
| `error` | `string\|null` | `null` on success. Error message string on network failure or timeout. |

---

### Real-World Example — ZenQuotes API

[ZenQuotes](https://zenquotes.io) is a free public API that returns a random inspirational quote. No API key needed.

**Endpoint:** `GET https://zenquotes.io/api/random`

**Response shape:**
```json
[
  {
    "q": "Perseverance and spirit have done wonders in all ages.",
    "a": "George Washington",
    "h": "<blockquote>...</blockquote>"
  }
]
```

The response is a JSON **array**. The quote text is in `[0].q` and the author is in `[0].a`.

#### Expected OSD output

When the script runs, you will see the raw response details formatted and displayed as an OSD text overlay on the player screen for 20 seconds. It will show the request ID, status code, and raw body containing the quote data.

---

#### Lua — Fetch a random quote and show it on screen

Save as `quote_of_session.lua` in your MPV scripts folder.

```lua
-- quote_of_session.lua
-- Fetches a random quote and prints the raw response as an OSD message for 20 seconds.

local utils = require("mp.utils")

mp.observe_property("user-data/mpvrx/curl_response", "string", function(_, value)
    if not value then return end

    local res = utils.parse_json(value)

    mp.osd_message(
        utils.format_json(res),
        20
    )
end)

mp.register_event("file-loaded", function()
    mp.set_property("user-data/mpvrx/curl_request",
        utils.format_json({
            id = "quote",
            url = "https://zenquotes.io/api/random",
            method = "GET"
        })
    )
end)
```

---

#### Lua — Fetch a new quote on key press (bound to a custom button)

```lua
-- quote_on_demand.lua
-- Press Q or tap the custom button to fetch a quote at any time.

local utils = require("mp.utils")

mp.observe_property("user-data/mpvrx/curl_response", "string", function(name, value)
    if value == nil or value == "" then return end
    local res = utils.parse_json(value)
    if res == nil or res.id ~= "quote-demand" then return end

    if res.error or res.status ~= 200 then
        mp.set_property("user-data/mpvrx/show_text",
            "Could not fetch quote")
        return
    end

    local data = utils.parse_json(res.body)
    if data and data[1] then
        mp.set_property("user-data/mpvrx/show_text",
            data[1].q .. "\n-- " .. data[1].a)
    end
end)

local function fetch_quote()
    -- OSD: Show fetching indicator immediately
    mp.set_property("user-data/mpvrx/show_text", "Fetching quote...")
    mp.set_property("user-data/mpvrx/curl_request", utils.format_json({
        id      = "quote-demand",
        url     = "https://zenquotes.io/api/random",
        method  = "GET",
        timeout = 10,
    }))
end

mp.register_script_message("call_button_quote_on_demand", fetch_quote)
mp.add_key_binding("Q", "fetch-quote", fetch_quote)
```

---

#### JavaScript — Fetch a random quote on file load

Save as `quote_of_session.js` in your MPV scripts folder.

```javascript
// quote_of_session.js
// Fetches a random quote when a file loads and shows it as OSD.

mp.observe_property("user-data/mpvrx/curl_response", "string", function(name, value) {
    if (!value) return;

    let res;
    try { res = JSON.parse(value); } catch (e) { return; }

    if (res.id !== "zenquotes-random-js") return;

    // OSD: Network / timeout error
    if (res.error) {
        mp.set_property("user-data/mpvrx/show_text",
            "Quote fetch failed: " + res.error);
        return;
    }

    // OSD: Non-200 status
    if (res.status !== 200) {
        mp.set_property("user-data/mpvrx/show_text",
            "Quote API error: HTTP " + res.status);
        return;
    }

    // Parse ZenQuotes response array
    let data;
    try { data = JSON.parse(res.body); } catch (e) {
        mp.set_property("user-data/mpvrx/show_text",
            "Could not parse quote response");
        return;
    }

    if (!data || !data[0]) return;

    const quote  = data[0].q || "...";
    const author = data[0].a || "Unknown";

    // OSD: Show the quote on screen
    mp.set_property("user-data/mpvrx/show_text",
        "\u201C" + quote + "\u201D\n\u2014 " + author);
});

// Fire the request when the file starts
mp.register_event("file-loaded", function() {
    // OSD: Let the user know we are fetching
    mp.set_property("user-data/mpvrx/show_text",
        "Fetching quote of the day...");

    mp.set_property("user-data/mpvrx/curl_request", JSON.stringify({
        id:      "zenquotes-random-js",
        url:     "https://zenquotes.io/api/random",
        method:  "GET",
        headers: { "Accept": "application/json" },
        timeout: 10
    }));
});
```

---

#### JavaScript — Fetch a new quote on key press

```javascript
// quote_on_demand.js
// Press Q or tap the custom button to fetch a quote at any time.

mp.observe_property("user-data/mpvrx/curl_response", "string", function(name, value) {
    if (!value) return;
    let res;
    try { res = JSON.parse(value); } catch (e) { return; }
    if (res.id !== "quote-demand-js") return;

    if (res.error || res.status !== 200) {
        mp.set_property("user-data/mpvrx/show_text",
            "Could not fetch quote");
        return;
    }

    let data;
    try { data = JSON.parse(res.body); } catch (e) { return; }
    if (data && data[0]) {
        mp.set_property("user-data/mpvrx/show_text",
            data[0].q + "\n\u2014 " + data[0].a);
    }
});

function fetchQuote() {
    // OSD: Show fetching indicator immediately
    mp.set_property("user-data/mpvrx/show_text", "Fetching quote...");
    mp.set_property("user-data/mpvrx/curl_request", JSON.stringify({
        id:     "quote-demand-js",
        url:    "https://zenquotes.io/api/random",
        method: "GET",
        timeout: 10
    }));
}

mp.register_script_message("call_button_quote_on_demand", fetchQuote);
mp.add_key_binding("Q", "fetch-quote-js", fetchQuote);
```

---

### Additional Examples

#### Lua — POST JSON data with auth token

```lua
-- post_example.lua
-- Sends a POST request with JSON body and Bearer token.

local utils = require("mp.utils")

mp.observe_property("user-data/mpvrx/curl_response", "string", function(name, value)
    if value == nil or value == "" then return end
    local res = utils.parse_json(value)
    if res == nil or res.id ~= "post-data" then return end

    if res.error then
        mp.set_property("user-data/mpvrx/show_text",
            "POST failed: " .. res.error)
        return
    end

    -- OSD: Show the response status
    mp.set_property("user-data/mpvrx/show_text",
        "POST returned HTTP " .. res.status)
end)

-- Fire a POST request
mp.set_property("user-data/mpvrx/curl_request", utils.format_json({
    id           = "post-data",
    url          = "https://httpbin.org/post",
    method       = "POST",
    headers      = {
        Authorization = "Bearer your-token-here",
        Accept        = "application/json",
    },
    body         = '{"key": "value"}',
    content_type = "application/json",
    timeout      = 15,
}))
```

---

### Notes & Limits

- **Response body** is capped at **2 MB**. Larger responses are truncated with a `[truncated]` suffix appended to the body.
- **Timeout** is clamped between 1 and 120 seconds.
- **Supported methods:** `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`.
- Redirects (HTTP and HTTPS) are followed automatically.
- The `curl_response` property is **not** cleared between requests — always check `res.id` to match the response to your request.
- Requests run on a background thread and do **not** block video playback.
