-- SPDX-License-Identifier: GPL-2.0-or-later
-- mpv v0.42.0: https://github.com/mpv-player/mpv/blob/v0.42.0/player/lua/stats.lua
-- FongMi additions: https://github.com/FongMi/mpv/blob/f9e2f9a24b49bf4a899017f3a6061e13e94b1898/player/lua/stats.lua
-- mpvRx adds explicit page selection and acknowledged console/overlay coordination.
-- Display some stats.
--
-- Please consult the readme for information about usage and configuration:
-- https://github.com/Argon-/mpv-stats
--
-- Please note: not every property is always available and therefore not always
-- visible.

local mp = require 'mp'
local utils = require 'mp.utils'
local input = require 'mp.input'

-- Options
local o = {
    -- Default key bindings
    key_page_1 = "1",
    key_page_2 = "2",
    key_page_3 = "3",
    key_page_4 = "4",
    key_page_5 = "5",
    key_page_6 = "6",
    key_page_7 = "7",
    key_page_0 = "0",
    -- For pages which support scrolling
    key_scroll_up = "UP",
    key_scroll_down = "DOWN",
    key_search = "/",
    key_exit = "ESC",
    scroll_lines = 1,

    duration = 4,
    redraw_delay = 1,                -- acts as duration in the toggling case
    ass_formatting = true,
    persistent_overlay = false,      -- whether the stats can be overwritten by other output
    filter_params_max_length = 100,  -- show one filter per line if list exceeds this length
    file_tag_max_length = 128,       -- only show file tags shorter than this length in bytes
    file_tag_max_count = 16,         -- only show the first x file tags
    show_frame_info = false,         -- whether to show the current frame info
    term_clip = true,
    track_info_selected_only = true, -- only show selected track info
    debug = false,

    -- Graph options and style
    plot_perfdata = false,
    plot_vsync_ratio = false,
    plot_vsync_jitter = false,
    plot_cache = true,
    plot_tonemapping_lut = false,
    skip_frames = 5,
    global_max = true,
    flush_graph_data = true,         -- clear data buffers when toggling
    plot_bg_border_color = "0000FF",
    plot_bg_color = "262626",
    plot_color = "FFFFFF",
    plot_bg_border_width = 1.25,

    -- Text style
    font = "",
    font_mono = "monospace",   -- monospaced digits are sufficient
    font_size = 20,
    font_color = "",
    border_size = 1.65,
    border_color = "",
    shadow_x_offset = math.huge,
    shadow_y_offset = math.huge,
    shadow_color = "",
    alpha = "11",
    vidscale = "auto",

    -- Custom header for ASS tags to style the text output.
    -- Specifying this will ignore the text style values above and just
    -- use this string instead.
    custom_header = "",

    -- Text formatting
    -- With ASS
    ass_nl = "\\N",
    ass_indent = "\\h\\h\\h\\h\\h",
    ass_prefix_sep = "\\h\\h",
    ass_b1 = "{\\b1}",
    ass_b0 = "{\\b0}",
    ass_it1 = "{\\i1}",
    ass_it0 = "{\\i0}",
    -- Without ASS
    no_ass_nl = "\n",
    no_ass_indent = "    ",
    no_ass_prefix_sep = " ",
    no_ass_b1 = "\027[1m",
    no_ass_b0 = "\027[0m",
    no_ass_it1 = "\027[3m",
    no_ass_it0 = "\027[0m",

    bindlist = "no",  -- print page 4 to the terminal on startup and quit mpv
}

local update_scale
require "mp.options".read_options(o, "stats", function ()
    update_scale()
end)

local format = string.format
local max = math.max
local min = math.min

-- Scaled metrics
local font_size = o.font_size
local border_size = o.border_size
local shadow_x_offset = o.shadow_x_offset
local shadow_y_offset = o.shadow_y_offset
local plot_bg_border_width = o.plot_bg_border_width
-- Function used to record performance data
local recorder = nil
-- Timer used for redrawing (toggling) and clearing the screen (oneshot)
local display_timer = nil
local stats_visible = false
local before_page_render = function() return true end
local statistics_hidden = function() end
local statistics_search_active = false
local android_stats = {}
local gesture_guide_data = {}
local gesture_guide_overlay = mp.create_osd_overlay("ass-events")
gesture_guide_overlay.z = 5
local gesture_guide_visible = false
-- Selection IDs 7/8 already belong to Console/performance in saved preferences.
local gesture_guide_selection = 9
-- Timer used to update cache stats.
local cache_recorder_timer
-- Current page and <page key>:<page function> mappings
local curr_page = o.key_page_1
local pages = {}
local scroll_bound = false
local searched_text
local tm_viz_prev = nil
-- Save these sequences locally as we'll need them a lot
local ass_start = mp.get_property_osd("osd-ass-cc/0")
local ass_stop = mp.get_property_osd("osd-ass-cc/1")
-- Ring buffers for the values used to construct a graph.
-- .pos denotes the current position, .len the buffer length
-- .max is the max value in the corresponding buffer
local vsratio_buf, vsjitter_buf
local function init_buffers()
    vsratio_buf = {0, pos = 1, len = 50, max = 0}
    vsjitter_buf = {0, pos = 1, len = 50, max = 0}
end
local cache_ahead_buf, cache_speed_buf
local perf_buffers = {}
local process_key_binding

local property_cache = {}

local function get_property_cached(name, def)
    if property_cache[name] ~= nil then
        return property_cache[name]
    end
    return def
end

local function graph_add_value(graph, value)
    graph.pos = (graph.pos % graph.len) + 1
    graph[graph.pos] = value
    graph.max = max(graph.max, value)
end

local function no_ASS(t)
    if not o.use_ass then
        return t
    elseif not o.persistent_overlay then
        -- mp.osd_message supports ass-escape using osd-ass-cc/{0|1}
        return ass_stop .. t .. ass_start
    else
        return mp.command_native({"escape-ass", tostring(t)})
    end
end


local function bold(t)
    return o.b1 .. t .. o.b0
end


local function it(t)
    return o.it1 .. t .. o.it0
end


local function text_style()
    if not o.use_ass then
        return ""
    end
    if o.custom_header and o.custom_header ~= "" then
        return o.custom_header
    else
        local style = "{\\r\\an7\\fs" .. font_size .. "\\bord" .. border_size

        if o.font ~= "" then
            style = style .. "\\fn" .. o.font
        end

        if o.font_color ~= "" then
            style = style .. "\\1c&H" .. o.font_color .. "&\\1a&H" .. o.alpha .. "&"
        end

        if o.border_color ~= "" then
            style = style .. "\\3c&H" .. o.border_color .. "&\\3a&H" .. o.alpha .. "&"
        end

        if o.shadow_color ~= "" then
            style = style .. "\\4c&H" .. o.shadow_color .. "&\\4a&H" .. o.alpha .. "&"
        end

        if o.shadow_x_offset < math.huge then
            style = style .. "\\xshad" .. shadow_x_offset
        end

        if o.shadow_y_offset < math.huge then
            style = style .. "\\yshad" .. shadow_y_offset
        end

        return style .. "}"
    end
end


local function has_vo_window()
    -- Keep ASS formatting while the VO is being switched.
    return mp.get_property("current-vo") and mp.get_property_native("video-osd")
end


-- Generate a graph from the given values.
-- Returns an ASS formatted vector drawing as string.
--
-- values: Array/table of numbers representing the data. Used like a ring buffer
--         it will get iterated backwards `len` times starting at position `i`.
-- i     : Index of the latest data value in `values`.
-- len   : The length/amount of numbers in `values`.
-- v_max : The maximum number in `values`. It is used to scale all data
--         values to a range of 0 to `v_max`.
-- v_avg : The average number in `values`. It is used to try and center graphs
--         if possible. May be left as nil
-- scale : A value that will be multiplied with all data values.
-- x_tics: Horizontal width multiplier for the steps
local function generate_graph(values, i, len, v_max, v_avg, scale, x_tics)
    -- Check if at least one value exists
    if not values[i] then
        return ""
    end

    local x_max = (len - 1) * x_tics
    local y_offset = border_size
    local y_max = font_size * 0.66
    local x = 0

    if v_max > 0 then
        -- try and center the graph if possible, but avoid going above `scale`
        if v_avg and v_avg > 0 then
            scale = min(scale, v_max / (2 * v_avg))
        end
        scale = scale * y_max / v_max
    end  -- else if v_max==0 then all values are 0 and scale doesn't matter

    local s = {format("m 0 0 n %f %f l ", x, y_max - scale * values[i])}
    i = ((i - 2) % len) + 1

    for _ = 1, len - 1 do
        if values[i] then
            x = x - x_tics
            s[#s+1] = format("%f %f ", x, y_max - scale * values[i])
        end
        i = ((i - 2) % len) + 1
    end

    s[#s+1] = format("%f %f %f %f", x, y_max, 0, y_max)

    local bg_box = format("{\\bord%f}{\\3c&H%s&}{\\1c&H%s&}m 0 %f l %f %f %f 0 0 0",
                          plot_bg_border_width, o.plot_bg_border_color, o.plot_bg_color,
                          y_max, x_max, y_max, x_max)
    return format("%s{\\rDefault}{\\pbo%f}{\\shad0}{\\alpha&H00}{\\p1}%s{\\p0}" ..
                  "{\\bord0}{\\1c&H%s}{\\p1}%s{\\p0}%s",
                  o.prefix_sep, y_offset, bg_box, o.plot_color, table.concat(s), text_style())
end


local function append(s, str, attr)
    if not str then
        return false
    end
    attr.prefix_sep = attr.prefix_sep or o.prefix_sep
    attr.indent = attr.indent or o.indent
    attr.nl = attr.nl or o.nl
    attr.suffix = attr.suffix or ""
    attr.prefix = attr.prefix or ""
    attr.no_prefix_markup = attr.no_prefix_markup or false
    attr.prefix = attr.no_prefix_markup and attr.prefix or bold(attr.prefix)

    local index = #s + (attr.nl == "" and 0 or 1)
    s[index] = s[index] or ""
    s[index] = s[index] .. format("%s%s%s%s%s%s", attr.nl, attr.indent,
                     attr.prefix, attr.prefix_sep, no_ASS(str), attr.suffix)
    return true
end


-- Format and append a property.
-- A property whose value is either `nil` or empty (hereafter called "invalid")
-- is skipped and not appended.
-- Returns `false` in case nothing was appended, otherwise `true`.
--
-- s      : Table containing strings.
-- prop   : The property to query and format (based on its OSD representation).
-- attr   : Optional table to overwrite certain (formatting) attributes for
--          this property.
-- exclude: Optional table containing keys which are considered invalid values
--          for this property. Specifying this will replace empty string as
--          default invalid value (nil is always invalid).
-- cached : If true, use get_property_cached instead of get_property_osd
local function append_property(s, prop, attr, excluded, cached)
    excluded = excluded or {[""] = true}
    local ret
    if cached then
        ret = get_property_cached(prop)
    else
        ret = mp.get_property_osd(prop)
    end
    if not ret or excluded[ret] then
        if o.debug then
            print("No value for property: " .. prop)
        end
        return false
    end
    return append(s, ret, attr)
end

local function sorted_keys(t, comp_fn)
    local keys = {}
    for k,_ in pairs(t) do
        keys[#keys+1] = k
    end
    table.sort(keys, comp_fn)
    return keys
end

local function scroll_hint(search)
    local hint = format("(hint: swipe or scroll with %s/%s", o.key_scroll_up, o.key_scroll_down)
    if search then
        hint = hint .. " and search with " .. o.key_search
    end
    hint = hint .. ")"
    if not o.use_ass then return " " .. hint end
    return format(" {\\fs%s}%s{\\fs%s}", font_size * 0.66, hint, font_size)
end

local function append_perfdata(header, s, dedicated_page)
    local vo_p = mp.get_property_native("vo-passes")
    if not vo_p then
        return
    end

    -- Sums of all last/avg/peak values
    local last_s, avg_s, peak_s = {}, {}, {}
    for frame, data in pairs(vo_p) do
        last_s[frame], avg_s[frame], peak_s[frame] = 0, 0, 0
        for _, pass in ipairs(data) do
            last_s[frame] = last_s[frame] + pass["last"]
            avg_s[frame]  = avg_s[frame]  + pass["avg"]
            peak_s[frame] = peak_s[frame] + pass["peak"]
        end
    end

    -- Pretty print measured time
    local function pp(i)
        -- rescale to microseconds for a saner display
        return format("%5d", i / 1000)
    end

    -- Format n/m with a font weight based on the ratio
    local function p(n, m)
        local i = 0
        if m > 0 then
            i = tonumber(n) / m
        end
        -- Calculate font weight. 100 is minimum, 400 is normal, 700 bold, 900 is max
        local w = (700 * math.sqrt(i)) + 200
        if not o.use_ass then
            local str = format("%3d%%", i * 100)
            return w >= 700 and bold(str) or str
        end
        return format("{\\b%d}%3d%%{\\b0}", w, i * 100)
    end

    local font_small = o.use_ass and format("{\\fs%s}", font_size * 0.66) or ""
    local font_normal = o.use_ass and format("{\\fs%s}", font_size) or ""
    local font = o.use_ass and format("{\\fn%s}", o.font) or ""
    local font_mono = o.use_ass and format("{\\fn%s}", o.font_mono) or ""
    local indent = o.use_ass and "\\h" or " "

    -- ensure that the fixed title is one element and every scrollable line is
    -- also one single element.
    local h = dedicated_page and header or s
    h[#h+1] = format("%s%s%s%s%s%s%s%s",
                     dedicated_page and "" or o.nl, dedicated_page and "" or o.indent,
                     bold("Frame Timings:"), o.prefix_sep, font_small,
                     "(last/average/peak μs)", font_normal,
                     dedicated_page and scroll_hint() or "")

    for _,frame in ipairs(sorted_keys(vo_p)) do  -- ensure fixed display order
        local data = vo_p[frame]
        local f = "%s%s%s%s%s / %s / %s %s%s%s%s%s%s"

        if dedicated_page then
            s[#s+1] = format("%s%s%s:", o.nl, o.indent,
                             bold(frame:gsub("^%l", string.upper)))

            for _, pass in ipairs(data) do
                s[#s+1] = format(f, o.nl, o.indent, o.indent,
                                 font_mono, pp(pass["last"]),
                                 pp(pass["avg"]), pp(pass["peak"]),
                                 o.prefix_sep .. indent, p(pass["last"], last_s[frame]),
                                 font, o.prefix_sep, o.prefix_sep, pass["desc"])

                if o.plot_perfdata and o.use_ass then
                    -- use the same line that was already started for this iteration
                    s[#s] = s[#s] ..
                              generate_graph(pass["samples"], pass["count"],
                                             pass["count"], pass["peak"],
                                             pass["avg"], 0.9, 0.25)
                end
            end

            -- Print sum of timing values as "Total"
            s[#s+1] = format(f, o.nl, o.indent, o.indent,
                             font_mono, pp(last_s[frame]),
                             pp(avg_s[frame]), pp(peak_s[frame]),
                             o.prefix_sep, bold("Total"), font, "", "", "")
        else
            -- for the simplified view, we just print the sum of each pass
            s[#s+1] = format(f, o.nl, o.indent, o.indent, font_mono,
                            pp(last_s[frame]), pp(avg_s[frame]), pp(peak_s[frame]),
                            "", "", font, o.prefix_sep, o.prefix_sep,
                            frame:gsub("^%l", string.upper))
        end
    end
end

-- command prefix tokens to strip - includes generic property commands
local cmd_prefixes = {
    osd_auto=1, no_osd=1, osd_bar=1, osd_msg=1, osd_msg_bar=1, raw=1, sync=1,
    async=1, expand_properties=1, repeatable=1, nonrepeatable=1, nonscalable=1,
    set=1, add=1, multiply=1, toggle=1, cycle=1, cycle_values=1, ["!reverse"]=1,
    change_list=1,
}
-- commands/writable-properties prefix sub-words (followed by -) to strip
local name_prefixes = {
    define=1, delete=1, enable=1, disable=1, dump=1, write=1, drop=1, revert=1,
    ab=1, hr=1, secondary=1, current=1,
}
-- extract a command "subject" from a command string, by removing all
-- generic prefix tokens and then returning the first interesting sub-word
-- of the next token. For target-script name we also check another token.
-- The tokenizer works fine for things we care about - valid mpv commands,
-- properties and script names, possibly quoted, white-space[s]-separated.
-- It's decent in practice, and worst case is "incorrect" subject.
local function cmd_subject(cmd)
    cmd = cmd:gsub(";.*", ""):gsub("%-", "_")  -- only first cmd, s/-/_/
    local TOKEN = '^%s*["\']?([%w_!]*)'  -- captures+ends before (maybe) final "
    local tok, sname, subw

    repeat tok, cmd = cmd:match(TOKEN .. '["\']?(.*)')
    until not cmd_prefixes[tok]
    -- tok is the 1st non-generic command/property name token, cmd is the rest

    sname = tok == "script_message_to" and cmd:match(TOKEN)
         or tok == "script_binding" and cmd:match(TOKEN .. "/")
    if sname and sname ~= "" then
        return "script: " .. sname
    end

    -- return the first sub-word of tok which is not a useless prefix
    repeat subw, tok = tok:match("([^_]*)_?(.*)")
    until tok == "" or not name_prefixes[subw]
    return subw:len() > 1 and subw or "[unknown]"
end

-- key names are valid UTF-8, ascii7 except maybe the last/only codepoint.
-- we count codepoints and ignore wcwidth. no need for grapheme clusters.
-- our error for alignment is at most one cell (if last CP is double-width).
-- (if k was valid but arbitrary: we'd count all bytes <0x80 or >=0xc0)
local function keyname_cells(k)
    local klen = k:len()
    if klen > 1 and k:byte(klen) >= 0x80 then  -- last/only CP is not ascii7
        repeat klen = klen-1
        until klen == 1 or k:byte(klen) >= 0xc0  -- last CP begins at klen
    end
    return klen
end

local function get_kbinfo_lines()
    -- active keys: only highest priority of each key, and not our (stats) keys
    local bindings = mp.get_property_native("input-bindings", {})
    local active = {}  -- map: key-name -> bind-info
    for _, bind in pairs(bindings) do
        if bind.priority >= 0 and (
               not active[bind.key] or
               (active[bind.key].is_weak and not bind.is_weak) or
               (bind.is_weak == active[bind.key].is_weak and
                bind.priority > active[bind.key].priority)
           ) and not bind.cmd:find("script-binding stats/__forced_", 1, true)
           and bind.section ~= "input_forced_console"
        then
            active[bind.key] = bind
        end
    end

    -- make an array, find max key len, add sort keys (.subject/.mods[_count])
    local ordered = {}
    local kspaces = ""  -- as many spaces as the longest key name
    for _, bind in pairs(active) do
        bind.subject = cmd_subject(bind.cmd)
        if bind.subject ~= "ignore" and (
                searched_text == nil or
                (bind.key .. bind.cmd .. (bind.comment or "")):lower():find(searched_text, 1, true)
            )
        then
            ordered[#ordered+1] = bind
            _,_, bind.mods = bind.key:find("(.*)%+.")
            _, bind.mods_count = bind.key:gsub("%+.", "")
            if bind.key:len() > kspaces:len() then
                kspaces = string.rep(" ", bind.key:len())
            end
        end
    end

    local function align_right(key)
        return kspaces:sub(keyname_cells(key)) .. key
    end

    -- sort by: subject, mod(ifier)s count, mods, key-len, lowercase-key, key
    table.sort(ordered, function(a, b)
        if a.subject ~= b.subject then
            return a.subject < b.subject
        elseif a.mods_count ~= b.mods_count then
            return a.mods_count < b.mods_count
        elseif a.mods ~= b.mods then
            return a.mods < b.mods
        elseif a.key:len() ~= b.key:len() then
            return a.key:len() < b.key:len()
        elseif a.key:lower() ~= b.key:lower() then
            return a.key:lower() < b.key:lower()
        else
            return a.key > b.key  -- only case differs, lowercase first
        end
    end)

    -- key/subject pre/post formatting for terminal/ass.
    -- key/subject alignment uses spaces (with mono font if ass)
    -- word-wrapping is disabled for ass, or cut at 79 for the terminal
    local LTR = string.char(0xE2, 0x80, 0x8E)  -- U+200E Left To Right mark
    local term = not o.use_ass
    local kpre = term and "" or format("{\\q2\\fn%s}%s", o.font_mono, LTR)
    local kpost = term and " " or format(" {\\fn%s}", o.font)
    local spre = term and kspaces .. "   "
                       or format("{\\q2\\fn%s}%s   {\\fn%s}{\\fs%d\\u1}",
                                 o.font_mono, kspaces, o.font, 1.3*font_size)
    local spost = term and "" or format("{\\u0\\fs%d}%s", font_size, text_style())

    -- create the display lines
    local info_lines = {}
    local subject = nil
    for _, bind in ipairs(ordered) do
        if bind.subject ~= subject then  -- new subject (title)
            subject = bind.subject
            append(info_lines, "", {})
            append(info_lines, "", { prefix = spre .. subject .. spost })
        end
        if bind.comment then
            bind.cmd = bind.cmd .. "  # " .. bind.comment
        end
        append(info_lines, bind.cmd, { prefix = kpre .. no_ASS(align_right(bind.key)) .. kpost })
    end
    return info_lines
end

local function append_general_perfdata(s)
    for i, data in ipairs(mp.get_property_native("perf-info") or {}) do
        append(s, data.text or data.value, {prefix="["..tostring(i).."] "..data.name..":"})

        if o.plot_perfdata and o.use_ass and data.value then
            local buf = perf_buffers[data.name]
            if not buf then
                buf = {0, pos = 1, len = 50, max = 0}
                perf_buffers[data.name] = buf
            end
            graph_add_value(buf, data.value)
            s[#s] = s[#s] .. generate_graph(buf, buf.pos, buf.len, buf.max, nil, 0.8, 1)
        end
    end
end

local function append_display_sync(s)
    if not mp.get_property_bool("display-sync-active", false) then
        return
    end

    local vspeed = append_property(s, "video-speed-correction", {prefix="DS:"})
    if vspeed then
        append_property(s, "audio-speed-correction",
                        {prefix="/", nl="", indent=" ", prefix_sep=" ", no_prefix_markup=true})
    else
        append_property(s, "audio-speed-correction",
                        {prefix="DS:" .. o.prefix_sep .. " - / ", prefix_sep=""})
    end

    append_property(s, "mistimed-frame-count", {prefix="Mistimed:", nl="",
                                                indent=o.prefix_sep .. o.prefix_sep})
    append_property(s, "vo-delayed-frame-count", {prefix="Delayed:", nl="",
                                                  indent=o.prefix_sep .. o.prefix_sep})

    -- As we need to plot some graphs we print jitter and ratio on their own lines
    if not display_timer.oneshot and (o.plot_vsync_ratio or o.plot_vsync_jitter) and o.use_ass then
        local ratio_graph = ""
        local jitter_graph = ""
        if o.plot_vsync_ratio then
            ratio_graph = generate_graph(vsratio_buf, vsratio_buf.pos,
                                         vsratio_buf.len, vsratio_buf.max, nil, 0.8, 1)
        end
        if o.plot_vsync_jitter then
            jitter_graph = generate_graph(vsjitter_buf, vsjitter_buf.pos,
                                          vsjitter_buf.len, vsjitter_buf.max, nil, 0.8, 1)
        end
        append_property(s, "vsync-ratio", {prefix="VSync Ratio:",
                                           suffix=o.prefix_sep .. ratio_graph})
        append_property(s, "vsync-jitter", {prefix="VSync Jitter:",
                                            suffix=o.prefix_sep .. jitter_graph})
    else
        -- Since no graph is needed we can print ratio/jitter on the same line and save some space
        local vr = append_property(s, "vsync-ratio", {prefix="VSync Ratio:"})
        append_property(s, "vsync-jitter", {prefix="VSync Jitter:",
                            nl=vr and "" or o.nl,
                            indent=vr and o.prefix_sep .. o.prefix_sep})
    end
end


local function append_filters(s, prop, prefix)
    local length = 0
    local filters = {}

    for _,f in ipairs(mp.get_property_native(prop, {})) do
        local n = f.name
        if f.enabled ~= nil and not f.enabled then
            n = n .. " (disabled)"
        end

        if f.label ~= nil then
            n = "@" .. f.label .. ": " .. n
        end

        local p = {}
        for _,key in ipairs(sorted_keys(f.params)) do
            p[#p+1] = key .. "=" .. f.params[key]
        end
        if #p > 0 then
            p = " [" .. table.concat(p, " ") .. "]"
        else
            p = ""
        end

        length = length + n:len() + p:len()
        filters[#filters+1] = no_ASS(n) .. it(no_ASS(p))
    end

    if #filters > 0 then
        local ret
        if length < o.filter_params_max_length then
            ret = table.concat(filters, ", ")
        else
            local sep = o.nl .. o.indent .. o.indent
            ret = sep .. table.concat(filters, sep)
        end
        s[#s+1] = o.nl .. o.indent .. bold(prefix) .. o.prefix_sep .. ret
    end
end


local function add_header(s)
    s[#s+1] = text_style()
end


local function add_file(s, print_cache, print_tags)
    append(s, "", {prefix="File:", nl="", indent=""})
    append_property(s, "filename", {prefix_sep="", nl="", indent=""})
    if mp.get_property_osd("filename") ~= mp.get_property_osd("media-title") then
        append_property(s, "media-title", {prefix="Title:"})
    end

    if print_tags then
        append_property(s, "duration", {prefix="Duration:"})
        local tags = mp.get_property_native("display-tags")
        local tags_displayed = 0
        for _, tag in ipairs(tags) do
            local value = mp.get_property("metadata/by-key/" .. tag)
            if tag ~= "Title" and tags_displayed < o.file_tag_max_count
               and value and value:len() < o.file_tag_max_length then
                append(s, value, {prefix=string.gsub(tag, "_", " ") .. ":"})
                tags_displayed = tags_displayed + 1
            end
        end
    end

    local editions = mp.get_property_number("editions")
    local edition = mp.get_property_number("current-edition")
    local ed_cond = (edition and editions > 1)
    if ed_cond then
        append_property(s, "edition-list/" .. tostring(edition) .. "/title",
                       {prefix="Edition:"})
        append_property(s, "edition-list/count",
                        {prefix="(" .. tostring(edition + 1) .. "/", suffix=")", nl="",
                         indent=" ", prefix_sep=" ", no_prefix_markup=true})
    end

    local ch_index = mp.get_property_number("chapter")
    if ch_index and ch_index >= 0 then
        append_property(s, "chapter-list/" .. tostring(ch_index) .. "/title", {prefix="Chapter:",
                        nl=ed_cond and "" or o.nl})
        append_property(s, "chapter-list/count",
                        {prefix="(" .. tostring(ch_index + 1) .. " /", suffix=")", nl="",
                         indent=" ", prefix_sep=" ", no_prefix_markup=true})
    end

    local fs = append_property(s, "file-size", {prefix="Size:"})
    append_property(s, "file-format", {prefix="Format/Protocol:",
                                       nl=fs and "" or o.nl,
                                       indent=fs and o.prefix_sep .. o.prefix_sep})

    if not print_cache then
        return
    end

    local demuxer_cache = mp.get_property_native("demuxer-cache-state", {})
    if demuxer_cache["fw-bytes"] then
        demuxer_cache = demuxer_cache["fw-bytes"] -- returns bytes
    else
        demuxer_cache = 0
    end
    local demuxer_secs = mp.get_property_number("demuxer-cache-duration", 0)
    if demuxer_cache + demuxer_secs > 0 then
        append(s, utils.format_bytes_humanized(demuxer_cache), {prefix="Total Cache:"})
        append(s, format("%.1f", demuxer_secs), {prefix="(", suffix=" sec)", nl="",
               no_prefix_markup=true, prefix_sep="", indent=o.prefix_sep})
    end
end


local function crop_noop(w, h, r)
    return r["crop-x"] == 0 and r["crop-y"] == 0 and
           r["crop-w"] == w and r["crop-h"] == h
end


local function crop_equal(r, ro)
    return r["crop-x"] == ro["crop-x"] and r["crop-y"] == ro["crop-y"] and
           r["crop-w"] == ro["crop-w"] and r["crop-h"] == ro["crop-h"]
end


local function append_resolution(s, r, prefix, w_prop, h_prop, video_res)
    if not r then
        return
    end
    w_prop = w_prop or "w"
    h_prop = h_prop or "h"
    if append(s, r[w_prop], {prefix=prefix}) then
        append(s, r[h_prop], {prefix="x", nl="", indent=" ", prefix_sep=" ",
                           no_prefix_markup=true})
        if r["aspect"] ~= nil and not video_res then
            append(s, format("%.2f:1", r["aspect"]), {prefix="", nl="", indent="",
                                                      no_prefix_markup=true})
            append(s, r["aspect-name"], {prefix="(", suffix=")", nl="", indent=" ",
                                         prefix_sep="", no_prefix_markup=true})
        end
        if r["sar"] ~= nil and video_res then
            append(s, format("%.2f:1", r["sar"]), {prefix="", nl="", indent="",
                                                      no_prefix_markup=true})
            append(s, r["sar-name"], {prefix="(", suffix=")", nl="", indent=" ",
                                         prefix_sep="", no_prefix_markup=true})
        end
        if r["s"] then
            append(s, format("%.2f", r["s"]), {prefix="(", suffix="x)", nl="",
                                               indent=o.prefix_sep, prefix_sep="",
                                               no_prefix_markup=true})
        end
        -- We can skip crop if it is the same as video decoded resolution
        if r["crop-w"] and (not video_res or
                            not crop_noop(r[w_prop], r[h_prop], r)) then
            append(s, format("[x: %d, y: %d, w: %d, h: %d]",
                            r["crop-x"], r["crop-y"], r["crop-w"], r["crop-h"]),
                            {prefix="", nl="", indent="", no_prefix_markup=true})
        end
    end
end


local function pq_eotf(x)
    if not x then
        return x;
    end

    local PQ_M1 = 2610.0 / 4096 * 1.0 / 4
    local PQ_M2 = 2523.0 / 4096 * 128
    local PQ_C1 = 3424.0 / 4096
    local PQ_C2 = 2413.0 / 4096 * 32
    local PQ_C3 = 2392.0 / 4096 * 32

    x = x ^ (1.0 / PQ_M2)
    x = max(x - PQ_C1, 0.0) / (PQ_C2 - PQ_C3 * x)
    x = x ^ (1.0 / PQ_M1)
    x = x * 10000.0

    return x
end


local function append_hdr(s, hdr, video_out)
    if not hdr then
        return
    end

    local function has(val, target)
        return val and math.abs(val - target) > 1e-4
    end

    -- If we are printing video out parameters it is just display, not mastering
    local display_prefix = video_out and "Display:" or "Mastering display:"

    local indent = ""
    local has_dml = has(hdr["min-luma"], 0.203) or has(hdr["max-luma"], 203)
    local has_cll = hdr["max-cll"] and hdr["max-cll"] > 0
    local has_fall = hdr["max-fall"] and hdr["max-fall"] > 0

    if has_dml or has_cll or has_fall then
        append(s, "", {prefix=video_out and "" or "HDR10:", prefix_sep=video_out and "" or nil})
        if has_dml then
            -- libplacebo uses close to zero values as "defined zero"
            hdr["min-luma"] = hdr["min-luma"] <= 1e-6 and 0 or hdr["min-luma"]
            append(s, format("%.2g / %.0f", hdr["min-luma"], hdr["max-luma"]),
                {prefix=display_prefix, suffix=" cd/m²", nl="", indent=indent})
            indent = o.prefix_sep .. o.prefix_sep
        end
        if has_cll then
            append(s, string.format("%.0f", hdr["max-cll"]), {prefix="MaxCLL:",
                                    suffix=" cd/m²", nl="", indent=indent})
            indent = o.prefix_sep .. o.prefix_sep
        end
        if has_fall then
            append(s, hdr["max-fall"], {prefix="MaxFALL:", suffix=" cd/m²", nl="",
                                        indent=indent})
        end
    end

    indent = o.prefix_sep .. o.prefix_sep

    if hdr["scene-max-r"] or hdr["scene-max-g"] or
       hdr["scene-max-b"] or hdr["scene-avg"] then
        append(s, "", {prefix="HDR10+:"})
        append(s, format("%.1f / %.1f / %.1f", hdr["scene-max-r"] or 0,
                         hdr["scene-max-g"] or 0, hdr["scene-max-b"] or 0),
               {prefix="MaxRGB:", suffix=" cd/m²", nl="", indent=""})
        append(s, format("%.1f", hdr["scene-avg"] or 0),
               {prefix="Avg:", suffix=" cd/m²", nl="", indent=indent})
    end

    if hdr["max-pq-y"] and hdr["avg-pq-y"] then
        append(s, "", {prefix="PQ(Y):"})
        append(s, format("%.2f cd/m² (%.2f%% PQ)", pq_eotf(hdr["max-pq-y"]),
                         hdr["max-pq-y"] * 100), {prefix="Max:", nl="",
                         indent=""})
        append(s, format("%.2f cd/m² (%.2f%% PQ)", pq_eotf(hdr["avg-pq-y"]),
                         hdr["avg-pq-y"] * 100), {prefix="Avg:", nl="",
                         indent=indent})
    end
end


local function append_img_params(s, r, ro)
    if not r then
        return
    end

    append_resolution(s, r, "Resolution:", "w", "h", true)
    if ro and (r["w"] ~= ro["dw"] or r["h"] ~= ro["dh"]) then
        if ro["crop-w"] and (crop_noop(r["w"], r["h"], ro) or crop_equal(r, ro)) then
            ro["crop-w"] = nil
        end
        append_resolution(s, ro, "Output Resolution:", "dw", "dh")
    end

    local indent = o.prefix_sep .. o.prefix_sep
    r = ro or r

    local pixel_format = r["hw-pixelformat"] or r["pixelformat"]
    append(s, pixel_format, {prefix="Format:"})
    append(s, r["colorlevels"], {prefix="Levels:", nl="", indent=indent})
    if r["chroma-location"] and r["chroma-location"] ~= "unknown" then
        append(s, r["chroma-location"], {prefix="Chroma Loc:", nl="", indent=indent})
    end

    -- Group these together to save vertical space
    append(s, r["colormatrix"], {prefix="Colormatrix:"})
    if r["prim-red-x"] or r["prim-red-y"] or
       r["prim-green-x"] or r["prim-green-y"] or
       r["prim-blue-x"] or r["prim-blue-y"] or
       r["prim-white-x"] or r["prim-white-y"] then
        append(s, string.format("[%.3f %.3f, %.3f %.3f, %.3f %.3f, %.3f %.3f]",
                                r["prim-red-x"] or 0, r["prim-red-y"] or 0,
                                r["prim-green-x"] or 0, r["prim-green-y"] or 0,
                                r["prim-blue-x"] or 0, r["prim-blue-y"] or 0,
                                r["prim-white-x"] or 0, r["prim-white-y"] or 0),
            {prefix="Primaries:", nl="", indent=indent})
        append(s, r["primaries"], {prefix="in", nl="", indent=" ", prefix_sep=" ",
                                   no_prefix_markup=true})
    else
        append(s, r["primaries"], {prefix="Primaries:", nl="", indent=indent})
    end
    append(s, r["gamma"], {prefix="Transfer:", nl="", indent=indent})
end


local function append_fps(s, prop, eprop)
    local fps = mp.get_property_osd(prop)
    local efps = mp.get_property_osd(eprop)
    local single = eprop == "" or (fps ~= "" and efps ~= "" and fps == efps)
    local unit = prop == "display-fps" and " Hz" or " fps"
    local suffix = single and "" or " (specified)"
    local esuffix = single and "" or " (estimated)"
    local prefix = prop == "display-fps" and "Refresh Rate:" or "Frame Rate:"
    local nl = o.nl
    local indent = o.indent

    if fps ~= "" and append(s, fps, {prefix=prefix, suffix=unit .. suffix}) then
        prefix = ""
        nl = ""
        indent = ""
    end

    if not single and efps ~= "" then
        append(s, efps,
               {prefix=prefix, suffix=unit .. esuffix, nl=nl, indent=indent})
    end
end


local function add_video_out(s)
    local vo = mp.get_property_native("current-vo")
    if not vo then
        return
    end

    append(s, "", {prefix="Display:", nl=o.nl .. o.nl, indent=""})
    append(s, vo, {prefix_sep="", nl="", indent=""})

    append_property(s, "display-names", {prefix_sep="", prefix="(", suffix=")",
                    no_prefix_markup=true, nl="", indent=" "}, nil, true)
    append(s, mp.get_property_native("current-gpu-context"),
           {prefix="Context:", nl="", indent=o.prefix_sep .. o.prefix_sep})
    append_property(s, "avsync", {prefix="A-V:"})
    append_fps(s, "display-fps", "estimated-display-fps")
    if append_property(s, "decoder-frame-drop-count",
                       {prefix="Dropped Frames:", suffix=" (decoder)"}) then
        append_property(s, "frame-drop-count", {suffix=" (output)", nl="", indent=""})
    end
    append_display_sync(s)
    append_perfdata(nil, s, false)

    if mp.get_property_native("deinterlace-active") then
        append_property(s, "deinterlace", {prefix="Deinterlacing:"})
    end

    local scale = nil
    if not mp.get_property_native("fullscreen") then
        scale = get_property_cached("current-window-scale")
    end

    local od = mp.get_property_native("osd-dimensions")
    local rt = mp.get_property_native("video-target-params")
    local r = rt or {}

    -- Add window scale
    r["s"] = scale
    r["crop-x"] = od["ml"]
    r["crop-y"] = od["mt"]
    r["crop-w"] = od["w"] - od["ml"] - od["mr"]
    r["crop-h"] = od["h"] - od["mt"] - od["mb"]

    if not rt then
        r["w"] = r["crop-w"]
        r["h"] = r["crop-h"]
        append_resolution(s, r, "Resolution:", "w", "h", true)
        return
    end

    append_img_params(s, r)
    append_hdr(s, r, true)
end


local function add_video(s)
    local r = mp.get_property_native("video-params")
    local ro = mp.get_property_native("video-out-params")
    -- in case of e.g. lavfi-complex there can be no input video, only output
    if not r then
        r = ro
    end
    if not r then
        return
    end

    local track = mp.get_property_native("current-tracks/video")
    local track_type = (track and track.image) and "Image:" or "Video:"
    append(s, "", {prefix=track_type, nl=o.nl .. o.nl, indent=""})
    if track and append(s, track["codec-desc"], {prefix_sep="", nl="", indent=""}) then
        append(s, track["codec-profile"], {prefix="[", nl="", indent=" ", prefix_sep="",
               no_prefix_markup=true, suffix="]"})
        if track["codec"] ~= track["decoder"] then
            append(s, track["decoder"], {prefix="[", nl="", indent=" ", prefix_sep="",
                   no_prefix_markup=true, suffix="]"})
        end
        append_property(s, "hwdec-current", {prefix="HW:", nl="",
                        indent=o.prefix_sep .. o.prefix_sep,
                        no_prefix_markup=false, suffix=""}, {no=true, [""]=true}, true)
    end
    local has_prefix = false
    if o.show_frame_info then
        if append_property(s, "estimated-frame-number", {prefix="Frame:"}) then
            append_property(s, "estimated-frame-count", {indent=" / ", nl="",
                                                        prefix_sep=""})
            has_prefix = true
        end
        local frame_info = mp.get_property_native("video-frame-info")
        if frame_info and frame_info["picture-type"] then
            local attrs = has_prefix and {prefix="(", suffix=")", indent=" ", nl="",
                                          prefix_sep="", no_prefix_markup=true}
                                      or {prefix="Picture Type:"}
            append(s, frame_info["picture-type"], attrs)
            has_prefix = true
        end
        if frame_info and frame_info["interlaced"] then
            local attrs = has_prefix and {indent=" ", nl="", prefix_sep=""}
                                      or {prefix="Picture Type:"}
            append(s, "Interlaced", attrs)
        end

        local timecodes = {
            ["gop-timecode"] = "GOP",
            ["smpte-timecode"] = "SMPTE",
            ["estimated-smpte-timecode"] = "Estimated SMPTE",
        }
        for prop, name in pairs(timecodes) do
            if frame_info and frame_info[prop] then
                local attrs = has_prefix and {prefix=name .. " Timecode:",
                                              indent=o.prefix_sep .. o.prefix_sep, nl=""}
                                          or {prefix=name .. " Timecode:"}
                append(s, frame_info[prop], attrs)
                break
            end
        end
    end

    if mp.get_property_native("current-tracks/video/image") == false then
        append_fps(s, "container-fps", "estimated-vf-fps")
    end
    append_img_params(s, r, ro)
    append_hdr(s, ro)
    append_property(s, "video-bitrate", {prefix="Bitrate:"})
    append_filters(s, "vf", "Filters:")
end


local motion_last

local function add_motion(s)
    local info = mp.get_property_native("motion-interpolation-info")
    if not info then
        motion_last = nil
        return
    end

    append(s, info.state, {prefix="MEMC:"})
    if info["target-fps"] > 0 then
        append(s, format("target %d fps", info["target-fps"]),
               {prefix="(", suffix=")", nl="", indent=" ", prefix_sep="",
                no_prefix_markup=true})
    end
    local last = motion_last
    motion_last = info["target-fps"] > 0 and info or nil
    if last and motion_last and info["epoch-ns"] == last["epoch-ns"] and
       info["timestamp-ns"] > last["timestamp-ns"] and
       info["swapchain-submissions"] >= last["swapchain-submissions"] and
       info["synthesis-submissions"] >= last["synthesis-submissions"] then
        local seconds = (info["timestamp-ns"] - last["timestamp-ns"]) / 1e9
        local submitted = info["swapchain-submissions"] - last["swapchain-submissions"]
        local synthesized = info["synthesis-submissions"] - last["synthesis-submissions"]
        append(s, format("%.1f/s, synthesis %.1f/s", submitted / seconds,
                         synthesized / seconds), {prefix="MEMC submissions:"})
    end
    if info["last-analysis-width"] and info["last-analysis-height"] and
       info["last-synthesis-width"] and info["last-synthesis-height"] then
        append(s, format("%dx%d ➜ %dx%d", info["last-analysis-width"],
                         info["last-analysis-height"], info["last-synthesis-width"],
                         info["last-synthesis-height"]), {prefix="Last motion resolution:"})
    end
end


local function add_audio(s)
    local r = mp.get_property_native("audio-params")
    local output = mp.get_property_native("audio-out-params")
    -- in case of e.g. lavfi-complex there can be no input audio, only output
    local ro = output or r
    r = r or ro
    if not r then
        return
    end

    local merge = function(rr, rro, prop)
        local a = rr[prop] or rro[prop]
        local b = rro[prop] or rr[prop]
        return (a == b or a == nil) and a or (a .. " ➜ " .. b)
    end

    append(s, "", {prefix="Audio:", nl=o.nl .. o.nl, indent=""})
    local track = mp.get_property_native("current-tracks/audio")
    if track then
        append(s, track["codec-desc"], {prefix_sep="", nl="", indent=""})
        append(s, track["codec-profile"], {prefix="[", nl="", indent=" ", prefix_sep="",
               no_prefix_markup=true, suffix="]"})
        if track["codec"] ~= track["decoder"] then
            append(s, track["decoder"], {prefix="[", nl="", indent=" ", prefix_sep="",
                   no_prefix_markup=true, suffix="]"})
        end
    end
    append_property(s, "current-ao", {prefix="AO:", nl="",
                                      indent=o.prefix_sep .. o.prefix_sep})
    if output and output.format then
        local output_type = "PCM"
        if output.format:match("^spdif%-") then
            output_type = "Passthrough (IEC61937)"
        elseif output.format:match("^raw%-") then
            output_type = "Passthrough (raw)"
        elseif output.format == "dop" then
            output_type = "Passthrough (DoP)"
        elseif output.format == "dsd" then
            output_type = "DSD"
        end
        append(s, output_type, {prefix="Output:"})
        if mp.get_property_native("audio-passthrough-failed", false) then
            append(s, "Passthrough output initialization failed", {prefix="Fallback:"})
        end
    end
    local dev = append_property(s, "audio-device", {prefix="Device:"})
    local ao_mute = mp.get_property_native("ao-mute") and " (Muted)" or ""
    append_property(s, "ao-volume", {prefix="AO Volume:", suffix="%" .. ao_mute,
                                     nl=dev and "" or o.nl,
                                     indent=dev and o.prefix_sep .. o.prefix_sep})
    if math.abs(mp.get_property_native("audio-delay")) > 1e-6 then
        append_property(s, "audio-delay", {prefix="A-V delay:"})
    end
    local cc = append(s, merge(r, ro, "channel-count"), {prefix="Channels:"})
    append(s, merge(r, ro, "format"), {prefix="Format:", nl=cc and "" or o.nl,
                            indent=cc and o.prefix_sep .. o.prefix_sep})
    append(s, merge(r, ro, "samplerate"), {prefix="Sample Rate:", suffix=" Hz"})
    append_property(s, "audio-bitrate", {prefix="Bitrate:"})
    append_filters(s, "af", "Filters:")
end


-- Determine whether ASS formatting shall/can be used and set formatting sequences
local function eval_ass_formatting(plain_text)
    o.use_ass = not plain_text and o.ass_formatting and has_vo_window()
    if o.use_ass then
        o.nl = o.ass_nl
        o.indent = o.ass_indent
        o.prefix_sep = o.ass_prefix_sep
        o.b1 = o.ass_b1
        o.b0 = o.ass_b0
        o.it1 = o.ass_it1
        o.it0 = o.ass_it0
    else
        o.nl = o.no_ass_nl
        o.indent = o.no_ass_indent
        o.prefix_sep = o.no_ass_prefix_sep
        o.b1 = o.no_ass_b1
        o.b0 = o.no_ass_b0
        o.it1 = o.no_ass_it1
        o.it0 = o.no_ass_it0
    end
end

-- split str into a table
-- example: local t = split(s, "\n")
-- plain: whether pat is a plain string (default false - pat is a pattern)
local function split(str, pat, plain)
    local init = 1
    local r, i, find, sub = {}, 1, string.find, string.sub
    repeat
        local f0, f1 = find(str, pat, init, plain)
        r[i], i = sub(str, init, f0 and f0 - 1), i+1
        init = f0 and f1 + 1
    until f0 == nil
    return r
end

-- Composes the output with header and scrollable content
-- Returns string of the finished page and the actually chosen offset
--
-- header      : table of the header where each entry is one line
-- content     : table of the content where each entry is one line
-- apply_scroll: scroll the content
local function finalize_page(header, content, apply_scroll, plain_text)
    local term_height = mp.get_property_native("term-size/h", 24)
    local from, to = 1, #content
    if apply_scroll then
        -- Up to 40 lines for libass because it can put a big performance toll on
        -- libass to process many lines which end up outside (below) the screen.
        -- In the terminal reduce height by 2 for the status line (can be more then one line)
        local max_content_lines = (o.use_ass and 40 or term_height - 2) - #header
        -- in the terminal the scrolling should stop once the last line is visible
        local max_offset = o.use_ass and #content or #content - max_content_lines + 1
        from = max(1, min((pages[curr_page].offset or 1), max_offset))
        to = min(#content, from + max_content_lines - 1)
        pages[curr_page].offset = from
    end
    local output = table.concat(header) .. table.concat(content, "", from, to)
    if not o.use_ass and o.term_clip and not plain_text then
        local clip = mp.get_property("term-clip-cc")
        local t = split(output, "\n", true)
        output = clip .. table.concat(t, "\n" .. clip)
    end
    return output, from
end

-- Returns an ASS string with "normal" stats
local function default_stats(_, plain_text)
    local stats = {}
    eval_ass_formatting(plain_text)
    add_header(stats)
    add_file(stats, true, false)
    add_video_out(stats)
    add_video(stats)
    add_motion(stats)
    add_audio(stats)
    return finalize_page({}, stats, false, plain_text)
end

mp.add_key_binding(nil, "export-stats", function()
    local text = default_stats(nil, true):gsub("\027%[[%d;]*m", "")
    eval_ass_formatting()
    mp.set_property_native("user-data/stats", {text = text})
end)

-- Returns an ASS string with extended VO stats
local function vo_stats()
    local header, content = {}, {}
    eval_ass_formatting()
    add_header(header)
    append_perfdata(header, content, true)
    header = {table.concat(header)}
    return finalize_page(header, content, true)
end

local kbinfo_lines = nil
local function keybinding_info(after_scroll, bindlist)
    local header = {}
    local page = pages[o.key_page_4]
    eval_ass_formatting()
    add_header(header)
    local prefix = bindlist and page.desc or page.desc .. ":" .. scroll_hint(true)
    append(header, "", {prefix=prefix, nl="", indent=""})
    header = {table.concat(header)}

    if not kbinfo_lines or not after_scroll then
        kbinfo_lines = get_kbinfo_lines()
    end

    return finalize_page(header, kbinfo_lines, not bindlist)
end

local function float2rational(x)
    local max_den = 100000
    local m00, m01, m10, m11 = 1, 0, 0, 1
    local a = math.floor(x)
    local frac = x - a
    while m10 * a + m11 <= max_den do
        local temp = m00 * a + m01
        m01 = m00
        m00 = temp
        temp = m10 * a + m11
        m11 = m10
        m10 = temp

        if frac == 0 then
            break
        end

        x = 1 / frac
        a = math.floor(x)
        frac = x - a
    end
    return m00, m10
end

local function add_track(c, t, i)
    if not t then
        return
    end

    local type = t.image and "Image" or t["type"]:sub(1, 1):upper() .. t["type"]:sub(2)
    append(c, "", {prefix=type .. ":", nl=o.nl .. o.nl, indent=""})
    append(c, t["title"], {prefix_sep="", nl="", indent=""})
    append(c, t["id"], {prefix="ID:"})
    append(c, t["src-id"], {prefix="Demuxer ID:", nl="", indent=o.prefix_sep .. o.prefix_sep})
    append(c, t["program-id"], {prefix="Program ID:", nl="", indent=o.prefix_sep .. o.prefix_sep})
    append(c, t["ff-index"], {prefix="FFmpeg Index:", nl="", indent=o.prefix_sep .. o.prefix_sep})
    append(c, t["external-filename"], {prefix="File:"})
    append(c, "", {prefix="Flags:"})
    local flags = {"default", "forced", "dependent", "visual-impaired",
                   "hearing-impaired", "original", "commentary", "image",
                   "albumart", "external"}
    local any = false
    for _, flag in ipairs(flags) do
        if t[flag] then
            append(c, flag, {prefix=any and ", " or "", nl="", indent="", prefix_sep=""})
            any = true
        end
    end
    if not any then
        table.remove(c)
    end
    if append(c, t["codec-desc"], {prefix="Codec:"}) then
        append(c, t["codec-profile"], {prefix="[", nl="", indent=" ", prefix_sep="",
               no_prefix_markup=true, suffix="]"})
        if t["codec"] ~= t["decoder"] then
            append(c, t["decoder"], {prefix="[", nl="", indent=" ", prefix_sep="",
                   no_prefix_markup=true, suffix="]"})
        end
    end
    append(c, t["lang"], {prefix="Language:"})
    append(c, t["demux-channel-count"], {prefix="Channels:"})
    append(c, t["demux-channels"], {prefix="Channel Layout:"})
    append(c, t["demux-samplerate"], {prefix="Sample Rate:", suffix=" Hz"})
    local function B(b) return b and string.format("%.2f", b / 1024) end
    local bitrate = append(c, B(t["demux-bitrate"]), {prefix="Bitrate:", suffix=" kbps"})
    append(c, B(t["hls-bitrate"]), {prefix="HLS Bitrate:", suffix=" kbps",
                                    nl=bitrate and "" or o.nl,
                                    indent=bitrate and o.prefix_sep .. o.prefix_sep})
    append_resolution(c, {w=t["demux-w"], h=t["demux-h"], ["crop-x"]=t["demux-crop-x"],
                          ["crop-y"]=t["demux-crop-y"], ["crop-w"]=t["demux-crop-w"],
                          ["crop-h"]=t["demux-crop-h"]}, "Resolution:")
    if not t["image"] and t["demux-fps"] then
        append_fps(c, "track-list/" .. i .. "/demux-fps", "")
    end
    append(c, t["format-name"], {prefix="Format:"})
    append(c, t["demux-rotation"], {prefix="Rotation:"})
    if t["demux-par"] then
        local num, den = float2rational(t["demux-par"])
        append(c, string.format("%d:%d", num, den), {prefix="Pixel Aspect Ratio:"})
    end
    local track_rg = t["replaygain-track-peak"] ~= nil or t["replaygain-track-gain"] ~= nil
    local album_rg = t["replaygain-album-peak"] ~= nil or t["replaygain-album-gain"] ~= nil
    if track_rg or album_rg then
        append(c, "", {prefix="Replay Gain:"})
    end
    if track_rg then
        append(c, "", {prefix="Track:", indent=o.indent .. o.prefix_sep, prefix_sep=""})
        append(c, t["replaygain-track-gain"], {prefix="Gain:", suffix=" dB",
                                               nl="", indent=o.prefix_sep})
        append(c, t["replaygain-track-peak"], {prefix="Peak:", suffix=" dB",
                                               nl="", indent=o.prefix_sep})
    end
    if album_rg then
        append(c, "", {prefix="Album:", indent=o.indent .. o.prefix_sep, prefix_sep=""})
        append(c, t["replaygain-album-gain"], {prefix="Gain:", suffix=" dB",
                                               nl="", indent=o.prefix_sep})
        append(c, t["replaygain-album-peak"], {prefix="Peak:", suffix=" dB",
                                               nl="", indent=o.prefix_sep})
    end
    if t["dolby-vision-profile"] or t["dolby-vision-level"] then
        append(c, "", {prefix="Dolby Vision:"})
        append(c, t["dolby-vision-profile"], {prefix="Profile:", nl="", indent=""})
        append(c, t["dolby-vision-level"], {prefix="Level:", nl="",
                                            indent=t["dolby-vision-profile"] and
                                            o.prefix_sep .. o.prefix_sep or ""})
    end
end

local function track_info()
    local h, c = {}, {}
    eval_ass_formatting()
    add_header(h)
    local desc = pages[o.key_page_5].desc
    append(h, "", {prefix=format("%s:%s", desc, scroll_hint()), nl="", indent=""})
    h = {table.concat(h)}
    table.insert(c, o.nl .. o.nl)
    add_file(c, false, true)
    for i, track in ipairs(mp.get_property_native("track-list")) do
        if track['selected'] or not o.track_info_selected_only then
            add_track(c, track, i - 1)
        end
    end
    return finalize_page(h, c, true)
end

local function perf_stats()
    local header, content = {}, {}
    eval_ass_formatting()
    add_header(header)
    local page = pages[o.key_page_0]
    append(header, "", {prefix=format("%s:%s", page.desc, scroll_hint()), nl="", indent=""})
    append_general_perfdata(content)
    header = {table.concat(header)}
    return finalize_page(header, content, true)
end

local function opt_time(t)
    if type(t) == type(1.1) then
        return mp.format_time(t)
    end
    return "?"
end

-- Returns an ASS string with stats about the demuxer cache etc.
local function cache_stats()
    local stats = {}

    eval_ass_formatting()
    add_header(stats)
    append(stats, "", {prefix="Cache Info:", nl="", indent=""})

    local info = mp.get_property_native("demuxer-cache-state")
    if info == nil then
        append(stats, "Unavailable.", {})
        return finalize_page({}, stats, false)
    end

    local a = info["reader-pts"]
    local b = info["cache-end"]

    append(stats, opt_time(a) .. " - " .. opt_time(b), {prefix = "Packet Queue:"})

    local r = nil
    if a ~= nil and b ~= nil then
        r = b - a
    end

    local r_graph = nil
    if not display_timer.oneshot and o.use_ass and o.plot_cache then
        r_graph = generate_graph(cache_ahead_buf, cache_ahead_buf.pos,
                                 cache_ahead_buf.len, cache_ahead_buf.max,
                                 nil, 0.8, 1)
        r_graph = o.prefix_sep .. r_graph
    end
    append(stats, opt_time(r), {prefix = "Readahead:", suffix = r_graph})

    -- These states are not necessarily exclusive. They're about potentially
    -- separate mechanisms, whose states may be decoupled.
    local state = "reading"
    local seek_ts = info["debug-seeking"]
    if seek_ts ~= nil then
        state = "seeking (to " .. mp.format_time(seek_ts) .. ")"
    elseif info["eof"] == true then
        state = "eof"
    elseif info["underrun"] then
        state = "underrun"
    elseif info["idle"]  == true then
        state = "inactive"
    end
    append(stats, state, {prefix = "State:"})

    local speed = info["raw-input-rate"] or 0
    local speed_graph = nil
    if not display_timer.oneshot and o.use_ass and o.plot_cache then
        speed_graph = generate_graph(cache_speed_buf, cache_speed_buf.pos,
                                     cache_speed_buf.len, cache_speed_buf.max,
                                     nil, 0.8, 1)
        speed_graph = o.prefix_sep .. speed_graph
    end
    append(stats, utils.format_bytes_humanized(speed) .. "/s", {prefix="Speed:",
        suffix=speed_graph})

    append(stats, utils.format_bytes_humanized(info["total-bytes"]),
           {prefix = "Total RAM:"})
    append(stats, utils.format_bytes_humanized(info["fw-bytes"]),
           {prefix = "Forward RAM:"})

    local fc = info["file-cache-bytes"]
    if fc ~= nil then
        fc = utils.format_bytes_humanized(fc)
    else
        fc = "(disabled)"
    end
    append(stats, fc, {prefix = "Disk Cache:"})

    append(stats, info["debug-low-level-seeks"], {prefix = "Media Seeks:"})
    append(stats, info["debug-byte-level-seeks"], {prefix = "Stream Seeks:"})

    append(stats, "", {prefix="Ranges:", nl=o.nl .. o.nl, indent=""})

    append(stats, info["bof-cached"] and "yes" or "no",
           {prefix = "Start Cached:"})
    append(stats, info["eof-cached"] and "yes" or "no",
           {prefix = "End Cached:"})

    local ranges = info["seekable-ranges"] or {}
    for n, range in ipairs(ranges) do
        append(stats, mp.format_time(range["start"]) .. " - " ..
                      mp.format_time(range["end"]),
               {prefix = format("Range %s:", n)})
    end

    return finalize_page({}, stats, false)
end

-- Record 1 sample of cache statistics.
-- (Unlike record_data(), this does not return a function, but runs directly.)
local function record_cache_stats()
    local info = mp.get_property_native("demuxer-cache-state")
    if info == nil then
        return
    end

    local a = info["reader-pts"]
    local b = info["cache-end"]
    if a ~= nil and b ~= nil then
        graph_add_value(cache_ahead_buf, b - a)
    end

    graph_add_value(cache_speed_buf, info["raw-input-rate"] or 0)
end

cache_recorder_timer = mp.add_periodic_timer(0.25, record_cache_stats)
cache_recorder_timer:kill()

local function app_stats()
    local header, content = {}, {}
    eval_ass_formatting()
    add_header(header)
    append(header, "", {prefix="Time, network & system:" .. scroll_hint(),
                        nl="", indent=""})
    append(content, "", {prefix=android_stats.playback_header or "PLAYBACK & DECODER",
                         indent=""})
    append_property(content, "media-title", {prefix="File:"})
    append(content, format("%s | %s | %s",
        mp.get_property("current-vo", "--"), mp.get_property("gpu-api", "--"),
        mp.get_property("gpu-context", "--")), {prefix="Renderer:"})
    append_property(content, "current-tracks/video/codec", {prefix="Video:"})
    append_property(content, "hwdec-current", {prefix="Decoder:"})
    append_property(content, "current-tracks/audio/codec", {prefix="Audio:"})
    append_property(content, "audio-out-params/format", {prefix="Audio output:"})
    append_property(content, "video-params/gamma", {prefix="Source transfer:"})
    append_property(content, "video-out-params/gamma", {prefix="Output transfer:"})
    append(content, android_stats.hdr_mode, {prefix="App HDR setting:"})
    add_motion(content)

    append(content, android_stats.clock or os.date("%H:%M"), {prefix="Time:"})
    append_property(content, "playback-time", {prefix="Position:"})
    append_property(content, "playtime-remaining", {prefix="Remaining:"})
    local speed = mp.get_property_number("cache-speed")
    if speed and speed >= 0 then
        append(content, utils.format_bytes_humanized(speed) .. "/s", {prefix="Network:"})
    end
    for _, section in ipairs(android_stats.sections or {}) do
        append(content, "", {prefix=no_ASS(section.title), indent="", nl=o.nl .. o.nl})
        for _, row in ipairs(section.rows) do
            append(content, row.value, {prefix=no_ASS(row.label) .. ":"})
        end
    end
    return finalize_page(header, content, true)
end

local function gesture_guide()
    local width, height = mp.get_osd_size()
    if width <= 0 or height <= 0 then return end
    local scale = 720 / min(width, height)
    width, height = width * scale, height * scale
    local portrait = height > width
    local tall = portrait and height / width >= 1.6
    local data = gesture_guide_data
    local accents = {data.left_color or "EAC5CB", data.center_color or "E3CFCE",
                     data.right_color or "C5C4EF"}
    local ass = require("mp.assdraw").ass_new()
    local padding = max(24, mp.get_property_number("osd-margin-x", 16) * height / 720)
    local gap = 12
    local muted = "BDB9C5"
    local top = max(padding, (data.top_inset or 0) * height)
    local bottom = height - max(padding, (data.bottom_inset or 0) * height)

    local function shape(color, alpha, border, draw)
        ass:new_event()
        ass:append(format("{\\r\\an7\\pos(0,0)\\bord%g\\shad0\\1c&H%s&" ..
                          "\\3c&H%s&\\1a&H%02X&\\3a&H%02X&}",
                          border or 0, color, color, alpha or 0, alpha or 0))
        ass:draw_start()
        draw()
        ass:draw_stop()
    end

    local function box(x, y, w, h, color, alpha, radius)
        if w <= 0 or h <= 0 then return end
        shape(color, alpha, 0, function()
            ass:round_rect_cw(x, y, x + w, y + h, min(radius or 18, w / 2, h / 2))
        end)
    end

    local function wrapped(value, columns, max_lines)
        local chars = {}
        for char in tostring(value):gmatch("[%z\1-\127\194-\244][\128-\191]*") do
            chars[#chars + 1] = char
        end
        local lines, first = {}, 1
        while first <= #chars and #lines < max_lines do
            local last, units = first - 1, 0
            while last < #chars do
                local cost = #chars[last + 1] > 1 and 2 or 1
                if units + cost > columns then break end
                last, units = last + 1, units + cost
            end
            last = max(first, last)
            if last < #chars then
                for i = last, first + math.floor((last - first) / 2), -1 do
                    if chars[i] == " " then last = i; break end
                end
            end
            local line = table.concat(chars, "", first, last):gsub("%s+$", "")
            if #lines == max_lines - 1 and last < #chars then line = line .. "..." end
            lines[#lines + 1] = mp.command_native({"escape-ass", line})
            first = last + 1
            while chars[first] == " " do first = first + 1 end
        end
        return table.concat(lines, "\\N")
    end

    local function text(value, x, y, w, size, color, bold, lines)
        if not value or w <= 0 then return end
        ass:new_event()
        ass:append(format("{\\r\\an7\\pos(%g,%g)\\fs%g\\fn%s\\b%d\\bord0.6\\shad0" ..
                          "\\1c&H%s&\\3c&H18151C&\\q2\\clip(%g,%g,%g,%g)}",
                          x, y, size, o.font ~= "" and o.font or "sans-serif",
                          bold and 1 or 0, color or "FFFFFF", x, y,
                          x + w, y + size * 1.5 * (lines or 2)))
        ass:append(wrapped(value, max(2, math.floor(w / (size * 0.56))), lines or 2))
    end

    local function icon(kind, x, y, size, color)
        shape(color, 0x10, 1.5, function()
            local c, r = size / 2, size * 0.28
            if kind == "hold" or kind == "tap" then
                ass:round_rect_cw(x + 2, y + 2, x + size - 2, y + size - 2, c - 2)
                ass:round_rect_ccw(x + 6, y + 6, x + size - 6, y + size - 6, c - 6)
            elseif kind == "subtitles" then
                ass:round_rect_cw(x, y + 3, x + size, y + size - 3, 4)
                ass:round_rect_ccw(x + 3, y + 6, x + size - 3, y + size - 6, 2)
                ass:rect_cw(x + 6, y + c - 1, x + size - 6, y + c + 1)
            else
                if kind ~= "vertical" and kind ~= "up" and kind ~= "down" then
                    ass:move_to(x + 2, y + c); ass:line_to(x + size - 2, y + c)
                    ass:move_to(x + r, y + c - r); ass:line_to(x + 2, y + c)
                    ass:line_to(x + r, y + c + r)
                    ass:move_to(x + size - r, y + c - r); ass:line_to(x + size - 2, y + c)
                    ass:line_to(x + size - r, y + c + r)
                end
                if kind ~= "horizontal" then
                    ass:move_to(x + c, y + 2); ass:line_to(x + c, y + size - 2)
                    if kind ~= "down" then
                        ass:move_to(x + c - r, y + r); ass:line_to(x + c, y + 2)
                        ass:line_to(x + c + r, y + r)
                    end
                    if kind ~= "up" then
                        ass:move_to(x + c - r, y + size - r); ass:line_to(x + c, y + size - 2)
                        ass:line_to(x + c + r, y + size - r)
                    end
                end
            end
        end)
    end

    local fraction = max(0.01, min(0.5, data.tap_width or 0.35))
    local boundaries = {0, width * fraction, width * (1 - fraction), width}
    for i = 1, 3 do
        local y0, y1 = top, bottom
        if i == 2 and data.center_single then
            y0, y1 = height * (data.center_top or 0.35), height * (1 - (data.center_top or 0.35))
        end
        box(boundaries[i] + 3, y0, boundaries[i + 1] - boundaries[i] - 6,
            y1 - y0, accents[i], 0xEE, 20)
    end
    for _, x in ipairs({boundaries[2], boundaries[3]}) do
        for y = top + 8, bottom - 8, 24 do
            box(x - 0.7, y, 1.4, 10, accents[2], 0xA0, 0)
        end
    end
    if data.center_single then
        local y0 = height * (data.center_top or 0.35)
        local y1 = height * (1 - (data.center_top or 0.35))
        box(boundaries[2], y0, boundaries[3] - boundaries[2], 2, accents[2], 0x70, 0)
        box(boundaries[2], y1 - 2, boundaries[3] - boundaries[2], 2, accents[2], 0x70, 0)
    end

    local header_y = top + (data.controls_visible and (portrait and 86 or 50) or 8)
    box(padding, header_y, width - padding * 2, 90, "18151C", 0x25, 24)
    box(padding + 14, header_y + 15, 44, 44, accents[2], 0xC0, 14)
    text("7", padding + 27, header_y + 21, 28, 26, accents[2], true, 1)
    text(data.title or "Gestures", padding + 72, header_y + 14,
         width - padding * 2 - 240, 26, "FFFFFF", true, 1)
    text(data.orientation, width - padding - 154, header_y + 20, 140, 18, accents[2], false, 1)
    text(data.status, padding + 72, header_y + 51, width - padding * 2 - 90, 17, muted, false, 2)

    local tap_y = max(header_y + 112, height * (tall and 0.34 or 0.28))
    local card_width = (width - padding * 2 - gap * 2) / 3
    local card_height = tall and 152 or 132
    for i, tap in ipairs(data.taps or {}) do
        if i > 3 then break end
        local x = padding + (i - 1) * (card_width + gap)
        local anchor = (boundaries[i] + boundaries[i + 1]) / 2
        shape(accents[i], 0x80, 1, function()
            ass:move_to(x + card_width / 2, tap_y - 4)
            ass:line_to(anchor, tap_y - 22)
        end)
        box(anchor - 3, tap_y - 27, 6, 6, accents[i], 0x20, 3)
        box(x, tap_y, card_width, card_height, accents[i], 0x70, 22)
        box(x + 1.5, tap_y + 1.5, card_width - 3, card_height - 3, "18151C",
            data.blocked and 0x65 or 0x25, 21)
        icon("tap", x + 14, tap_y + 15, 24, accents[i])
        text(tap.single and "1x" or "2x", x + 47, tap_y + 17, card_width - 60, 17, accents[i], true, 1)
        text(tap.label, x + 14, tap_y + (tall and 48 or 44), card_width - 28,
             tall and 17 or 15, muted, false, 2)
        text(tap.action, x + 14, tap_y + (tall and 92 or 84), card_width - 28,
             tall and 20 or 18, tap.enabled and "FFFFFF" or muted, true, 2)
    end

    local rail_y = tap_y + card_height + (tall and 54 or 16)
    local reserved = data.center_reserved
    local rail_width = reserved and card_width or (width - padding * 2 - gap) / 2
    local rail_color = data.vertical_enabled and "FFFFFF" or muted
    for i, label in ipairs({data.vertical_left, data.vertical_right}) do
        local x = i == 1 and padding or width - padding - rail_width
        box(x, rail_y, rail_width, 98, "18151C", 0x35, 20)
        icon("vertical", x + 14, rail_y + 17, 26, accents[i == 1 and 1 or 3])
        text(label, x + 50, rail_y + 13, rail_width - 62, 20, rail_color, true, 2)
        text(data.vertical_hint, x + 14, rail_y + 59, rail_width - 28, 16, muted, false, 2)
    end
    if reserved then
        local x = padding + card_width + gap
        box(x, rail_y, card_width, 98, "18151C", 0x35, 20)
        if data.swipe_up then
            icon("up", x + 12, rail_y + 14, 22, accents[2])
            text(data.swipe_up, x + 44, rail_y + 12, card_width - 56, 19, "FFFFFF", true, 1)
        end
        if data.swipe_down then
            icon("down", x + 12, rail_y + 55, 22, accents[2])
            text(data.swipe_down, x + 44, rail_y + 54, card_width - 56, 19, "FFFFFF", true, 2)
        end
        if not data.swipe_up and not data.swipe_down then
            text(data.disabled_label, x + 14, rail_y + 32, card_width - 28, 19, muted, false, 2)
        end
    end

    local hints = data.hints or {}
    local columns = tall and 2 or portrait and 3 or 4
    local hint_width = (width - padding * 2 - gap * (columns - 1)) / columns
    local rows = math.ceil(#hints / columns)
    local hint_height = tall and 72 or portrait and 56 or 62
    local hints_y = max(rail_y + 126, bottom - rows * (hint_height + gap) - 48)
    for i, hint in ipairs(hints) do
        local x = padding + ((i - 1) % columns) * (hint_width + gap)
        local y = hints_y + math.floor((i - 1) / columns) * (hint_height + gap)
        box(x, y, hint_width, hint_height, "18151C", 0x35, 18)
        icon(hint.icon, x + 12, y + 18, 24, accents[2])
        text(hint.text, x + 48, y + 12, hint_width - 60, 17, muted, false, 2)
    end
    text(data.center_note, padding, bottom - 28, width - padding * 2, 16, accents[2], false, 1)
    gesture_guide_overlay.res_x = math.floor(width + 0.5)
    gesture_guide_overlay.res_y = math.floor(height + 0.5)
    gesture_guide_overlay.data = ass.text
    gesture_guide_overlay:update()
end

-- Current page and <page key>:<page function> mapping
curr_page = o.key_page_1
pages = {
    [o.key_page_1] = { idx = 1, f = default_stats, desc = "Default" },
    [o.key_page_2] = { idx = 2, f = vo_stats, desc = "Extended Frame Timings", scroll = true },
    [o.key_page_3] = { idx = 3, f = cache_stats, desc = "Cache Statistics" },
    [o.key_page_4] = { idx = 4, f = keybinding_info, desc = "Active Key Bindings", scroll = true },
    [o.key_page_5] = { idx = 5, f = track_info, desc = "Tracks Info", scroll = true },
    [o.key_page_6] = { idx = 6, f = app_stats, desc = "Time, Network & System", scroll = true },
    [o.key_page_7] = { idx = 7, f = gesture_guide, desc = "Gestures" },
    [o.key_page_0] = { idx = 0, f = perf_stats, desc = "Internal Performance Info", scroll = true },
}


-- Returns a function to record vsratio/jitter with the specified `skip` value
local function record_data(skip)
    init_buffers()
    skip = max(skip, 0)
    local i = skip
    return function()
        if i < skip then
            i = i + 1
            return
        else
            i = 0
        end

        if o.plot_vsync_jitter then
            local r = mp.get_property_number("vsync-jitter")
            if r then
                vsjitter_buf.pos = (vsjitter_buf.pos % vsjitter_buf.len) + 1
                vsjitter_buf[vsjitter_buf.pos] = r
                vsjitter_buf.max = max(vsjitter_buf.max, r)
            end
        end

        if o.plot_vsync_ratio then
            local r = mp.get_property_number("vsync-ratio")
            if r then
                vsratio_buf.pos = (vsratio_buf.pos % vsratio_buf.len) + 1
                vsratio_buf[vsratio_buf.pos] = r
                vsratio_buf.max = max(vsratio_buf.max, r)
            end
        end
    end
end

-- Call the function for `page` and print it to OSD
local function print_page(page, after_scroll)
    if not before_page_render(pages[page].idx) then return end
    if pages[page].idx == 7 then
        if not gesture_guide_visible then
            if o.persistent_overlay then mp.set_osd_ass(0, 0, "") else mp.osd_message("", 0) end
        end
        gesture_guide()
        gesture_guide_visible = true
        stats_visible = true
        return
    elseif gesture_guide_visible then
        gesture_guide_overlay:remove()
        gesture_guide_visible = false
    end
    -- the page functions assume we start in ass-enabled mode.
    -- that's true for mp.set_osd_ass, but not for mp.osd_message.
    local ass_content = pages[page].f(after_scroll)
    if o.persistent_overlay then
        mp.set_osd_ass(0, 0, ass_content)
    else
        mp.osd_message((o.use_ass and ass_start or "") .. ass_content,
                       display_timer.oneshot and o.duration or o.redraw_delay + 1)
    end
    stats_visible = true
end

update_scale = function ()
    local scale_with_video
    if o.vidscale == "auto" then
        scale_with_video = mp.get_property_native("osd-scale-by-window")
    else
        scale_with_video = o.vidscale == "yes"
    end

    -- Keep mpv's landscape sizing, but use the short edge instead of portrait height.
    local scale = 288 / 720
    local osd_width = mp.get_property_native("osd-width", 0)
    local osd_height = mp.get_property_native("osd-height", 0)
    if not scale_with_video and osd_height > 0 then
        scale = 288 / osd_height
    elseif osd_width > 0 and osd_height > osd_width then
        scale = scale * osd_width / osd_height
    end
    font_size = o.font_size * scale
    border_size = o.border_size * scale
    shadow_x_offset = o.shadow_x_offset * scale
    shadow_y_offset = o.shadow_y_offset * scale
    plot_bg_border_width = o.plot_bg_border_width * scale
    if display_timer:is_enabled() then
        print_page(curr_page)
    end
end

local function clear_screen()
    gesture_guide_overlay:remove()
    gesture_guide_visible = false
    if o.persistent_overlay then mp.set_osd_ass(0, 0, "") else mp.osd_message("", 0) end
    stats_visible = false
    statistics_hidden()
end

local function scroll_delta(d)
    if display_timer.oneshot then display_timer:kill() ; display_timer:resume() end
    pages[curr_page].offset = (pages[curr_page].offset or 1) + d
    print_page(curr_page, true)
end
local function scroll_up() scroll_delta(-o.scroll_lines) end
local function scroll_down() scroll_delta(o.scroll_lines) end

local function reset_scroll_offsets()
    for _, page in pairs(pages) do
        page.offset = nil
    end
end
local function bind_scroll()
    if not scroll_bound then
        mp.add_forced_key_binding(o.key_scroll_up, "__forced_" .. o.key_scroll_up,
                                  scroll_up, {repeatable=true})
        mp.add_forced_key_binding(o.key_scroll_down, "__forced_" .. o.key_scroll_down,
                                  scroll_down, {repeatable=true})
        scroll_bound = true
    end
end
local function unbind_scroll()
    if scroll_bound then
        mp.remove_key_binding("__forced_"..o.key_scroll_up)
        mp.remove_key_binding("__forced_"..o.key_scroll_down)
        scroll_bound = false
    end
end

local add_page_bindings
local remove_page_bindings

local function filter_bindings()
    statistics_search_active = true
    input.get({
        prompt = "Filter bindings:",
        opened = function ()
            -- This is necessary to close the console if the oneshot
            -- display_timer expires without typing anything.
            searched_text = ""

            -- Must be re-bound to override the console.lua bindings.
            remove_page_bindings()
            bind_scroll()
        end,
        edited = function (text)
            reset_scroll_offsets()
            searched_text = text:lower()
            print_page(curr_page)
            if display_timer.oneshot then
                display_timer:kill()
                display_timer:resume()
            end
        end,
        closed = function ()
            statistics_search_active = false
            searched_text = nil
            if display_timer:is_enabled() then
                add_page_bindings()
                print_page(curr_page)
                if display_timer.oneshot then
                    display_timer:kill()
                    display_timer:resume()
                end
            end
        end,
    })
end

local function bind_search()
    mp.add_forced_key_binding(o.key_search, "__forced_"..o.key_search, filter_bindings)
end

local function unbind_search()
    mp.remove_key_binding("__forced_"..o.key_search)
end

local function bind_exit()
    -- Don't bind in oneshot mode because if ESC is pressed right when the stats
    -- stop being displayed, it would unintentionally trigger any user-defined
    -- ESC binding.
    if not display_timer.oneshot then
        mp.add_forced_key_binding(o.key_exit, "__forced_" .. o.key_exit, function ()
            process_key_binding(false)
        end)
    end
end

local function unbind_exit()
    mp.remove_key_binding("__forced_" .. o.key_exit)
end

local function update_scroll_bindings(k)
    if pages[k].scroll then
        bind_scroll()
    else
        unbind_scroll()
    end

    if k == o.key_page_4 then
        bind_search()
    else
        unbind_search()
    end
end

-- Add keybindings for every page
add_page_bindings = function()
    if curr_page == o.key_page_7 then return end
    local function a(k)
        return function()
            reset_scroll_offsets()
            remove_page_bindings()
            curr_page = k
            add_page_bindings()
            print_page(k)
            if display_timer.oneshot then display_timer:kill() ; display_timer:resume() end
        end
    end
    for k, _ in pairs(pages) do
        mp.add_forced_key_binding(k, "__forced_"..k, a(k), {repeatable=true})
    end
    update_scroll_bindings(curr_page)
    bind_exit()
end


-- Remove keybindings for every page
remove_page_bindings = function()
    for k, _ in pairs(pages) do
        mp.remove_key_binding("__forced_"..k)
    end
    unbind_scroll()
    unbind_search()
    unbind_exit()
end


local function hide_stats()
    display_timer:kill()
    cache_recorder_timer:stop()
    if tm_viz_prev ~= nil then
        mp.set_property_native("tone-mapping-visualize", tm_viz_prev)
        tm_viz_prev = nil
    end
    if stats_visible then clear_screen() end
    remove_page_bindings()
    if recorder then
        mp.unobserve_property(recorder)
        recorder = nil
    end
end

process_key_binding = function(oneshot)
    reset_scroll_offsets()
    -- Stats are already being displayed
    if display_timer:is_enabled() then
        -- Previous and current keys were oneshot -> restart timer
        if display_timer.oneshot and oneshot then
            display_timer:kill()
            print_page(curr_page)
            display_timer:resume()
        -- Previous and current keys were toggling -> end toggling
        elseif not display_timer.oneshot and not oneshot then
            hide_stats()
        end
    -- No stats are being displayed yet
    else
        if not oneshot and (o.plot_vsync_jitter or o.plot_vsync_ratio) then
            recorder = record_data(o.skip_frames)
            -- Rely on the fact that "vsync-ratio" is updated at the same time.
            -- Using "none" to get a sample any time, even if it does not change.
            -- Will stop working if "vsync-jitter" property change notification
            -- changes, but it's fine for an internal script.
            mp.observe_property("vsync-jitter", "none", recorder)
        end
        if not oneshot and o.plot_tonemapping_lut then
            tm_viz_prev = mp.get_property_native("tone-mapping-visualize")
            mp.set_property_native("tone-mapping-visualize", true)
        end
        if not oneshot then
            cache_ahead_buf = {0, pos = 1, len = 50, max = 0}
            cache_speed_buf = {0, pos = 1, len = 50, max = 0}
            cache_recorder_timer:resume()
        end
        display_timer:kill()
        display_timer.oneshot = oneshot
        display_timer.timeout = oneshot and o.duration or o.redraw_delay
        add_page_bindings()
        print_page(curr_page)
        display_timer:resume()
    end
end


-- Create the timer used for redrawing (toggling) or clearing the screen (oneshot)
-- The duration here is not important and always set in process_key_binding()
display_timer = mp.add_periodic_timer(o.duration,
    function()
        if display_timer.oneshot then
            display_timer:kill() ; clear_screen() ; remove_page_bindings()
            -- Close the console only if it was opened for searching bindings.
            if statistics_search_active then
                input.terminate()
            end
        else
            print_page(curr_page)
        end
    end)
display_timer:kill()

-- Single invocation key binding
mp.add_key_binding(nil, "display-stats", function() process_key_binding(true) end,
    {repeatable=true})

-- Toggling key binding
mp.add_key_binding(nil, "display-stats-toggle", function() process_key_binding(false) end,
    {repeatable=false})

for k, page in pairs(pages) do
    -- Single invocation key bindings for specific pages, e.g.:
    -- "e script-binding stats/display-page-2"
    mp.add_key_binding(nil, "display-page-" .. page.idx, function()
        curr_page = k
        process_key_binding(true)
    end, {repeatable=true})

    -- Key bindings to toggle a specific page, e.g.:
    -- "h script-binding stats/display-page-4-toggle".
    mp.add_key_binding(nil, "display-page-" .. page.idx .. "-toggle", function()
        curr_page = k
        process_key_binding(false)
    end, {repeatable=false})
end

-- One request property survives script startup; replies carry its revision so the app
-- can discard notifications from a superseded tap.
local request_property = "user-data/mpvrx/statistics-request"
local state_property = "user-data/mpvrx/statistics-state"
local request_revision
local requested_page = 0
local console_pending
local reconciling = false

local function console_open()
    return mp.get_property_bool("user-data/mpv/console/open", false)
end

local function publish_page(page)
    if request_revision then
        mp.set_property(state_property, request_revision .. ":" .. page)
    end
end

local function set_console(active)
    console_pending = active
    if active then
        mp.commandv("script-binding", "console/enable")
    else
        -- console.lua's first disable only leaves history-search mode, if active.
        mp.commandv("script-message-to", "console", "disable")
        mp.commandv("script-message-to", "console", "disable")
    end
end

local function show_page(index)
    for key, page in pairs(pages) do
        if page.idx == index then
            if display_timer:is_enabled() and not display_timer.oneshot then
                reset_scroll_offsets()
                remove_page_bindings()
                curr_page = key
                add_page_bindings()
                print_page(key)
            else
                -- Unlike the stock toggle, explicit selection also replaces a one-shot.
                hide_stats()
                curr_page = key
                process_key_binding(false)
            end
            return
        end
    end
    mp.msg.error("Unknown statistics page: " .. tostring(index))
end

local function reconcile()
    if not request_revision then return end
    reconciling = true
    local native_page = requested_page >= 1 and requested_page <= 6 or
                        requested_page == 8 or requested_page == gesture_guide_selection
    if not native_page or console_pending ~= nil or console_open() then
        if statistics_search_active then
            statistics_search_active = false
            console_pending = false
            input.terminate()
        end
        hide_stats()
    end

    -- Finish the outstanding console operation before issuing its opposite. Otherwise
    -- a late "open" can resurrect Console after a newer Off or Page 6 request.
    if console_pending ~= nil then
        reconciling = false
        return
    end
    local wants_console = requested_page == 7
    if wants_console ~= console_open() then
        set_console(wants_console)
        reconciling = false
        return
    end
    if native_page then
        -- mpv's internal performance page is key 0, distinct from the app's Off.
        show_page(requested_page == 8 and 0 or
                  requested_page == gesture_guide_selection and 7 or requested_page)
    end
    publish_page(requested_page)
    reconciling = false
end

before_page_render = function(index)
    if reconciling then return true end
    requested_page = index == 0 and 8 or index == 7 and gesture_guide_selection or index
    if statistics_search_active and index == 4 then
        publish_page(requested_page)
        return true
    end
    if console_pending ~= nil or console_open() then
        if console_pending == nil then set_console(false) end
        return false
    end
    publish_page(requested_page)
    return true
end

statistics_hidden = function()
    if not reconciling then
        requested_page = 0
        publish_page(0)
    end
end

-- String-format reads of user-data JSON-quote strings; native reads preserve the payload.
mp.observe_property(request_property, "native", function(_, value)
    if value == nil then return end
    if type(value) ~= "string" then
        mp.msg.error("Invalid statistics request type: " .. type(value))
        return
    end
    local revision, page = value:match("^(%d+):(%d+)$")
    page = tonumber(page)
    if not revision or not page or page > gesture_guide_selection then
        mp.msg.error("Invalid statistics request: " .. tostring(value))
        return
    end
    request_revision = revision
    requested_page = page
    reconcile()
end)

mp.observe_property("user-data/mpv/console/open", "bool", function(_, value)
    if not request_revision then return end
    if statistics_search_active then return end
    if console_pending ~= nil then
        if (value == true) ~= console_pending then return end
        console_pending = nil
    elseif console_open() then
        requested_page = 7
    elseif requested_page == 7 then
        requested_page = 0
    end
    reconcile()
end)

mp.observe_property("user-data/mpvrx/statistics-android", "native", function(_, value)
    if not value then
        android_stats = {}
        return
    end
    if type(value) ~= "string" then
        mp.msg.error("Invalid Android statistics type: " .. type(value))
        return
    end
    local data, err = utils.parse_json(value)
    if type(data) ~= "table" then
        mp.msg.error("Invalid Android statistics: " .. tostring(err))
        return
    end
    android_stats = data
end)

mp.observe_property("user-data/mpvrx/gesture-guide", "native", function(_, value)
    if value == nil then
        gesture_guide_data = {}
        return
    end
    if type(value) ~= "string" then
        mp.msg.error("Invalid gesture guide type: " .. type(value))
        return
    end
    local data, err = utils.parse_json(value)
    if type(data) ~= "table" then
        mp.msg.error("Invalid gesture guide: " .. tostring(err))
        return
    end
    gesture_guide_data = data
    if gesture_guide_visible then gesture_guide() end
end)

mp.register_script_message("mpvrx-stats-scroll", function(delta)
    delta = tonumber(delta)
    if not delta or delta == 0 or delta ~= math.floor(delta) then
        mp.msg.error("Invalid statistics scroll delta")
        return
    end
    if requested_page == 7 and console_open() then
        -- Wheel/Up/Down edit command history. Resolve Console's real log bindings
        -- instead of relying on its version-dependent "_console_N" numbering.
        local key = delta > 0 and "shift+down" or "shift+up"
        for _, binding in ipairs(mp.get_property_native("input-bindings", {})) do
            local target = (binding.cmd or ""):match("script%-binding%s+(console/[%w_]+)%s*$")
            if target and (binding.key or ""):lower() == key then
                for _ = 1, math.min(100, math.abs(delta)) do
                    mp.commandv("script-binding", target)
                end
                return
            end
        end
        mp.msg.error("Console log scroll binding is unavailable")
        return
    end
    if display_timer:is_enabled() and pages[curr_page].scroll and
       (not console_open() or statistics_search_active) then
        scroll_delta(max(-100, min(100, delta)))
    end
end)

-- Reprint stats immediately when VO was reconfigured, only when toggled
mp.register_event("video-reconfig",
    function()
        if display_timer:is_enabled() and not display_timer.oneshot then
            print_page(curr_page)
        end
    end)

if o.bindlist ~= "no" then
    -- This is a special mode to print key bindings to the terminal,
    -- Adjust the print format and level to make it print only the key bindings.
    mp.set_property("msg-level", "all=no,statusline=status")
    mp.set_property("term-osd", "force")
    mp.set_property_bool("msg-module", false)
    mp.set_property_bool("msg-time", false)
    -- wait for all other scripts to finish init
    mp.add_timeout(0, function()
        if o.bindlist:sub(1, 1) == "-" then
            o.no_ass_b0 = ""
            o.no_ass_b1 = ""
        end
        o.ass_formatting = false
        o.no_ass_indent = " "
        mp.osd_message(keybinding_info(false, true))
        -- wait for next tick to print status line and flush it without clearing
        mp.add_timeout(0, function()
            mp.command("flush-status-line no")
            mp.command("quit")
        end)
    end)
end

mp.observe_property("osd-width", "native", update_scale)
mp.observe_property("osd-height", "native", update_scale)
mp.observe_property("osd-scale-by-window", "native", update_scale)

local function update_property_cache(name, value)
    property_cache[name] = value
end

mp.observe_property('current-window-scale', 'native', update_property_cache)
mp.observe_property('display-names', 'string', update_property_cache)
mp.observe_property('hwdec-current', 'string', update_property_cache)
