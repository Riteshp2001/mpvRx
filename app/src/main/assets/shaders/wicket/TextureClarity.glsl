// Copyright (C) 2026 Ágúst Ari
// Licensed under GPL-3.0 — see LICENSE

// User tuning: the four tc_* params below (DYNAMIC — glsl-shader-opts
// changes apply on the next frame, no recompile). Defaults = the shipped
// tune. Everything below the param block is internal.
// shampv shader API (plain comments to libplacebo):
//@shampv input any

//!PARAM tc_strength
//!DESC Sharpening strength. ↑ = sharper · 12 = shipped tune · 0 = off.
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 24.0
12.0

//!PARAM tc_coring
//!DESC Coring threshold — high-pass deltas below this are ignored (stops noise being sharpened). ↑ for noisier sources · ↓ sharpens finer detail.
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 0.003
0.0005

//!PARAM tc_texture_thresh
//!DESC Texture floor — minimum local variance treated as real texture, not noise. ↑ = sharpen only stronger detail · ↓ = also lift faint texture.
//!TYPE DYNAMIC float
//!MINIMUM 0.005
//!MAXIMUM 0.08
0.02

//!PARAM tc_max_delta
//!DESC Per-pixel luma change cap — hard ceiling on how far sharpening may move any pixel. ↑ = allow bigger swings · ↓ = safer.
//!TYPE DYNAMIC float
//!MINIMUM 0.001
//!MAXIMUM 0.05
0.012

//!DESC Texture Clarity
//!HOOK LUMA
//!BIND HOOKED

// Grain suppression parameters
const float grain_threshold = 0.045;
const float grain_transition_width = 0.50;

// Texture discrimination
const float texture_transition_width = 0.018;

// Black/white area safeguards
const float black_threshold = 0.15;
const float black_falloff = 0.10;
const float white_threshold = 0.55;
const float white_falloff = 0.20;

// Enhanced control parameters
const float EDGE_MULTIPLIER = 6.0;
const float DETAIL_LOW = 0.003;
const float DETAIL_HIGH = 0.013;

// Cinematic boost parameters
// Max multiplier applied to sharpen_amount in lowest-variance areas.
const float LOWDETAIL_MAX_BOOST = 1.5;  // final cap (1.0 = no boost, 1.5 = +50%)
// Curve shape is a fixed sqrt() (was pow(.., 0.5)); see the boost block below.

float get_grid(int x, int y, float grid[25]) {
    return grid[(y + 2) * 5 + (x + 2)];
}

vec4 hook() {
    // Pre-sample 5x5 neighborhood
    float grid[25];
    for (int y = -2; y <= 2; y++) {
        for (int x = -2; x <= 2; x++) {
            grid[(y + 2) * 5 + (x + 2)] = HOOKED_texOff(vec2(x, y)).x;
        }
    }

    float p_x = grid[12];
    vec4 p = vec4(p_x, 0.0, 0.0, 1.0);

    // 3x3 window analysis
    float sum_grain = 0.0;
    float sum_texture = 0.0;
    float window_sum = 0.0;
    float window_sum_sq = 0.0;
    float grain_center = 0.0;
    float texture_center = 0.0;
    float max_dev_center = 0.0;

    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            float n = get_grid(x, y, grid);

            // 4 diagonals around (x,y)
            float s0[4];
            s0[0] = get_grid(x + 1, y + 1, grid);
            s0[1] = get_grid(x + 1, y - 1, grid);
            s0[2] = get_grid(x - 1, y + 1, grid);
            s0[3] = get_grid(x - 1, y - 1, grid);

            // Compute deviation and local statistics
            float max_dev = 0.0;
            float min_val = s0[0];
            float max_val = s0[0];
            float local_mean_dev = 0.0;

            for (int j = 0; j < 4; j++) {
                float dv = abs(n - s0[j]);
                max_dev = max(max_dev, dv);
                min_val = min(min_val, s0[j]);
                max_val = max(max_val, s0[j]);
                local_mean_dev += dv;
            }
            local_mean_dev *= 0.25; // true mean deviation

            float thresh = grain_threshold + 0.5 * local_mean_dev;
            float grain = 1.0 - smoothstep(thresh, thresh + grain_transition_width, max_dev);
            float variance = max_val - min_val;
            float texture = smoothstep(tc_texture_thresh, tc_texture_thresh + texture_transition_width, variance);

            sum_grain += grain;
            sum_texture += texture;
            window_sum += n;
            window_sum_sq += n * n;

            if (x == 0 && y == 0) {
                grain_center = grain;
                texture_center = texture;
                max_dev_center = max_dev;
            }
        }
    }

    float avg_grain = sum_grain / 9.0;
    float avg_texture = sum_texture / 9.0;
    float window_mean = window_sum / 9.0;
    // E[x^2] - E[x]^2 form (numerically loose, but fine for luma in [0,1]); the tiny
    // negative FP results it can produce are absorbed by the smoothstep/max() below.
    float window_variance = window_sum_sq / 9.0 - window_mean * window_mean;

    // Local texture blending
    float blend = smoothstep(0.0, 0.003, window_variance);
    float blend_sq = blend * blend;
    float final_grain = mix(avg_grain, grain_center, blend_sq);
    float final_texture = mix(avg_texture, texture_center, blend_sq);

    // Multi-scale factor (vectorized)
    vec4 s1 = vec4(grid[24], grid[4], grid[20], grid[0]);
    vec4 dv_large = abs(p.x - s1);
    float max_dev_large = max(max(dv_large.x, dv_large.y), max(dv_large.z, dv_large.w));
    float scale_diff = max_dev_center - max_dev_large;
    final_grain *= (1.0 - smoothstep(0.0, 0.05, scale_diff));

    // Unsharp mask — isotropic 3x3 binomial high-pass (center 4, edges 2, corners 1; / 16).
    // Replaces the old 5-tap axis cross, which had no diagonal taps and therefore
    // under-sharpened diagonal texture/edges (mild axis-aligned anisotropy). All taps
    // already live in grid[] — this costs zero extra texture fetches.
    float blurred = (4.0 * p.x
                     + 2.0 * (get_grid(1,0,grid) + get_grid(-1,0,grid) +
                              get_grid(0,1,grid) + get_grid(0,-1,grid))
                     + (get_grid(1,1,grid)  + get_grid(-1,1,grid) +
                        get_grid(1,-1,grid) + get_grid(-1,-1,grid))) * (1.0 / 16.0);
    float t = p.x - blurred;
    float t_sharp = t - clamp(t, -tc_coring, tc_coring);

    // Sobel edge detection
    float sobel_h = (grid[8] - grid[6]) + 2.0 * (grid[13] - grid[11]) + (grid[18] - grid[16]);
    float sobel_v = (grid[16] - grid[6]) + 2.0 * (grid[17] - grid[7]) + (grid[18] - grid[8]);
    float edge = abs(sobel_h) + abs(sobel_v);
    float edge_factor = clamp(1.0 - edge * EDGE_MULTIPLIER, 0.0, 1.0);

    // Suppress sharpening in high-detail areas
    float detail_saturation = 1.0 - smoothstep(DETAIL_LOW, DETAIL_HIGH, window_variance);

    // Combine influences
    float combined = edge_factor * final_grain * final_texture * detail_saturation;
    float sharpen_amount = t_sharp * tc_strength * combined;

    // -----------------------
    // Mild-detail boost (applied BEFORE black/white mask)
    // -----------------------
    // Goal: lift the sharpening a little more in MILD / low-amplitude texture, while
    // leaving already-sharp (high-variance) detail untouched. The boost rides on top of
    // sharpen_amount, which the texture/detail gates have already zeroed in flat areas,
    // so this only acts where there is some texture to begin with.
    // NOTE: keyed on window_variance alone, so it cannot distinguish mild distributed
    // texture from sparse isolated detail at the same low variance — see audit notes.
    float lowdetail_gain = 1.0 - smoothstep(0.0, DETAIL_LOW, window_variance);
    // Concave (square-root) curve emphasizes the milder end of the range.
    // sqrt(x) == pow(x, 0.5); the guard keeps the input non-negative.
    lowdetail_gain = sqrt(max(lowdetail_gain, 0.0));
    // Map to a multiplier between 1.0 and LOWDETAIL_MAX_BOOST
    float lowdetail_boost = mix(1.0, LOWDETAIL_MAX_BOOST, lowdetail_gain);

    sharpen_amount *= lowdetail_boost;
    // -----------------------
    // End cinematic boost
    // -----------------------

    // Black/white luminance masks
    float mask = smoothstep(black_threshold, black_threshold + black_falloff, p.x) *
                 (1.0 - smoothstep(white_threshold - white_falloff, white_threshold, p.x));

    sharpen_amount *= mask;

    // Clamp final brightness changes
    float result = clamp(p.x + sharpen_amount, p.x - tc_max_delta, p.x + tc_max_delta);

    return vec4(result, 0.0, 0.0, 1.0);
}
