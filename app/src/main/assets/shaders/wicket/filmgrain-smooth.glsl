// Copyright (C) 2026 Ágúst Ari
// Licensed under GPL-3.0 — see LICENSE
//
// Film Grain Smooth — one shampv-tunable shader that replaces the old
// SDR/HDR x light/medium/heavy variant matrix. Separable per-channel Gaussian
// grain (red fine, green mid, blue coarse — the chromatic-size signature) is
// generated once per SOURCE frame on a fixed 3840x2160 grid, then composited
// display-scaled every present. SDR and HDR (PQ BT.2020 output, i.e. mpv's
// target-trc=pq) share ONE grain model via a per-pixel PQ bridge — flip
// grain_hdr to match the player target. Pick a baked
// look with grain_preset (light/medium/heavy), or set it to custom (0) and tune
// the individual DYNAMIC grain_* knobs; grain_hdr, grain_size and grain_rate stay
// live in any preset. All controls apply with no recompile.
//
// This is the LIGHT/GENERAL cousin of Film Grain Match: no adaptive scene
// measurement (fixed look), so the per-source-frame cost is just the 4K grain
// gen; re-presents only fetch the field, so on a high-refresh panel it costs
// far less than the old per-present generation.
//
// Pipeline (each HOOK is its own translation unit):
//   1. tick      (LUMA, fresh group)   source-frame counter -> GRAIN_STATE
//   2. gen       (LUMA, fresh group)   fixed-4K per-channel grain -> GRAIN_FIELD
//   3. composite (OUTPUT, redraw group) fetch scaled field, tone-key, add, bridge
//
// Why LUMA for the generators: an mpv LUMA hook runs in the FRESH group — once
// per source frame regardless of display refresh — while OUTPUT runs per
// present. Generating grain in the fresh group and persisting it in a storage
// texture is what locks the grain cadence to the source, not the display.

//@shampv toggle grain_hdr
//@shampv target-trc-param grain_hdr
//@shampv choice grain_preset custom light medium heavy
//@shampv active-if grain_preset 0 grain_intensity grain_saturation grain_mid grain_steepness
//@shampv step grain_steepness 0.1

//!PARAM grain_preset
//!DESC Baked look. 0 = custom (grain_* knobs) · 1 light · 2 medium · 3 heavy. Presets set intensity/saturation/mid/steepness; size/rate/hdr/ref_white stay live.
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 3.0
0.0

//!PARAM grain_intensity
//!DESC Grain amplitude (custom preset only). ↑ = stronger, 0 = none. light ~0.05, medium ~0.12, heavy ~0.20. Ignored when a preset is active.
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 0.5
0.05

//!PARAM grain_saturation
//!DESC Grain chroma (custom only). 0 = monochrome · ↑ 1 = full per-channel color. Updates on the next source frame. Ignored when a preset is active.
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.10

//!PARAM grain_size
//!DESC Grain cell size at a 4K reference (constant visual angle). ↓ <1 = finer · ↑ >1 = coarser. 1 = calibrated. Live, display-scaled.
//!TYPE DYNAMIC float
//!MINIMUM 0.5
//!MAXIMUM 3.0
1.0

//!PARAM grain_mid
//!DESC Tone where grain peaks (custom only). ↓ 0 = shadows · 0.5 = midtones · ↑ 1 = highlights. Ignored when a preset is active.
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.5

//!PARAM grain_steepness
//!DESC Tone-bell tightness (custom only). ↑ = confine grain to the mid tone, cleaner highlights · ↓ = spread wider. Ignored when a preset is active.
//!TYPE DYNAMIC float
//!MINIMUM 0.5
//!MAXIMUM 20.0
2.0

//!PARAM grain_rate
//!DESC Reseed cadence in SOURCE frames. 1 = every frame / on ones · 0.5 = on twos · ↓ slows the boil. Display-refresh independent.
//!TYPE DYNAMIC float
//!MINIMUM 0.1
//!MAXIMUM 1.0
1.0

//!PARAM grain_hdr
//!DESC Output transfer — match mpv's target-trc (shampv syncs it). 1 = PQ BT.2020 out: grain keyed/applied in SDR via a PQ bridge, fades above ref white · 0 = plain SDR (bit-identical).
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.0

//!PARAM grain_ref_white
//!DESC SDR reference white (nits) the output anchors to — match hdr-reference-white, or the upstream shader's if one owns the SDR→PQ encode. Only used when grain_hdr = 1.
//!TYPE DYNAMIC float
//!MINIMUM 80.0
//!MAXIMUM 480.0
100.0

//!BUFFER GRAIN_STATE
//!VAR float m_gen_frame
//!VAR float m_state_magic
//!STORAGE

//!TEXTURE GRAIN_FIELD
//!SIZE 3840 2160
//!FORMAT rgba16f
//!STORAGE

//!HOOK LUMA
//!BIND HOOKED
//!BIND GRAIN_STATE
//!SAVE GRAIN_TICK
//!WIDTH 1
//!HEIGHT 1
//!COMPUTE 32 32
//!DESC Film Grain Smooth: source-frame tick

// One thread advances the source-frame counter. This is a LUMA hook = fresh
// group, so it fires exactly once per source frame; `frame` (the builtin) ticks
// per PRESENT and would over-count on redraws / high refresh, so it is used
// ONLY as the first-frame reset guard. The storage buffer reads garbage on its
// first use, so a magic sentinel gates the counter until it is initialized.
#define STATE_MAGIC 0.5567819

void hook() {
    if (gl_LocalInvocationIndex == 0u) {
        bool state_ok = abs(m_state_magic - STATE_MAGIC) < 0.0001;
        if (!state_ok || frame == 0u) {
            m_gen_frame = 0.0;
            m_state_magic = STATE_MAGIC;
        } else {
            m_gen_frame += 1.0;
        }
    }
    imageStore(out_image, ivec2(0), vec4(0.0));
}

//!HOOK LUMA
//!BIND HOOKED
//!BIND GRAIN_STATE
//!BIND GRAIN_FIELD
//!SAVE GRAIN_GEN_TRIGGER
//!WIDTH 3840
//!HEIGHT 2160
//!COMPUTE 32 32
//!DESC Film Grain Smooth: grain gen (fixed 4K, source-locked)

// Grain is generated on a FIXED 3840x2160 grid, decoupled from both source and
// display resolution, into the persistent GRAIN_FIELD storage image. The
// composite (redraw group) only fetches it, so re-presents are ~free and the
// grain can never ride the display refresh. The grid IS the grain's own
// resolution ("a 4K scan"); the composite scales it to the display for a
// constant on-screen grain size. A throwaway GRAIN_GEN_TRIGGER is SAVE'd purely
// to size this 3840x2160 dispatch.
//
// GEN_GRID MUST equal this pass's WIDTH/HEIGHT directives and GRAIN_FIELD's SIZE
// (same file, no compile guard — hand-kept in lockstep). The composite derives
// its dims from imageSize(GRAIN_FIELD) so it cannot desync.
#define GEN_GRID ivec2(3840, 2160)

// Per-channel Gaussian sizes — the chromatic-grain signature: red is the finest
// (3-tap), green medium (5-tap), blue the coarsest (7-tap). MAX_TAPS bounds the
// shared-memory halo. Live grain_size is applied at the composite (a field
// zoom), so these stay fixed and the ratio between channels is preserved.
#define MAX_TAPS 3
#define RED_TAPS 1
#define GREEN_TAPS 2
#define BLUE_TAPS 3

#define RED_VARIANCE_SCALE 1.0
#define GREEN_VARIANCE_SCALE 1.0
#define BLUE_VARIANCE_SCALE 1.0

#define RED_SATURATION 0.5
#define GREEN_SATURATION 0.5
#define BLUE_SATURATION 0.5

#define RED_INTENSITY_MULTIPLIER 1.0
#define GREEN_INTENSITY_MULTIPLIER 1.0
#define BLUE_INTENSITY_MULTIPLIER 1.0

const uvec2 isize = uvec2(gl_WorkGroupSize) + uvec2(2 * MAX_TAPS);

shared float grain_r[isize.y][isize.x];
shared float grain_g[isize.y][isize.x];
shared float grain_b[isize.y][isize.x];
// NV-Vulkan SSBO snapshot: per-thread reads of a BUFFER-directive SSBO run
// uncached on NVIDIA Vulkan (dev-notes win-harness results-0723-ssbo-sweep /
// Match Grain audit), so lane 0 reads the frame counter once per workgroup
// and publishes it through shared memory. Value-identical — the tick pass
// finished writing before this pass launched, so every lane reads one value.
shared float sh_gen_frame;

const float weights_red[3] = { 0.23899, 0.52202, 0.23899 };
const float weights_green[5] = { 0.05449, 0.24420, 0.40262, 0.24420, 0.05449 };
const float weights_blue[7] = { 0.01465, 0.08312, 0.23556, 0.33335, 0.23556, 0.08312, 0.01465 };

// PRNG: PCG hash (stateless, no visible patterns).
uint pcg_hash(uint s) {
    uint state = s * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

float rand_triangular(inout uint state, float variance_scale) {
    uint a = pcg_hash(state); state = a;
    uint b = pcg_hash(state); state = b;
    float u = float(a) * (1.0 / 4294967296.0);
    float v = float(b) * (1.0 / 4294967296.0);
    return (u + v - 1.0) * 0.612 * variance_scale;
}

void hook() {
    uint num_threads = gl_WorkGroupSize.x * gl_WorkGroupSize.y;
    // SSBO snapshot (see sh_gen_frame above). The barrier sits in uniform
    // control flow ahead of the existing two, before any use of the seed.
    if (gl_LocalInvocationIndex == 0u)
        sh_gen_frame = m_gen_frame;
    barrier();
    // Source-locked seed: floor(counter * rate) reseeds once per source frame at
    // grain_rate 1.0, every 2nd source frame at 0.5, on ANY display.
    uint frame_seed = uint(max(0.0, floor(sh_gen_frame * grain_rate)));

    // grain_preset resolve (gen side): a tier sets global saturation and the
    // per-channel chroma balance / variance / intensity-mult (the tunings that
    // actually differ across light/medium/heavy). custom (0) keeps the live
    // grain_saturation knob and the neutral per-channel #define baseline.
    int preset = int(clamp(grain_preset, 0.0, 3.0) + 0.5);
    float eff_sat  = grain_saturation;
    vec3 chan_sat  = vec3(RED_SATURATION, GREEN_SATURATION, BLUE_SATURATION);
    vec3 chan_var  = vec3(RED_VARIANCE_SCALE, GREEN_VARIANCE_SCALE, BLUE_VARIANCE_SCALE);
    vec3 chan_mult = vec3(RED_INTENSITY_MULTIPLIER, GREEN_INTENSITY_MULTIPLIER, BLUE_INTENSITY_MULTIPLIER);
    if (preset == 1)      { eff_sat = 0.10; chan_sat = vec3(0.5, 0.5, 0.5); }
    else if (preset == 2) { eff_sat = 0.25; chan_sat = vec3(0.6, 0.5, 0.4); }
    else if (preset == 3) { eff_sat = 0.50; chan_sat = vec3(0.7, 0.6, 0.5);
                            chan_var = vec3(1.1, 1.0, 1.1); chan_mult = vec3(1.0, 1.0, 1.1); }

    // 1. Generate white triangular noise per channel. The halo is regenerated
    //    from TOROIDALLY-WRAPPED global coords so the fixed grid tiles with no
    //    seam when a display is taller/wider than the grid.
    for (uint i = gl_LocalInvocationIndex; i < isize.y * isize.x; i += num_threads) {
        uvec2 local_pos = uvec2(i % isize.x, i / isize.x);
        ivec2 global_coord_i = ivec2(gl_WorkGroupID.xy * gl_WorkGroupSize.xy) + ivec2(local_pos) - ivec2(MAX_TAPS);
        // global_coord_i >= -MAX_TAPS, so one added grid extent keeps the
        // dividend non-negative. GLSL leaves % undefined on negative operands,
        // and NVIDIA's Vulkan compiler really does wrap them as unsigned: the
        // old (x % G + G) % G sent row 0 / column 0's halo to the wrong place
        // and broke the torus at the field's wrap line.
        uvec2 global_pos = uvec2((global_coord_i + GEN_GRID) % GEN_GRID);

        // Asymmetric seed: x*Prime1 + y*Prime2 to prevent mirroring.
        uint seed_init = (global_pos.x * 1664525u) + (global_pos.y * 22695477u) + (frame_seed * 314159265u);

        float g_r = rand_triangular(seed_init, chan_var.r);
        float g_g = rand_triangular(seed_init, chan_var.g);
        float g_b = rand_triangular(seed_init, chan_var.b);

        float grain_lum = dot(vec3(g_r, g_g, g_b), vec3(0.299, 0.587, 0.114));
        grain_r[local_pos.y][local_pos.x] = mix(grain_lum, g_r, chan_sat.r * eff_sat);
        grain_g[local_pos.y][local_pos.x] = mix(grain_lum, g_g, chan_sat.g * eff_sat);
        grain_b[local_pos.y][local_pos.x] = mix(grain_lum, g_b, chan_sat.b * eff_sat);
    }

    barrier();

    // 2. Convolve horizontal (per-channel variable taps).
    for (uint y = gl_LocalInvocationID.y; y < isize.y; y += gl_WorkGroupSize.y) {
        float hsum_r = 0.0;
        float hsum_g = 0.0;
        float hsum_b = 0.0;

        for (int x = 0; x < 2 * MAX_TAPS + 1; x++) {
            // Guards let one shader carry three different per-channel tap sizes.
            if (x < 2 * RED_TAPS + 1)   hsum_r += weights_red[x]   * grain_r[y][gl_LocalInvocationID.x + x + (MAX_TAPS - RED_TAPS)];
            if (x < 2 * GREEN_TAPS + 1) hsum_g += weights_green[x] * grain_g[y][gl_LocalInvocationID.x + x + (MAX_TAPS - GREEN_TAPS)];
            if (x < 2 * BLUE_TAPS + 1)  hsum_b += weights_blue[x]  * grain_b[y][gl_LocalInvocationID.x + x + (MAX_TAPS - BLUE_TAPS)];
        }
        grain_r[y][gl_LocalInvocationID.x + MAX_TAPS] = hsum_r;
        grain_g[y][gl_LocalInvocationID.x + MAX_TAPS] = hsum_g;
        grain_b[y][gl_LocalInvocationID.x + MAX_TAPS] = hsum_b;
    }

    barrier();

    // 3. Convolve vertical -> final signed per-channel grain, write the 4K field.
    float vsum_r = 0.0;
    float vsum_g = 0.0;
    float vsum_b = 0.0;

    for (int y = 0; y < 2 * MAX_TAPS + 1; y++) {
        if (y < 2 * RED_TAPS + 1)   vsum_r += weights_red[y]   * grain_r[gl_LocalInvocationID.y + y + (MAX_TAPS - RED_TAPS)][gl_LocalInvocationID.x + MAX_TAPS];
        if (y < 2 * GREEN_TAPS + 1) vsum_g += weights_green[y] * grain_g[gl_LocalInvocationID.y + y + (MAX_TAPS - GREEN_TAPS)][gl_LocalInvocationID.x + MAX_TAPS];
        if (y < 2 * BLUE_TAPS + 1)  vsum_b += weights_blue[y]  * grain_b[gl_LocalInvocationID.y + y + (MAX_TAPS - BLUE_TAPS)][gl_LocalInvocationID.x + MAX_TAPS];
    }

    // COMPUTE 32 rounds 2160 up to 2176, so guard the out-of-range rows: an OOB
    // imageStore is a spec-legal no-op, but guarding keeps it portable (D3D11).
    ivec2 gpos = ivec2(gl_GlobalInvocationID.xy);
    if (all(lessThan(gpos, imageSize(GRAIN_FIELD))))
        imageStore(GRAIN_FIELD, gpos, vec4(vsum_r * chan_mult.r,
                                           vsum_g * chan_mult.g,
                                           vsum_b * chan_mult.b, 0.0));

    imageStore(out_image, ivec2(0), vec4(0.0));
}

//!HOOK OUTPUT
//!BIND HOOKED
//!BIND GRAIN_FIELD
//!COMPUTE 32 32
//!DESC Film Grain Smooth: composite

#define TUKEY_SCALE 0.459

const vec3 luma_coeff = vec3(0.299, 0.587, 0.114);

// Tone bell: grain amplitude peaks at `mid` and tapers to zero away from it
// (a Tukey window), times a shadow-protection floor so grain does not crawl in
// near-black. In HDR mode the input is the SDR-equivalent code from the bridge,
// so one bell serves both paths; hook() then fades grain out just above
// reference white (below) so expanded highlights stay clean.
float grain_scale(float lum, float mid, float steepness) {
    float d2 = steepness * (lum - mid) * (lum - mid);
    float t = 1.0 - d2 * TUKEY_SCALE;
    float curve = t > 0.0 ? t * t : 0.0;
    float protection = smoothstep(0.0, 0.05, lum);
    return curve * protection;
}

// --- HDR (PQ BT.2020) domain bridge, grain_hdr=1. The grain model is authored
// on gamma-2.4 SDR codes, but whenever the player target is PQ the OUTPUT
// pixels this pass receives are true PQ (libplacebo runs OUTPUT hooks after
// the conversion to the target colorspace). Bridge per pixel: PQ code -> nits
// -> SDR-equivalent 2.4 code vs grain_ref_white; key + apply the model there;
// re-encode. Standard ST 2084 constants, shared with any upstream PQ encode.
float pq_eotf_nits(float e) {
    float p = pow(e, 1.0 / 78.84375);
    return 10000.0 * pow(max(p - 0.8359375, 0.0)
                         / (18.8515625 - 18.6875 * p), 1.0 / 0.1593017578125);
}
float pq_oetf_code(float nits) {
    float y = pow(clamp(nits / 10000.0, 0.0, 1.0), 0.1593017578125);
    return pow((0.8359375 + 18.8515625 * y) / (1.0 + 18.6875 * y), 78.84375);
}

// Bilinear fetch of the toroidal grain field. GRAIN_FIELD is a persistent
// storage image (point-only imageLoad), so bilinear is done by hand from four
// independently-wrapped corners — this keeps grain smooth (not blocky) when the
// display is not 2160-tall, i.e. exactly the sub/over-4K panels this variant
// targets. At gscale 1.0 (a 2160-tall display) the fractional part is zero and
// this returns the exact texel.
vec3 grain_bilinear(vec2 fpos, ivec2 g) {
    vec2 p = fpos - 0.5;
    ivec2 i0 = ivec2(floor(p));
    vec2 f = p - vec2(i0);
    ivec2 i1 = i0 + 1;
    // i0 >= -1 (only when gscale < 1, at gid 0), so adding g once keeps both
    // dividends non-negative — see the gen pass on % with negative operands.
    ivec2 w0 = (i0 + g) % g;
    ivec2 w1 = (i1 + g) % g;
    vec3 c00 = imageLoad(GRAIN_FIELD, ivec2(w0.x, w0.y)).rgb;
    vec3 c10 = imageLoad(GRAIN_FIELD, ivec2(w1.x, w0.y)).rgb;
    vec3 c01 = imageLoad(GRAIN_FIELD, ivec2(w0.x, w1.y)).rgb;
    vec3 c11 = imageLoad(GRAIN_FIELD, ivec2(w1.x, w1.y)).rgb;
    return mix(mix(c00, c10, f.x), mix(c01, c11, f.x), f.y);
}

void hook() {
    vec4 color = HOOKED_tex(HOOKED_pos);

    // grain_preset resolve (composite side): a tier sets amplitude and the tone
    // bell (mid/steepness). custom (0) keeps the live grain_intensity / grain_mid
    // / grain_steepness knobs. Tables here and in the gen pass share no value, so
    // there is no cross-pass constant to keep in sync.
    int preset = int(clamp(grain_preset, 0.0, 3.0) + 0.5);
    float eff_int   = grain_intensity;
    float eff_mid   = grain_mid;
    float eff_steep = grain_steepness;
    if (preset == 1)      { eff_int = 0.05; eff_mid = 0.50; eff_steep = 2.0; }
    else if (preset == 2) { eff_int = 0.12; eff_mid = 0.40; eff_steep = 1.6; }
    else if (preset == 3) { eff_int = 0.20; eff_mid = 0.35; eff_steep = 1.2; }

    // Source-locked grain, SCALED to the display so it tracks like a real 4K
    // source (constant visual angle). gscale = grid_height / output_height,
    // divided by grain_size (>1 = coarser). Applied uniformly to both axes so
    // grain stays square; horizontal over-extent tiles via the wrap. On a
    // 2160-tall display with grain_size 1.0, gscale == 1.0 -> exact texels.
    ivec2 gid = ivec2(gl_GlobalInvocationID.xy);
    ivec2 gsize = imageSize(GRAIN_FIELD);
    float gscale = (float(gsize.y) / max(HOOKED_size.y, 1.0)) / max(grain_size, 0.05);
    vec2 fpos = (vec2(gid) + 0.5) * gscale;
    vec3 gfield = grain_bilinear(fpos, gsize);

    // grain_hdr bridge: work in the measured SDR domain. Clamp PQ input codes —
    // YUV->RGB overshoot above 1.0 explodes the PQ EOTF.
    bool hdr_bridge = grain_hdr > 0.5;
    vec3 work_rgb = color.rgb;
    if (hdr_bridge) {
        vec3 nits = vec3(pq_eotf_nits(clamp(color.r, 0.0, 1.0)),
                         pq_eotf_nits(clamp(color.g, 0.0, 1.0)),
                         pq_eotf_nits(clamp(color.b, 0.0, 1.0)));
        work_rgb = pow(max(nits / grain_ref_white, vec3(0.0)), vec3(1.0 / 2.4));
    }

    // Per-channel tone keying (each channel keyed by its own value), preserving
    // the original smooth response.
    vec3 scale_vec = vec3(grain_scale(work_rgb.r, eff_mid, eff_steep),
                          grain_scale(work_rgb.g, eff_mid, eff_steep),
                          grain_scale(work_rgb.b, eff_mid, eff_steep));

    // HDR: grain is texture, not signal — fade it to zero just ABOVE reference
    // white (code 1.0) so expanded highlight cores stay clean. This is
    // the "fades above reference white" promise made explicit; it is keyed by
    // the bridged code, so it tracks grain_ref_white. The SDR-range bell
    // (code <= 1.0) and the whole SDR path (grain_hdr=0) are untouched.
    if (hdr_bridge)
        scale_vec *= vec3(1.0) - smoothstep(vec3(1.0), vec3(1.1), work_rgb);

    vec3 pre_grain = work_rgb;
    work_rgb += eff_int * gfield * scale_vec;

    if (hdr_bridge) {
        // Grain is texture, not signal: never push a pixel above max(its own
        // pre-grain level, reference white). The SDR path gets this for free at
        // code 1.0 downstream.
        work_rgb = min(work_rgb, max(pre_grain, vec3(1.0)));
        vec3 out_nits = grain_ref_white * pow(max(work_rgb, vec3(0.0)), vec3(2.4));
        color.rgb = vec3(pq_oetf_code(out_nits.r), pq_oetf_code(out_nits.g),
                         pq_oetf_code(out_nits.b));
    } else {
        color.rgb = work_rgb;
    }

    imageStore(out_image, gid, color);
}
