// Small-template (TPL) architecture since 2026-07-10: grain is generated into
// a 960x540 toroidal vocabulary and assembled in normalized active-picture
// space from per-block randomized template windows, AV1-FGS style. The current
// 2160-sample picture-height lattice is an implementation bandwidth/calibration
// choice, not a film format or a "grain resolution": source-matched correlation
// length stays picture-relative at every source/output resolution. This file is
// CANONICAL and hand-maintained;
// the pre-TPL full-field A/B build is archived outside the public release repo.
// Copyright (C) 2026 Ágúst Ari
// Licensed under GPL-3.0 — see LICENSE
//
// Film Grain — MATCH+ — complementary grain remastering.
// ============================================================================
// Learns a persistent title grain model from the best stochastic evidence that
// survives delivery, commits a sensitivity-aware presentation at shot cuts, and
// restores per-luma power that compression or finishing erased. Film, sensors and
// animation masters all carry acquisition/finishing noise: weak measurement shifts
// weight toward the title prior, never toward a fictitious noiseless master.
//
// What is automatic is the AMOUNT: per-luma grain power. The grain's character
// (size, softness, hardness, value contrast, colour noise) comes from the look
// knobs and is never measured -- delivery encodes destroy it. The observer is
// hidden scratch. It measures one picture-relative grain band in eight
// exposure-weighted luma zones, temporal authenticity, coverage, motion and cut
// distance; a cell whose content moved is left out of that frame's reading.
// A tone that has not yet earned authority learns its level fast, but only for
// the first few evidence units of each shot, so speed follows the number of
// shots that show grain (a grainy title is matched within minutes; a one-shot
// noise effect stays small); earned authority stays slow. The visible shot
// model may adapt quickly only in the short perceptual window after a real
// cut; inside a shot all upward change is deliberately slow, and frames whose
// flat areas mostly moved count less in both directions. Independent source
// and synthetic powers combine in quadrature over the untouched source.
//
// Architecture (LUMA measure + source-locked template gen, OUTPUT composite):
//   PASS 1 - Compute 32x32 at LUMA, measurement only: saves to a 1x1 dummy
//            (WIDTH/HEIGHT 1 -> a single workgroup; the LUMA plane itself is
//            untouched -- the old full-plane passthrough copy was the shader's
//            entire measurable GPU cost at 4K). Samples HOOKED.r across the
//            source raster with a fixed cut/matte probe, remaps the grain
//            observer into the committed active picture, and writes only
//            GRAIN_STATE.
//   PASS 2 - Compute 32x32 at LUMA: regenerates the persistent 960x540 grain
//            vocabulary on every visible tick (and on a look edit); between
//            ticks it is retained and PASS 3 repeats the arrangement.
//   PASS 3 - Compute 32x32 at OUTPUT: assembles the picture-space field from
//            randomized template windows and composites it only
//            inside the committed active picture.
//
// Runtime params. Every param is DYNAMIC and tuned live through glsl-shader-opts
// (shampv: match_grain / debug_match are its toggles); the defaults below are the
// calibrated values. The param blocks are ordered to match these groups. (Comments
// cannot sit between the param blocks -- the parser rejects it -- so every param is
// documented HERE.)
//
//  == CONTROL ===============================================================
//   match_grain      0 = no synthetic output, 1 = Match Grain+; observation
//                    continues so live A/B does not cold-start. Between 0 and 1
//                    it is a pure output mix: the template does not change.
//   debug_match      compact 52-row machine-readable posterior/geometry overlay.
//   state_epoch      persisted-state token, two parts. mod 4096 = the title
//                    or series: any change is a cold start (bump by 1 per file
//                    for a cold start per file). floor(x / 4096) = the file in
//                    that series: a change of that part alone carries the
//                    title's learned grain into the next episode at a quarter
//                    of its authority. Machine-owned (shampv epoch-param).
//   grain_pause      machine-owned pause input; freezes temporal state; baked
//                    look edits may rebuild the standing field in place.
//
//  == GRAIN LOOK (the dials to tune by eye) =================================
//   grain_gain       overall grain AMOUNT/strength (1 = calibrated; up to 12).
//   grain_size       grain SIZE: <1 finer, >1 coarser (1 = the calibrated
//                    neutral scale; 1.34 = default, the restoration scan
//                    character). Phase 4 folded the old grain_sharpness size
//                    trim in: old size s at sharpness p is now s x (1 + 0.387 p).
//                    The 0.25 floor is dyadic on purpose: mpv rejects an opt
//                    that sits exactly on a bound float can't represent (0.3
//                    did), and the kernels are already floored there.
//   grain_contrast   spectral hardness: 0 = soft/lowpass, 1 = sandpaper bandpass, up to 2 =
//                    more DC removed / peppery (difference-of-Gaussians).
//   value_warp       VALUE-domain contrast: 0 = Gaussian (bit-identical), ~2 hard, ~3 extreme
//                    = bimodal/high-per-grain-contrast (CyberCity "harsh"). Amplitude-
//                    preserving; the value-domain cousin of grain_contrast.
//   grain_soften     sub-pixel CAPTURE softness in lattice samples (picture-
//                    height units, a title property like grain_size): a common
//                    scan-aperture/optical MTF folded into every channel's
//                    kernel. 0 = point-sampled lattice (legacy); 0.29 = the
//                    minimal one-sample scan aperture (default); 1-2.5 = softer
//                    scan/optics, up to smooth Gaussian-like grain at
//                    value_warp 0 (the warp's tanh flattens soft blobs into
//                    mesas). Softens the red (finest) channel most; grain_size
//                    would coarsen all three and its G/B kernels already sit on
//                    the SIGMA_MAX cap above ~1.5. Floors the effective sigma
//                    under small grain_size.
//                    SIZE, CONTRAST AND SOFTEN ARE AMOUNT-NORMALIZED (Phase 4):
//                    each keeps the grain's RMS after a sigma-1.5-sample
//                    visibility blur at the default kernel's, so grain_gain
//                    sets the amount. Before Phase 4 they held RAW RMS, and
//                    soft or coarse grain read up to ~1.65x stronger on that
//                    model. The blur is a visibility MODEL, not a measured
//                    match to the eye (a sitting calibrates it), and very fine
//                    grain is capped (see VIS_GAIN_MAX): below ~size 0.85 it
//                    reads progressively weaker at the same gain.
//   grain_rate       visible temporal cadence: fraction of SOURCE frames that
//                    choose a fresh on-screen arrangement (1 = on ones; 0.5 =
//                    on twos). Source-locked and display-refresh independent.
//                    The 0.0625 floor is dyadic for the same reason as size's.
//   grain_base_sat   colour noise of the grain (per-channel independence).
//                    0 = one shared noise -- not mono: the per-channel kernel
//                    sizes still leave fine colour speckle (R-B sigma ~0.46x a
//                    channel's at the default size); 0.75 = calibrated look;
//                    ~1.36 = red and blue
//                    fully independent, like separate dye layers; up to 2 =
//                    exaggerated colour noise beyond real film, for outlier
//                    sources. The luma amount holds within 2% across the whole
//                    range; only the colour noise grows (x1.35 at 1, x2.7 at 2).
//
//  == RESTORATION (how much grain to rebuild on degraded sources) ===========
//   restore_gain     missing-power lane only: 0 = character complement C,
//                    1 = inferred target, >1 = explicit amplitude override. The
//                    restored AMPLITUDE scales linearly with it (power with its
//                    square) at every value;
//                    values near 5 are aggressive shot-specific overrides,
//                    not a preset; 6 is the expert ceiling.
//   grain_extreme    per-title admission scale for grain HEAVIER than the
//                    normal film model (the plausibility band rejects it as
//                    fireworks/damage otherwise). 1 = exactly neutral; ~2 for
//                    the sandpaper OVA class whose measured grain sits at
//                    4-8x normal; set from a profile, not a look knob. It
//                    widens the plausibility band and raises the learned-
//                    amount ceilings (the title level may reach 3.24 x its
//                    square x the clean level). Since 5B it no longer scales
//                    the stillness test: the titles that test starved
//                    (Utena, Gunbuster) are credited at 1, and 2 moves them
//                    by only 5-7% (replay, 2026-09-30).
//   grain_floor      the engine's built-in MINIMUM added grain -- what a title
//                    with no grain evidence (a clean title) gets -- as a level:
//                    1 = as calibrated (default, exact), 0.5 = half, 0 = no
//                    added grain on clean titles, 1.5 = +50%, 2 = double.
//                    Titles the engine recognises as grainy already add more
//                    (~1.7-7x the clean amount once learned: Kuroneko 1.7,
//                    Cyber City 4.4, Utena 7.1). Raising only lifts titles
//                    below the new
//                    level (so above ~1.7 it reaches those titles too);
//                    lowering only reduces titles at the minimum and eases out
//                    by 1.5x it, so a
//                    title rising through that band while the engine learns it
//                    never steps. One factor per title: the tone shape is kept.
//                    Titles whose grain the engine does not credit sit at the
//                    minimum and follow the knob like clean titles, as does
//                    every title before its first grainy shots are learned
//                    (the first minutes of a grainy title). restore_gain
//                    and grain_gain stay
//                    relative trims on top.
//
//  == PIPELINE / SIZING =====================================================
//   density_combine  0 = additive, 1 = multiplicative density, between = linear
//                    blend of the two deltas (same field, RMS holds).
//
//  == OUTPUT CHAIN (what the OUTPUT hook is handed) =========================
//   grain_hdr        the player's OUTPUT transfer, NOT a look knob. libplacebo
//                    runs OUTPUT hooks AFTER the conversion to the target
//                    colorspace, so this pass receives target-space codes: set
//                    1 whenever mpv's target-trc resolves to pq (an explicit
//                    target-trc=pq, or an HDR-signalled display under
//                    target-colorspace-hint), else 0. It is independent of
//                    which shaders precede us. Wrong value = the model is
//                    measured on gamma SDR source codes at LUMA but keyed and
//                    applied onto PQ codes here: the highlight fade and black
//                    gate both go inert and grain lands off its tone bell.
//   grain_headroom   whether the CONTENT extends above reference white, as
//                    opposed to grain_hdr which describes the container. Only
//                    read when grain_hdr = 1. 1 = an upstream SDR->HDR stage
//                    (CelFlare) expands highlights past ref white: work-domain
//                    1.0 is paper white with real detail above, so grain fades
//                    just ABOVE it. 0 = plain SDR carried in a PQ container:
//                    work-domain 1.0 IS the source clip ceiling, so the SDR
//                    clip fade and the near-clip channel clamp apply exactly
//                    as on an SDR target. Wrong value = grain in clipped
//                    whites (1 on SDR content) or grain shaved off real
//                    expanded highlights (0 under CelFlare).
//   grain_fade       where grain fades to white: the work-domain luma at
//                    which grain reaches zero. ONE knob for every chain --
//                    below 0.5 it also widens the rise out of black; between
//                    that toe and the upper fade, amount follows the title's
//                    own rendition curve (no aesthetic shoulder). The approach
//                    into the fade is sized in stops: through the content
//                    range the glide spans ~1.8 stops below the zero point
//                    (start = 0.60 x top), tightening to the stock half-stop
//                    band as the top nears white -- a low fade rolls off
//                    gradually instead of switching off. Clip-limited
//                    chains (grain_hdr = 0, or grain_headroom = 0) cap the
//                    effective top at 0.95 (the near-clip dead zone), so
//                    the 1.10 default is stock on every chain; with
//                    headroom it is the above-ref-white reach -- we cannot
//                    know the upstream expansion's tuning, so match it by
//                    eye, per source if needed. Below ref white the knob
//                    moves the LUMA fade only: the per-channel grain bound
//                    stays floored at ref white (overshoot physics), so
//                    bright saturated channels keep their grain. Values near
//                    0.2-0.3 aggressively confine grain to low luminance.
//   grain_source_trc the SOURCE transfer the LUMA observer reads: 0 = SDR
//                    gamma (every SDR source, and SDR retagged for CelFlare),
//                    1 = PQ (native HDR10 / PQ sources). libplacebo does not
//                    tell hooks the source transfer, so the profile that routes
//                    native-HDR sources sets it. At 1 the observer bridges each
//                    PQ luma sample to the SDR-equivalent code at
//                    grain_ref_white before any statistic, so native HDR is
//                    measured in the domain the whole model is calibrated in
//                    (raw PQ codes under-read grain ~1.9x at 10 nits, ~3.1x at
//                    50 nits vs ref white 116). HLG and Dolby Vision profile 5
//                    (IPT) are not PQ luma: leave them at 0 (unbridged); DV P7/P8
//                    base layers are plain PQ (1). The observer assumes LIMITED-
//                    range luma (black ~16/255) -- true for disc, broadcast,
//                    streaming and the AnimeJaNai upscaler output; full-range
//                    video (yuvj, some screen/phone captures) mis-keys the tone
//                    bins by up to ~1.5 bins at black.
//                    shampv still lints this file as input sdr until it can sync
//                    grain_source_trc from the source transfer itself: set it in
//                    the profile that routes native-HDR sources.
//   grain_ref_white  the nit level the chain anchors SDR white to; the bridge
//                    divides by it, so it is DESCRIPTIVE -- it must equal what
//                    the chain actually did, not what we would prefer. Plain
//                    mpv PQ output anchors at hdr-reference-white, which is
//                    "auto" by default = libplacebo's BT.2408 203 nits (hence
//                    the 203 default; measured 202.4 on this chain). With
//                    grain_source_trc = 1 it is ALSO the measure anchor: the
//                    observer reads native PQ as SDR codes against this white,
//                    so a wrong value rescales measured grain against the
//                    absolute evidence constants (203 vs 116 reads sigma x0.79),
//                    not just the keying. Native PQ source -> PQ output has no
//                    chain anchor: it is a diffuse-white choice, and measure +
//                    apply stay self-consistent for any value; pin it to the
//                    SDR chain's white (116 here) so the SDR-calibrated
//                    constants land on the same nits. PQ source -> SDR output
//                    must match libplacebo's SDR white. shampv
//                    syncs this from hdr-reference-white ONLY while that is
//                    pinned numeric -- so PIN IT, and any upstream SDR->PQ
//                    encoder's own reference white gets pinned to the same
//                    number and the whole chain stays coherent. If an upstream
//                    shader owns the SDR->PQ encode and you leave
//                    hdr-reference-white on auto, nothing syncs: match that
//                    shader's reference white here by hand or grain keys off
//                    the wrong point on the bell. Measured invariant: a wrong
//                    ref white is a pure KEYING error, never a colour error --
//                    the bridge is a self-consistent inverse pair for any
//                    value, round-tripping within 1 LSB.
//                    Three standing limits of the bridge, all verified by
//                    measurement and all fine under the author's pinned config
//                    (target-prim=bt.2020, bt709 sources, target-peak 1000):
//                    (a) the 2020->709 matrix is HARDCODED, so it assumes
//                    target-prim=bt.2020 -- under target-prim=display-p3 (and
//                    target-prim defaults to auto) the matrix is simply wrong;
//                    (b) the 2.4 inverse assumes a bt709/bt1886-tagged source
//                    (exact there; an sRGB-tagged source mis-keys ~+5.7% at
//                    code 0.5, ~+25% at 0.06); (c) the anchor holds only while
//                    frame peak stays under target-peak -- above it libplacebo's
//                    spline tone map engages and shifts what we are keying off.
// ============================================================================

// shampv shader API (plain comments to libplacebo). All params are DYNAMIC:
// glsl-shader-opts changes apply next frame, no recompile; bump state_epoch
// to invalidate the persisted GRAIN_STATE live.
//@shampv input sdr
//@shampv ref-white-param grain_ref_white
//@shampv target-trc-param grain_hdr
//@shampv pause-param grain_pause
//@shampv epoch-param state_epoch
//@shampv toggle match_grain grain_hdr grain_headroom debug_match grain_pause
//@shampv choice grain_source_trc gamma pq
//@shampv step grain_gain 0.05
//@shampv step density_combine 0.05
//@shampv step grain_floor 0.05
//@shampv step grain_soften 0.05
//@shampv measures LUMA

//!PARAM match_grain
//!DESC Match Grain+ mix (shampv A/B toggle). 1 = complementary remastering · 0 = no synthetic output while observation remains live · intermediate mix values stay valid via opts.
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 1.0
1.0

//!PARAM debug_match
//!DESC Debug overlay (toggle). 1 = compact 52-row Match Grain+ posterior/geometry readout · 0 = normal output.
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.0

//!PARAM state_epoch
//!DESC Persisted-state token. mod 4096 = the title or series: any change wipes the saved grain state (bump by 1 per file for a cold start per file). floor(x / 4096) = the file within that series: a change of that part alone carries the title's learned grain into the next episode at a quarter of its authority.
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 65535.0
0.0

//!PARAM grain_pause
//!DESC Machine-owned pause mirror — 1 freezes observation, cadence and arrangement so paused redraws are bit-stable. shampv writes it from mpv pause.
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.0

//!PARAM grain_gain
//!DESC Overall grain amount. ↑ stronger / more visible · ↓ fainter, 0 = none. Default 1 = calibrated restoration; >1 is an artistic/evaluation override.
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 12.0
1.0

//!PARAM grain_size
//!DESC Grain cell size. ↓ finer · ↑ coarser. Default 1.34 = restoration scan character (the old 1.2 at grain_sharpness 0.3, now folded in); 1 = neutral scale. Amount-normalized: the grain's visible strength stays about the same as size changes (very fine sizes read a little weaker).
//!TYPE DYNAMIC float
//!MINIMUM 0.25
//!MAXIMUM 2.5
1.34

//!PARAM grain_contrast
//!DESC Spectral hardness (difference-of-Gaussians). ↓ toward 0 = soft/lowpass · ↑ toward 2 = crisper grain edges / more DC stripped. Default 2. Amount-normalized: visible strength stays about the same.
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 2.0
2.0

//!PARAM value_warp
//!DESC Value-domain contrast, amplitude-preserving. 0 = Gaussian (bit-identical) · ↑ ~2 = hard, ~3 = bimodal/harsh. The amplitude-domain cousin of grain_contrast.
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 4.0
0.0

//!PARAM grain_soften
//!DESC Capture softness in grain-lattice samples (1/2160 of active picture height): sigma of a common scan-aperture/optical MTF folded into every channel's kernel, a title property like grain_size. 0 = point-sampled lattice · 0.29 = minimal one-sample scan aperture (default) · 1-2.5 = softer scan/optics, up to smooth Gaussian-like grain (at value_warp 0). Amount-normalized: visible strength stays about the same (grain_gain sets it). Also floors the effective sigma under small grain_size.
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 2.5
0.29

//!PARAM grain_rate
//!DESC Visible arrangement cadence in SOURCE frames. 1 = fresh arrangement every frame / on ones · 0.5 = on twos. ↓ slows the boil. Display-refresh independent. 0.333 ≈ on-threes.
//!TYPE DYNAMIC float
//!MINIMUM 0.0625
//!MAXIMUM 1.0
1.0

//!PARAM grain_base_sat
//!DESC Colour noise of the grain. 0 = one shared noise (fine colour speckle remains from the per-channel sizes) · 0.75 = calibrated · ~1.36 = channels fully independent (dye layers) · up to 2 = exaggerated colour noise for outlier sources. The luma amount stays the same; only the colour noise grows. Explicit prior character, not source-measured chroma.
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 2.0
0.75

//!PARAM restore_gain
//!DESC Missing-power authority. 0 = complement only · 1 = inferred missing-power target · restored amplitude scales linearly with it (power with its square); high values can overgrain intact material · 6 = expert ceiling.
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 6.0
1.0

//!PARAM grain_floor
//!DESC Level of the built-in minimum grain (what a clean title gets). 1 = as calibrated · 0.5 = half · 0 = none on clean titles · 1.5 = +50% · 2 = double. Raising lifts only titles adding less than the chosen level; lowering reduces only titles near the minimum (eased out by 1.5x). Grainy titles the engine does not credit read as clean.
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 2.0
1.0

//!PARAM density_combine
//!DESC Grain combine mix. 1 = multiplicative density, rides the carrier like film (shipped) · 0 = additive · between = linear blend of the two deltas from the same field (carrier weighting, bright-biased skew and shadow floor interpolate; matched RMS holds).
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 1.0
1.0

//!PARAM grain_extreme
//!DESC Extreme-grain admission. 1 = normal film model (exactly neutral) · 2 = admit ~2x heavier grain (sandpaper OVA class) · above ~2.5 only for the heaviest scans. Scales the plausibility band and the evidence ceilings together.
//!TYPE DYNAMIC float
//!MINIMUM 1.0
//!MAXIMUM 4.0
1.0

//!PARAM grain_hdr
//!DESC Output transfer — match mpv's target-trc. 1 = PQ BT.2020 out: grain keyed/applied in SDR via a PQ bridge, fades above ref white · 0 = plain SDR (bit-identical).
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.0

//!PARAM grain_headroom
//!DESC Content headroom above ref white (grain_hdr = 1 only). 1 = upstream SDR→HDR expansion, grain fades just above ref white · 0 = SDR in a PQ container, ref white is the clip ceiling.
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 1.0
1.0

//!PARAM grain_fade
//!DESC Work-domain luma of full fade-out. 1.10 = stock top (clip-limited chains cap at 0.95) · 0.2-0.3 confines grain low · with grain_headroom = 1 it is the above-ref-white reach — by eye.
//!TYPE DYNAMIC float
//!MINIMUM 0.1875
//!MAXIMUM 2.5
1.10

//!PARAM grain_ref_white
//!DESC SDR reference white (nits) the output chain anchors to — match hdr-reference-white (203 = its auto anchor). Used when grain_hdr = 1, and by the PQ source bridge.
//!TYPE DYNAMIC float
//!MINIMUM 80.0
//!MAXIMUM 480.0
203.0

//!PARAM grain_source_trc
//!DESC Source transfer the observer reads (choice). 0 = gamma (all SDR, incl. SDR retagged for CelFlare) · 1 = PQ native HDR (HDR10/HDR10+, DV P7/P8 base layer): measure through a bridge to SDR-equivalent codes at grain_ref_white. HLG and DV P5 are not PQ luma: leave 0.
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 1.0
0.0

//!BUFFER GRAIN_STATE
//!VAR float m_observed
//!VAR float m_measured
//!VAR float m_prev_ready
//!VAR float m_state_magic
//!VAR float m_state_epoch
//!VAR float m_coverage
//!VAR float m_motion
//!VAR float m_cut_score
//!VAR float m_gen_frame
//!VAR float m_eff_render
//!VAR float m_title_power
//!VAR float m_temporal_support
//!VAR float m_evidence
//!VAR float m_ev_gate
//!VAR float m_auth_mean
//!VAR float m_acq_max
//!VAR float m_q_random
//!VAR float m_q_source
//!VAR float m_shot_age
//!VAR float m_shot_gain
//!VAR float m_shot_ev
//!VAR float m_shot_restore_boost
//!VAR float m_master_p[8]
//!VAR float m_master_w[8]
//!VAR float m_char_p[8]
//!VAR float m_restore_p[8]
//!VAR float m_arr_seed
//!VAR float m_regen
//!VAR float m_regen_pending
//!VAR float m_field_valid
//!VAR float m_field_cov_rg
//!VAR float m_field_cov_rb
//!VAR float m_field_cov_gb
//!VAR float m_field_var_r
//!VAR float m_field_var_g
//!VAR float m_field_var_b
//!VAR float m_baked_grain_size
//!VAR float m_baked_grain_contrast
//!VAR float m_baked_value_warp
//!VAR float m_baked_grain_base_sat
//!VAR float m_baked_grain_soften
//!VAR float prev_grid[4096]
//!VAR float prev_grid_off[4096]
//!VAR float prev_mean[4096]
//!VAR float m_source_aspect
//!VAR float m_active_inset_x
//!VAR float m_active_inset_y
//!VAR float m_pending_inset_x
//!VAR float m_pending_inset_y
//!VAR float m_geom_streak
//!VAR float m_geom_streak_y
//!VAR float m_geom_known
//!VAR float m_geom_known_y
//!VAR float m_geom_blackout
//!VAR float m_geom_blackout_y
//!VAR float m_geom_changed
//!VAR float m_geom_shrink_x
//!VAR float m_geom_shrink_y
//!VAR float m_geom_shrink_cand_x
//!VAR float m_geom_shrink_cand_y
//!VAR float prev_probe[4096]
//!VAR float m_pan_px
//!VAR float m_tpl_scale
//!STORAGE

//!TEXTURE GRAIN_FIELD
//!SIZE 960 540
//!FORMAT rgba16f
//!STORAGE

//!HOOK LUMA
//!BIND HOOKED
//!BIND GRAIN_STATE
//!SAVE GRAIN_STATS
//!WIDTH 1
//!HEIGHT 1
//!COMPUTE 32 32
//!DESC Film Grain Match: LUMA measure

#define AMP_BINS               32
#define MP_GRID_W              64
#define MP_GRID_H              64
#define MP_GRID_N              (MP_GRID_W * MP_GRID_H)
#define MP_TONE_BINS           8
// Log-spaced amplitude histograms (5B, audit lane A change 2). Bin 0 holds
// |values| below MP_LOG_LO and reads as exact zero, so mathematically flat
// input still cannot manufacture evidence. Bins 1..30 span MP_LOG_LO..1.6e-2
// at 2^(1/MP_LOG_BPO) = ~21% per bin; bin 31 is the overflow (>= 1.6e-2, read
// as one more 21% bin -- above the plausibility band at every grain_extreme
// <= 4, so it only ever lowers evidence). The old linear
// bins made a per-bin sigma either exactly 0 or >= 0.664e-3 -- the prior
// 0.55e-3 could not be represented -- and the temporal range clamped at
// 3.75e-3 x grain_extreme, below the plausibility band, so heavy grain failed
// its randomness test (Cyber City per-bin temporal ratios 0.46-0.64 clamped,
// 0.95-0.98 unclamped; audit 2026-09-30).
#define MP_LOG_LO              5.0e-5
#define MP_LOG_BPO             3.6049338
// Per-cell stillness (5B, audit lane A change 1): a cell whose local 9-tap
// mean moved more than this since the previous frame is left out of this
// frame's grain statistics. The mean averages grain away (~3x), so the test
// sees content motion, not the grain's own frame-to-frame swing -- the old
// single-point test tripped on coarse grain at 2 sigma (Utena, Cyber City)
// and read heavy grain low when used per cell.
#define MP_STILL_MEAN          0.018
#define MP_HP_TO_SOURCE        1.7888544
#define MP_MEDABS_TO_STD       1.4826022
#define MP_COMPLEMENT_POWER    0.150
// Normalized picture-space calibration density. Distances below are physical
// fractions of active picture height expressed on this finite synthesis
// lattice. Raising output resolution reveals the same grain more faithfully;
// it does not make the source's grains smaller or redefine their identity.
#define MP_PICTURE_DENSITY     2160.0
// MUST equal PICTURE_DENSITY / PICTURE_DENSITY_OUT in PASS 2 / PASS 3.
// These are separate translation units and have no compile-time cross-check.
// Restoration bed: the share of the missing-power model that is presented,
// constant since 5B (2026-09-30). The per-shot survivor bed it replaces (the
// surviving delivered grain discounted at fidelity 0.5, plus a title EMA of
// it) sat at its floor on 11 of 12 audited titles (median 0.51-0.61) while its
// per-shot swings made ~90% of the churn at cuts; a constant 0.55 moves
// converged levels by <= +-9% and cuts the p95 cut step by 25-97% (audit lane
// B, replay of 18 full episodes). An even bed of restoration-grade grain
// across the picture remains the intent (author spec 2026-07-17).
#define MP_BED                 0.55
#define MP_PRIOR_SIGMA         0.00055
// Effective midtone output RMS of a unit control after density application and
// the measured tone basis.
#define MP_FIELD_STD           0.0185
#define MP_STATE_MAGIC         0.956340
#define MP_MIN_BIN_SAMPLES     24u
// Film-plausible evidence band. Per-frame sigma above this band is not
// photographic grain (fireworks, confetti, dense near-field rain, damage):
// soft-reject it from BOTH the per-bin master update and shot refinement,
// upstream of every lane, so implausible evidence cannot enter the
// persistent posterior at all. Calibrated ABOVE the heaviest catalogued
// legitimate grain — Golden Spurtle Super35 reads sigma 0.0026-0.0035 in
// this domain (harness, 2026-07-17) and must pass at full weight; in-band
// grain twins are bounded by the rate/breadth/reversibility layers instead,
// a level test cannot catch them. The absolute master ceiling backstops
// sustained band-edge evidence.
#define MP_SIGMA_PLAUS_LO      0.0040
#define MP_SIGMA_PLAUS_HI      0.0065
#define MP_MASTER_P_MAX        2.0e-5
#define MP_BAR_DARK_MAX        0.085
#define MP_BAR_RANGE_MAX       0.025
#define MP_BAR_DARK_SAMPLES    36u
#define MP_BAR_MIN_CELLS       2
#define MP_BAR_SIGNAL_MIN      32u
#define MP_BAR_PICTURE_RANGE   0.05
#define MP_BAR_EDGE_PICTURE    48u
#define MP_BAR_LEVEL_MAX       0.008
#define MP_ACTIVE_INSET_MAX    0.24
#define MP_BLACKOUT_CODE_MAX   0.075
#define MP_BLACKOUT_SIGNAL_MAX 8u
#define MP_BLACKOUT_LATCH_MAX  4.0
#define MP_GEOM_X_BOOTSTRAP    24.0
#define MP_GEOM_Y_BOOTSTRAP    12.0
// Release of a too-large committed bar (5A, 2026-09-30). Dark and fade-in
// frames make picture rows next to a bar read as matte, and before this path
// only a hard cut could undo such a commit (audit A: The Thin Red Line held a
// 0.198 bar over a true 0.119 for 74 s after its dark opening, Utena 0.1455 on
// 4:3 content for 13 s after a black fade, Gunbuster 14 px per side through a
// sub-cell refine error). Picture inside a bar is strong evidence: dark picture
// reads as matte on every dark frame, while a matte reads as picture only when
// bright content sits in the bar (hardsubs, logos, credits crossing the bars).
// So a CLEAN candidate (symmetric, picture beside both bars -- one-bar
// subtitles fail it) at least 1.5 refine steps (1/8 cell each) smaller than
// the committed bar, holding steady within one refine step for
// MP_GEOM_RELEASE frames, re-commits at the candidate (moving in-bar content
// restarts the count, and so does a hard cut). Nothing grows a bar except the
// existing cut/blackout/bootstrap paths.
#define MP_GEOM_RELEASE        12.0
#define MP_GEOM_SHRINK_MIN     (1.5 / 512.0)
// S4 evidence veto -- global-translation (pan/shake) gate. A translating
// texture is a per-frame grain twin: it decorrelates band0 while the
// absolute stillness threshold stays blind on dark content and q_random's
// innovation/spatial ratio lands in the grain band (measured: Odyssey
// 160-330 s, m_motion 0.0 throughout the shake, eff 2.64x staircase). A
// grid-level single-step Lucas-Kanade translation estimate is the tell:
// grain decorrelates across grid neighbours so its g*d products average
// to ~0 over the lattice, while a coherent camera translation correlates
// (proven lineage: the old build's pan-freeze gate). Magnitude is EMA'd,
// so alternating-direction SHAKE holds it elevated like a sustained pan.
// The veto only REDUCES learning authority (rise lanes); down-reads stay
// live because translation decorrelation can only INFLATE sigma -- a
// sub-master read under motion remains a valid one-sided bound. PAN_LO/HI
// are in picture-lattice samples/frame and are calibrated on the PC harness
// decode at MP_PICTURE_DENSITY
// (row 51): they must sit above the static-grain floor read on ground
// truth (Golden Spurtle) so legitimate grain never freezes.
#define MP_LK_SCALE            2.0e5
#define MP_LK_CLAMP            1.0e6
#define MP_PAN_LO              0.35
#define MP_PAN_HI              1.00

// Flattened for conservative SPIRV-Cross/D3D11 lowering.
shared uint s_hist[MP_TONE_BINS * AMP_BINS];
shared uint s_content_hist[AMP_BINS];
shared uint s_luma_now[MP_TONE_BINS];
shared uint s_luma_prev[MP_TONE_BINS];
shared float s_probe[MP_GRID_N];
shared uint s_row_dark[MP_GRID_H];
shared uint s_col_dark[MP_GRID_W];
shared float s_row_range[MP_GRID_H];
shared float s_col_range[MP_GRID_W];
shared float s_row_level[MP_GRID_H];
shared float s_col_level[MP_GRID_W];
shared float s_refine_probe[512];
shared uint s_state_ok;
shared uint s_prev_ready;
shared uint s_raster_changed;
shared uint s_new_file;
shared uint s_picture_signal;
shared uint s_raster_signal;
shared uint s_probe_count;
shared uint s_probe_changed;
shared float s_probe_hist_l1;
shared uint s_probe_hard_cut;
shared uint s_history_ready;
shared float s_active_inset_x;
shared float s_active_inset_y;
shared float s_candidate_inset_x;
shared float s_candidate_inset_y;
shared uint s_candidate_valid_x;
shared uint s_candidate_valid_y;
shared uint s_candidate_immediate_x;
shared uint s_candidate_immediate_y;
shared uint s_candidate_clean_x;
shared uint s_candidate_clean_y;
shared uint s_scan_x0;
shared uint s_scan_x1;
shared uint s_scan_y0;
shared uint s_scan_y1;
shared float s_scan_inset_x;
shared float s_scan_inset_y;
shared uint s_valid_count;
shared uint s_flat_count;
shared uint s_changed_count;
shared uint s_lk_gxx;
shared uint s_lk_gyy;
shared uint s_lk_gxy_p;
shared uint s_lk_gxy_n;
shared uint s_lk_bx_p;
shared uint s_lk_bx_n;
shared uint s_lk_by_p;
shared uint s_lk_by_n;

float measure_luma(vec2 uv) {
    return HOOKED_tex(uv).r;
}

// Limited-range luma code span. The observer runs on raw LUMA-plane codes, which
// are limited range for every source this chain sees (plain decode: verified
// 2026-07-29; the AnimeJaNai upscaler output is tagged yuv444p16 limited,
// 2026-09-29). OUTPUT maps its full-range luma into this same coordinate before
// reading the tone bins: MEASURE_BLACK_OUT/WHITE_OUT there MUST equal these.
#define MEASURE_BLACK (16.0 / 255.0)
#define MEASURE_WHITE (235.0 / 255.0)

// Native-PQ bridge (grain_source_trc = 1): limited PQ code -> full-range PQ ->
// nits -> SDR-equivalent BT.1886 2.4 code at grain_ref_white -> back into the
// limited coordinate. Black maps to black and ref white to limited white. The
// result is clamped at the SDR container ceiling (1.0): SDR LUMA codes never
// exceed it, and the gates that are not behind the flat test (the changed-cell
// count, the LK pan terms, the cut probe) must not see
// expanded highlights at 2-5x their SDR range; flat_ok (c < 0.985) already kept
// those cells out of the estimator, so the clamp costs no evidence.
// Luma-only: Y' is treated as a PQ-coded luminance -- exact on neutrals;
// skin/sky/foliage within ~0.02 bin and ~6% sigma; saturated 709 primaries bin
// +0.06..0.26 brighter than OUTPUT keys them (review 2026-09-29). Same ST 2084
// constants as the OUTPUT bridge. 10-bit sources normalize black/white to
// 64/1023 and 940/1023; these 8-bit constants sit ~0.7% off at ref white,
// negligible for a grain amplitude. One pow is folded into a uniform factor:
// (10000 r^(1/m1) / W)^(1/2.4) = (10000/W)^(1/2.4) * r^(1/(2.4 m1)).
// Call sites apply it in GROUPS under one uniform branch: FXC flattens a
// single-call conditional into a select, which would make every d3d11 frame
// pay the pow chain even at grain_source_trc = 0 (review 2026-09-29).
float measure_bridge(float v) {
    float e = clamp((v - MEASURE_BLACK) / (MEASURE_WHITE - MEASURE_BLACK), 0.0, 1.0);
    float p = pow(e, 1.0 / 78.84375);
    float r = max(p - 0.8359375, 0.0) / (18.8515625 - 18.6875 * p);
    float sdr = pow(10000.0 / max(grain_ref_white, 1.0), 1.0 / 2.4)
              * pow(r, 1.0 / (2.4 * 0.1593017578125));
    return min(MEASURE_BLACK + sdr * (MEASURE_WHITE - MEASURE_BLACK), 1.0);
}

// PICTURE-RELATIVE SAMPLE FOOTPRINT (2026-09-29). The lattice points fall between
// pixels, so each tap is a hardware bilinear blend of the neighbouring raster
// pixels -- a footprint fixed in PIXELS. On the 1080-line rasters every evidence
// constant was calibrated on, that is one footprint; on a 2160-line raster (native
// 4K, or a 2x upscale such as AnimeJaNai ahead of this hook) the same blend covers
// a quarter of the picture area, so grain reads stronger (live chain: Utena x2.16,
// The Thin Red Line x2.00; a pixel-duplicated 2x twin x1.22, a lanczos 2x twin
// x1.61) and grainy titles fall out of the plausibility band. Taller rasters
// therefore sample what a 1080-line raster of the same picture would give: the
// bilinear of a virtual 1080-line raster whose cells are box averages. At an
// integer 2x each cell centre sits on a real pixel corner, so one hardware tap per
// cell IS the 2x2 box; four cells combine with the virtual bilinear weights
// (exact box-downsample-then-bilinear). At <= 1080 lines the loop runs once as
// the plain native tap, bit-identical to HEAD. The trip count is a uniform int,
// so FXC keeps a real loop instead of flattening the 4-tap path into every frame.
float grain_sample(vec2 uv, vec2 vsize, int ntaps) {
    vec2 P = uv * vsize - 0.5;
    vec2 i0 = floor(P);
    vec2 f = P - i0;
    float s = 0.0;
    for (int t = 0; t < ntaps; t++) {
        vec2 sel = vec2(float(t & 1), float((t >> 1) & 1));
        vec2 w2 = mix(vec2(1.0) - f, f, sel);
        float w = (ntaps > 1) ? w2.x * w2.y : 1.0;
        vec2 tuv = (ntaps > 1) ? (i0 + sel + 0.5) / vsize : uv;
        s += w * measure_luma(tuv);
    }
    return s;
}

int amp_bin(float a) {
    if (a < MP_LOG_LO) return 0;
    return clamp(1 + int(log2(a / MP_LOG_LO) * MP_LOG_BPO), 1, AMP_BINS - 1);
}

// Median of one log-spaced histogram, interpolated inside its bin in the log
// domain. A bin-0 median (below MP_LOG_LO) is exact zero.
float amp_median(int med_bin, uint target, uint acc_before, uint in_bin) {
    if (med_bin == 0) return 0.0;
    float frac = clamp((float(target) - float(acc_before))
                       / max(float(in_bin), 1.0), 0.0, 1.0);
    return MP_LOG_LO * exp2((float(med_bin - 1) + frac) / MP_LOG_BPO);
}

int tone_bin(float y) {
    return clamp(int(sqrt(clamp(y, 0.0, 1.0)) * float(MP_TONE_BINS)),
                 0, MP_TONE_BINS - 1);
}

float prior_tone_shape(int b) {
    float q = (float(b) + 0.5) / float(MP_TONE_BINS);
    float y = q * q;
    float shadow = mix(0.78, 1.0, smoothstep(0.0, 0.22, y));
    float highlight = 1.0 - 0.55 * smoothstep(0.72, 1.0, y);
    return shadow * highlight;
}

// Fraction of the master's grain POWER presumed erased by the delivery
// encode. Recalibrated 2026-08-20 to the paired-encode censoring audit
// (film corpus + anime ladder + an official streaming AVC/HEVC/AV1
// set): picture-wide missing power measures ~0.39 at remux/UHD-BD
// tier, 0.55-0.75 in AVC 5M film mids, ~0 on modern digital anime AVC
// deliveries — against the old 0.80-0.92 profile. Because the restore
// target multiplies this by the bed (MP_BED 0.55, the level the old
// optimistic flat-cell survivor bed typically sat at -- flats are where
// censoring is weakest), the end-to-end exact value is tier-dependent: ~0.46 at
// remux/UHD-BD, ~0.7-0.9 at AVC 5M film. This profile sits at the
// remux-exact end. The function below is the legacy film-exact CEILING
// (author-validated on heavy-grain titles); MP_MISSING_FLOOR_SCALE
// scales it to the recalibrated clean/remux-exact FLOOR (the two
// profiles are exactly proportional), and the evidence key at the
// restore target blends between them per bin. Accepted under-restored
// cells: deliveries whose censoring erased the evidence itself —
// low-bitrate AV1 and streaming HEVC/AV1 rungs (46-83% censoring vs
// their AVC sibling) read as low-evidence and stay at the floor. The
// tone slope is retained from the legacy profile; per-bin shape
// evidence is thin and class-contradictory (film brights censor
// hardest, anime brights survive AVC) and supports no recalibrated
// shape. Recovering the erased-evidence cells needs a delivery-health
// keying (not built), which requires its own evidence audit first —
// that keying's natural home is this same floor scale.
#define MP_MISSING_FLOOR_SCALE 0.60
float prior_missing_fraction(int b) {
    float q = (float(b) + 0.5) / float(MP_TONE_BINS);
    float y = q * q;
    float d = mix(0.92, 0.85, smoothstep(0.06, 0.45, y));
    return mix(d, 0.80, smoothstep(0.58, 1.0, y));
}

// Compact picture-relative observer. Its measurements update hidden title/shot
// candidates; no instantaneous statistic is allowed to modulate visible grain.
void lean_observe() {
    uint lid = gl_LocalInvocationIndex;
    uint nthreads = gl_WorkGroupSize.x * gl_WorkGroupSize.y;
    if (lid == 0u) {
        // state_epoch carries two tokens (see its DESC): mod 4096 = the title
        // or series (a change starts cold), floor(x / 4096) = the file within
        // it (a change of that part alone is the next episode: a soft reset
        // that carries, below).
        bool ok = abs(m_state_magic - MP_STATE_MAGIC) < 0.0001
               && (uint(max(m_state_epoch, 0.0) + 0.5) & 4095u)
                  == (uint(max(state_epoch, 0.0) + 0.5) & 4095u);
        bool new_file = ok && abs(m_state_epoch - state_epoch) > 0.5;
        float raster_aspect = HOOKED_size.x / max(HOOKED_size.y, 1.0);
        bool raster_changed = ok && abs(m_source_aspect - raster_aspect) > 0.001;
        s_state_ok = ok ? 1u : 0u;
        s_raster_changed = raster_changed ? 1u : 0u;
        s_new_file = new_file ? 1u : 0u;
        s_prev_ready = (ok && m_prev_ready > 0.5 && !raster_changed
                        && !new_file) ? 1u : 0u;
    }
    barrier();
    bool state_ok = s_state_ok != 0u;
    bool prev_ready = s_prev_ready != 0u;

    if (!state_ok) {
        for (uint k = lid; k < uint(MP_GRID_N); k += nthreads) {
            prev_grid[k] = -1.0;
            prev_grid_off[k] = 0.0;
            prev_mean[k] = 0.0;
            prev_probe[k] = 0.0;
        }
        if (lid == 0u) {
            m_state_magic = MP_STATE_MAGIC;
            m_state_epoch = state_epoch;
            m_prev_ready = 0.0;
            m_measured = 0.0;
            m_gen_frame = 0.0;
            m_arr_seed = 0.0;
            m_regen = 1.0;
            m_regen_pending = 1.0;
            m_field_valid = 0.0;
            m_field_cov_rg = 0.0;
            m_field_cov_rb = 0.0;
            m_field_cov_gb = 0.0;
            m_field_var_r = 0.0;
            m_field_var_g = 0.0;
            m_field_var_b = 0.0;
            m_tpl_scale = 1.0;
            m_baked_grain_size = -1.0;
            m_baked_grain_contrast = -1.0;
            m_baked_value_warp = -1.0;
            m_baked_grain_base_sat = -1.0;
            m_baked_grain_soften = -1.0;
            m_source_aspect = HOOKED_size.x / max(HOOKED_size.y, 1.0);
            m_active_inset_x = 0.0;
            m_active_inset_y = 0.0;
            m_pending_inset_x = 0.0;
            m_pending_inset_y = 0.0;
            m_geom_streak = 0.0;
            m_geom_streak_y = 0.0;
            m_geom_known = 0.0;
            m_geom_known_y = 0.0;
            m_geom_blackout = 0.0;
            m_geom_blackout_y = 0.0;
            m_geom_changed = 0.0;
            m_geom_shrink_x = 0.0;
            m_geom_shrink_y = 0.0;
            m_geom_shrink_cand_x = 0.0;
            m_geom_shrink_cand_y = 0.0;
            m_observed = 0.0;
            m_coverage = 0.0;
            m_motion = 0.0;
            m_cut_score = 0.0;
            m_pan_px = 0.0;
            m_temporal_support = 0.0;
            m_title_power = MP_PRIOR_SIGMA * MP_PRIOR_SIGMA;
            m_shot_age = 0.0;
            m_shot_gain = 1.0;
            m_shot_ev = 0.0;
            // Reserved slot for the parked shot-restore design; nothing reads
            // it (the neutral boost arithmetic was removed in 5A).
            m_shot_restore_boost = 1.0;
            m_evidence = 0.0;
            m_ev_gate = 0.0;
            m_auth_mean = 0.0;
            m_acq_max = 1.0;
            m_q_random = 0.0;
            m_q_source = 1.0;
            float eff_sum = 0.0;
            for (int b = 0; b < MP_TONE_BINS; b++) {
                float s = MP_PRIOR_SIGMA * prior_tone_shape(b);
                float p = s * s;
                m_master_p[b] = p;
                m_master_w[b] = 0.0;
                m_char_p[b] = MP_COMPLEMENT_POWER * p;
                // This is the acquisition posterior, not a decorative floor:
                // absent delivery evidence, all capture paths still imply a
                // conservative amount of master grain throughout the range.
                // Cold start = the clean level (prior masters, constant bed).
                m_restore_p[b] = MP_BED * MP_MISSING_FLOOR_SCALE
                               * prior_missing_fraction(b) * p;
                eff_sum += m_char_p[b] + restore_gain * restore_gain
                         * m_restore_p[b];
            }
            m_eff_render = sqrt(eff_sum / float(MP_TONE_BINS)) / MP_FIELD_STD;
        }
    }
    barrier();

    if (lid == 0u && (s_raster_changed != 0u || s_new_file != 0u)) {
        m_geom_known = 0.0;
        m_geom_known_y = 0.0;
        m_geom_streak = 0.0;
        m_geom_streak_y = 0.0;
        m_geom_blackout = 0.0;
        m_geom_blackout_y = 0.0;
        m_geom_shrink_x = 0.0;
        m_geom_shrink_y = 0.0;
        m_geom_shrink_cand_x = 0.0;
        m_geom_shrink_cand_y = 0.0;
    }
    // Series carry (5B, audit lane B 5.3): the next file of the SAME series
    // keeps the title's learned grain -- the masters, and so the derived title
    // level -- at a quarter of its authority: the episode starts near the
    // series' level and re-earns authority from its own evidence. What belongs
    // to the old picture starts fresh: geometry (above), the pan EMA
    // (m_measured restarts it) and the shot, through the forced boundary
    // (prev_ready is false this frame), which also commits the carried
    // presentation at once. Authority x 0.25, not 0.5: at 0.5 an end-card grain
    // look-alike carried +58% (5B replay +70%) into the next episode's start
    // (Dandadan e02); at 0.25 nothing. With the derived title level the carried
    // start is partial (Utena e02 starts at ~4.5 units, 6.8 converged; a cold
    // start takes ~1.5 min to pass it).
    if (lid == 0u && s_new_file != 0u) {
        m_state_epoch = state_epoch;
        // The boundary is forced through m_prev_ready, not only this frame's
        // s_prev_ready: a file loaded PAUSED runs this block and then returns
        // at the pause gate, and its first observed frame must still be a
        // boundary (no comparison with the previous file's history, an
        // immediate letterbox commit, a fresh shot).
        m_prev_ready = 0.0;
        m_measured = 0.0;
        for (int b = 0; b < MP_TONE_BINS; b++)
            m_master_w[b] *= 0.25;
    }
    barrier();

    // mpv may redraw a paused frame without advancing source PTS. The shader
    // cannot infer that distinction from pixels, so shampv supplies this
    // machine-owned uniform. A newly loaded/inserted shader observes its held
    // source frame once after reset, so it can initialize without waiting for
    // unpause; established paused frames cannot train any posterior, advance
    // cadence, or alter their arrangement. Baked
    // look edits are the sole exception: rebuild the standing vocabulary once
    // under the same seed so pause-and-tune remains useful without animating.
    // The PARAM-conditioned return is dispatch-uniform and precedes all later
    // barriers, which is legal on FXC/D3D11.
    if (grain_pause > 0.5 && state_ok) {
        if (lid == 0u) {
            bool baked_params_changed =
                   abs(m_baked_grain_size - grain_size) > 1.0e-6
                || abs(m_baked_grain_contrast - grain_contrast) > 1.0e-6
                || abs(m_baked_value_warp - value_warp) > 1.0e-6
                || abs(m_baked_grain_base_sat - grain_base_sat) > 1.0e-6
                || abs(m_baked_grain_soften - grain_soften) > 1.0e-6;
            m_regen = 0.0;
            if (baked_params_changed) {
                m_baked_grain_size = grain_size;
                m_baked_grain_contrast = grain_contrast;
                m_baked_value_warp = value_warp;
                m_baked_grain_base_sat = grain_base_sat;
                m_baked_grain_soften = grain_soften;
                m_regen_pending = 1.0;
            }
            // A baked edit may have arrived while the generator was disabled.
            // Consume that sticky request as soon as gain/debug makes PASS 2
            // active, even if the baked cache already matches by then.
            bool gen_active = (grain_gain > 0.0 && match_grain > 0.0)
                           || debug_match > 0.5;
            if (gen_active && m_regen_pending > 0.5) {
                m_regen = 1.0;
                m_regen_pending = 0.0;
            }
            imageStore(out_image, ivec2(0), vec4(0.0));
        }
        return;
    }

    // A fixed full-raster probe discovers centred baked mattes and keeps cut
    // history independent of the active-picture mapping used by the grain
    // observer. Limited-range black arrives here near 16/255, not zero.
    if (lid == 0u) {
        s_picture_signal = 0u;
        s_raster_signal = 0u;
        s_candidate_inset_x = m_active_inset_x;
        s_candidate_inset_y = m_active_inset_y;
        s_candidate_valid_x = 0u;
        s_candidate_valid_y = 0u;
        s_candidate_immediate_x = 0u;
        s_candidate_immediate_y = 0u;
        s_candidate_clean_x = 0u;
        s_candidate_clean_y = 0u;
    }
    barrier();
    for (uint k = lid; k < uint(MP_GRID_N); k += nthreads) {
        uint gx = k % uint(MP_GRID_W);
        uint gy = k / uint(MP_GRID_W);
        vec2 probe_uv = (vec2(float(gx), float(gy)) + 0.5)
                      / vec2(float(MP_GRID_W), float(MP_GRID_H));
        float c = measure_luma(probe_uv);
        if (grain_source_trc >= 0.5)
            c = measure_bridge(c);
        s_probe[k] = c;
        if (c > MP_BLACKOUT_CODE_MAX)
            atomicAdd(s_raster_signal, 1u);
        if (gx >= 16u && gx < 48u && gy >= 16u && gy < 48u
            && c > 0.10)
            atomicAdd(s_picture_signal, 1u);
    }
    barrier();

    if (lid == 0u) {
        s_scan_inset_x = clamp(m_active_inset_x, 0.0, MP_ACTIVE_INSET_MAX);
        s_scan_inset_y = clamp(m_active_inset_y, 0.0, MP_ACTIVE_INSET_MAX);
        s_scan_x0 = uint(floor(s_scan_inset_x * float(MP_GRID_W) + 0.5));
        s_scan_y0 = uint(floor(s_scan_inset_y * float(MP_GRID_H) + 0.5));
        s_scan_x1 = uint(MP_GRID_W) - s_scan_x0;
        s_scan_y1 = uint(MP_GRID_H) - s_scan_y0;
    }
    barrier();

    if (lid < uint(MP_GRID_H)) {
        uint dark = 0u;
        float lo = 1.0;
        float hi = 0.0;
        float sum = 0.0;
        for (uint x = s_scan_x0; x < s_scan_x1; x++) {
            float c = s_probe[int(lid) * MP_GRID_W + int(x)];
            if (c < MP_BAR_DARK_MAX) {
                dark++;
                sum += c;
                lo = min(lo, c);
                hi = max(hi, c);
            }
        }
        s_row_dark[lid] = dark;
        s_row_range[lid] = max(hi - lo, 0.0);
        s_row_level[lid] = sum / max(float(dark), 1.0);
    }
    if (lid < uint(MP_GRID_W)) {
        uint dark = 0u;
        float lo = 1.0;
        float hi = 0.0;
        float sum = 0.0;
        for (uint y = s_scan_y0; y < s_scan_y1; y++) {
            float c = s_probe[int(y) * MP_GRID_W + int(lid)];
            if (c < MP_BAR_DARK_MAX) {
                dark++;
                sum += c;
                lo = min(lo, c);
                hi = max(hi, c);
            }
        }
        s_col_dark[lid] = dark;
        s_col_range[lid] = max(hi - lo, 0.0);
        s_col_level[lid] = sum / max(float(dark), 1.0);
    }
    barrier();

    if (lid == 0u) {
        uint row_span = max(s_scan_x1 - s_scan_x0, 1u);
        uint col_span = max(s_scan_y1 - s_scan_y0, 1u);
        uint row_matte_min = (row_span * MP_BAR_DARK_SAMPLES + 63u) / 64u;
        uint col_matte_min = (col_span * MP_BAR_DARK_SAMPLES + 63u) / 64u;
        uint row_picture_dark = (row_span * MP_BAR_EDGE_PICTURE + 63u) / 64u;
        uint col_picture_dark = (col_span * MP_BAR_EDGE_PICTURE + 63u) / 64u;
        int top = 0;
        int bottom = 0;
        int left = 0;
        int right = 0;
        for (int y = 0; y < MP_GRID_H / 2; y++) {
            bool matte = s_row_dark[y] >= row_matte_min
                      && s_row_range[y] <= MP_BAR_RANGE_MAX
                      && abs(s_row_level[y] - s_row_level[0])
                         <= MP_BAR_LEVEL_MAX;
            if (!matte) break;
            top++;
        }
        for (int y = MP_GRID_H - 1; y >= MP_GRID_H / 2; y--) {
            bool matte = s_row_dark[y] >= row_matte_min
                      && s_row_range[y] <= MP_BAR_RANGE_MAX
                      && abs(s_row_level[y] - s_row_level[MP_GRID_H - 1])
                         <= MP_BAR_LEVEL_MAX;
            if (!matte) break;
            bottom++;
        }
        for (int x = 0; x < MP_GRID_W / 2; x++) {
            bool matte = s_col_dark[x] >= col_matte_min
                      && s_col_range[x] <= MP_BAR_RANGE_MAX
                      && abs(s_col_level[x] - s_col_level[0])
                         <= MP_BAR_LEVEL_MAX;
            if (!matte) break;
            left++;
        }
        for (int x = MP_GRID_W - 1; x >= MP_GRID_W / 2; x--) {
            bool matte = s_col_dark[x] >= col_matte_min
                      && s_col_range[x] <= MP_BAR_RANGE_MAX
                      && abs(s_col_level[x] - s_col_level[MP_GRID_W - 1])
                         <= MP_BAR_LEVEL_MAX;
            if (!matte) break;
            right++;
        }

        int top_support = 0, bottom_support = 0;
        int left_support = 0, right_support = 0;
        int full_top_support = 0, full_bottom_support = 0;
        int full_left_support = 0, full_right_support = 0;
        for (int d = 0; d < 3; d++) {
            int yt = min(top + d, MP_GRID_H - 1);
            int yb = max(MP_GRID_H - 1 - bottom - d, 0);
            int xl = min(left + d, MP_GRID_W - 1);
            int xr = max(MP_GRID_W - 1 - right - d, 0);
            if (s_row_dark[yt] < row_picture_dark) top_support++;
            if (s_row_dark[yb] < row_picture_dark) bottom_support++;
            if (s_col_dark[xl] < col_picture_dark) left_support++;
            if (s_col_dark[xr] < col_picture_dark) right_support++;
            if (s_row_dark[d] < row_picture_dark) full_top_support++;
            if (s_row_dark[MP_GRID_H - 1 - d] < row_picture_dark)
                full_bottom_support++;
            if (s_col_dark[d] < col_picture_dark) full_left_support++;
            if (s_col_dark[MP_GRID_W - 1 - d] < col_picture_dark)
                full_right_support++;
        }

        float picture_lo = 1.0;
        float picture_hi = 0.0;
        for (int y = 16; y < 48; y++) {
            for (int x = 16; x < 48; x++) {
                float c = s_probe[y * MP_GRID_W + x];
                picture_lo = min(picture_lo, c);
                picture_hi = max(picture_hi, c);
            }
        }
        bool signal_ok = s_picture_signal >= MP_BAR_SIGNAL_MIN
                      && picture_hi - picture_lo >= MP_BAR_PICTURE_RANGE;
        float coarse_y = float(min(top, bottom)) / float(MP_GRID_H);
        float coarse_x = float(min(left, right)) / float(MP_GRID_W);
        bool bars_y = signal_ok && top >= MP_BAR_MIN_CELLS
                   && bottom >= MP_BAR_MIN_CELLS
                   && abs(top - bottom) <= 8
                   && coarse_y <= MP_ACTIVE_INSET_MAX
                   && (top_support >= 2 || bottom_support >= 2);
        bool bars_x = signal_ok && left >= MP_BAR_MIN_CELLS
                   && right >= MP_BAR_MIN_CELLS
                   && abs(left - right) <= 8
                   && coarse_x <= MP_ACTIVE_INSET_MAX
                   && (left_support >= 2 || right_support >= 2);
        bool full_y = signal_ok && !bars_y
                   && full_top_support >= 2 && full_bottom_support >= 2;
        bool full_x = signal_ok && !bars_x
                   && full_left_support >= 2 && full_right_support >= 2;

        // "Clean" means unambiguous: symmetric bars (within one cell) with
        // picture (fewer than 3/4 dark samples) on BOTH inner edges. A dark or
        // fading frame lets dark picture rows join one bar; requiring bright
        // picture beside both bars rejects most such reads. The bootstraps and
        // the release (many frames) need clean; "immediate" (a single-frame
        // commit: first frame, hard cut, blackout) also needs bars of at least
        // four cells. A full-picture read is both.
        if (bars_x) {
            bool clean_x = abs(left - right) <= 1
                        && left_support >= 2 && right_support >= 2;
            s_candidate_inset_x = coarse_x;
            s_candidate_valid_x = 1u;
            s_candidate_clean_x = clean_x ? 1u : 0u;
            s_candidate_immediate_x = (clean_x && min(left, right) >= 4)
                                    ? 1u : 0u;
        } else if (full_x) {
            s_candidate_inset_x = 0.0;
            s_candidate_valid_x = 1u;
            s_candidate_clean_x = 1u;
            s_candidate_immediate_x = 1u;
        }
        if (bars_y) {
            bool clean_y = abs(top - bottom) <= 1
                        && top_support >= 2 && bottom_support >= 2;
            s_candidate_inset_y = coarse_y;
            s_candidate_valid_y = 1u;
            s_candidate_clean_y = clean_y ? 1u : 0u;
            s_candidate_immediate_y = (clean_y && min(top, bottom) >= 4)
                                    ? 1u : 0u;
        } else if (full_y) {
            s_candidate_inset_y = 0.0;
            s_candidate_valid_y = 1u;
            s_candidate_clean_y = 1u;
            s_candidate_immediate_y = 1u;
        }
    }
    barrier();

    // Refine a coarse 1/64 edge inside its transition cell. Sixteen samples
    // across each of eight sub-rows/sub-columns retain subtitle tolerance while
    // reducing active-height error to about one source pixel at 1080p.
    if (lid < 512u) {
        uint side = lid / 128u;
        uint q = lid % 128u;
        uint step = q / 16u;
        uint across = q % 16u;
        float across_uv = (float(across) + 0.5) / 16.0;
        vec2 uv = vec2(across_uv);
        float offset = ((float(step) + 0.5) / 8.0 - 0.5)
                     / float(MP_GRID_H);
        bool enabled = false;
        if (side == 0u && s_candidate_valid_y != 0u
            && s_candidate_inset_y > 0.0) {
            uv.x = s_scan_inset_x
                 + across_uv * (1.0 - 2.0 * s_scan_inset_x);
            uv.y = s_candidate_inset_y + offset;
            enabled = true;
        } else if (side == 1u && s_candidate_valid_y != 0u
                   && s_candidate_inset_y > 0.0) {
            uv.x = s_scan_inset_x
                 + across_uv * (1.0 - 2.0 * s_scan_inset_x);
            uv.y = 1.0 - (s_candidate_inset_y + offset);
            enabled = true;
        } else if (side == 2u && s_candidate_valid_x != 0u
            && s_candidate_inset_x > 0.0) {
            uv.x = s_candidate_inset_x + offset;
            uv.y = s_scan_inset_y
                 + across_uv * (1.0 - 2.0 * s_scan_inset_y);
            enabled = true;
        } else if (side == 3u && s_candidate_valid_x != 0u
            && s_candidate_inset_x > 0.0) {
            uv.x = 1.0 - (s_candidate_inset_x + offset);
            uv.y = s_scan_inset_y
                 + across_uv * (1.0 - 2.0 * s_scan_inset_y);
            enabled = true;
        }
        float refine_v = enabled ? measure_luma(uv) : 1.0;
        if (enabled && grain_source_trc >= 0.5)
            refine_v = measure_bridge(refine_v);
        s_refine_probe[lid] = refine_v;
    }
    barrier();

    if (lid == 0u) {
        if (s_candidate_valid_y != 0u && s_candidate_inset_y > 0.0) {
            int top_steps = 0;
            int bottom_steps = 0;
            for (int step = 0; step < 8; step++) {
                uint dark_t = 0u;
                uint dark_b = 0u;
                float lo_t = 1.0, hi_t = 0.0;
                float lo_b = 1.0, hi_b = 0.0;
                float sum_t = 0.0, sum_b = 0.0;
                for (int x = 0; x < 16; x++) {
                    float ct = s_refine_probe[step * 16 + x];
                    float cb = s_refine_probe[128 + step * 16 + x];
                    if (ct < MP_BAR_DARK_MAX) {
                        dark_t++;
                        sum_t += ct;
                        lo_t = min(lo_t, ct); hi_t = max(hi_t, ct);
                    }
                    if (cb < MP_BAR_DARK_MAX) {
                        dark_b++;
                        sum_b += cb;
                        lo_b = min(lo_b, cb); hi_b = max(hi_b, cb);
                    }
                }
                bool mt = dark_t >= 10u
                       && max(hi_t - lo_t, 0.0) <= MP_BAR_RANGE_MAX
                       && abs(sum_t / max(float(dark_t), 1.0)
                            - s_row_level[0]) <= MP_BAR_LEVEL_MAX;
                bool mb = dark_b >= 10u
                       && max(hi_b - lo_b, 0.0) <= MP_BAR_RANGE_MAX
                       && abs(sum_b / max(float(dark_b), 1.0)
                            - s_row_level[MP_GRID_H - 1]) <= MP_BAR_LEVEL_MAX;
                if (mt && top_steps == step) top_steps++;
                if (mb && bottom_steps == step) bottom_steps++;
            }
            float refine_sum = 0.0;
            float refine_count = 0.0;
            if (top_steps > 0 && top_steps < 8) {
                refine_sum += (float(top_steps) - 0.5) / 8.0 - 0.5;
                refine_count += 1.0;
            }
            if (bottom_steps > 0 && bottom_steps < 8) {
                refine_sum += (float(bottom_steps) - 0.5) / 8.0 - 0.5;
                refine_count += 1.0;
            }
            if (refine_count > 0.0) {
                s_candidate_inset_y += refine_sum / refine_count
                                     / float(MP_GRID_H);
            }
        }
        if (s_candidate_valid_x != 0u && s_candidate_inset_x > 0.0) {
            int left_steps = 0;
            int right_steps = 0;
            for (int step = 0; step < 8; step++) {
                uint dark_l = 0u;
                uint dark_r = 0u;
                float lo_l = 1.0, hi_l = 0.0;
                float lo_r = 1.0, hi_r = 0.0;
                float sum_l = 0.0, sum_r = 0.0;
                for (int y = 0; y < 16; y++) {
                    float cl = s_refine_probe[256 + step * 16 + y];
                    float cr = s_refine_probe[384 + step * 16 + y];
                    if (cl < MP_BAR_DARK_MAX) {
                        dark_l++;
                        sum_l += cl;
                        lo_l = min(lo_l, cl); hi_l = max(hi_l, cl);
                    }
                    if (cr < MP_BAR_DARK_MAX) {
                        dark_r++;
                        sum_r += cr;
                        lo_r = min(lo_r, cr); hi_r = max(hi_r, cr);
                    }
                }
                bool ml = dark_l >= 10u
                       && max(hi_l - lo_l, 0.0) <= MP_BAR_RANGE_MAX
                       && abs(sum_l / max(float(dark_l), 1.0)
                            - s_col_level[0]) <= MP_BAR_LEVEL_MAX;
                bool mr = dark_r >= 10u
                       && max(hi_r - lo_r, 0.0) <= MP_BAR_RANGE_MAX
                       && abs(sum_r / max(float(dark_r), 1.0)
                            - s_col_level[MP_GRID_W - 1]) <= MP_BAR_LEVEL_MAX;
                if (ml && left_steps == step) left_steps++;
                if (mr && right_steps == step) right_steps++;
            }
            float refine_sum = 0.0;
            float refine_count = 0.0;
            if (left_steps > 0 && left_steps < 8) {
                refine_sum += (float(left_steps) - 0.5) / 8.0 - 0.5;
                refine_count += 1.0;
            }
            if (right_steps > 0 && right_steps < 8) {
                refine_sum += (float(right_steps) - 0.5) / 8.0 - 0.5;
                refine_count += 1.0;
            }
            if (refine_count > 0.0) {
                s_candidate_inset_x += refine_sum / refine_count
                                     / float(MP_GRID_W);
            }
        }
        s_candidate_inset_x = clamp(s_candidate_inset_x, 0.0,
                                    MP_ACTIVE_INSET_MAX);
        s_candidate_inset_y = clamp(s_candidate_inset_y, 0.0,
                                    MP_ACTIVE_INSET_MAX);
        // A one-frame subtitle/logo can obscure an otherwise stable pending
        // edge exactly on a cut. Preserve that rectangle for cut normalization
        // while keeping candidate validity false for commit authority.
        if (s_candidate_valid_x == 0u
            && m_geom_streak >= MP_GEOM_X_BOOTSTRAP)
            s_candidate_inset_x = m_pending_inset_x;
        if (s_candidate_valid_y == 0u
            && m_geom_streak_y >= MP_GEOM_Y_BOOTSTRAP)
            s_candidate_inset_y = m_pending_inset_y;
    }
    barrier();

    // Fixed-coordinate cut evidence is normalized by the selected picture
    // rectangle, so static baked bars cannot cap the changed fraction.
    if (lid == 0u) {
        s_probe_count = 0u;
        s_probe_changed = 0u;
    }
    for (uint k = lid; k < uint(MP_TONE_BINS); k += nthreads) {
        s_luma_now[k] = 0u;
        s_luma_prev[k] = 0u;
    }
    barrier();
    for (uint k = lid; k < uint(MP_GRID_N); k += nthreads) {
        uint gx = k % uint(MP_GRID_W);
        uint gy = k / uint(MP_GRID_W);
        float cut_ix = s_candidate_inset_x;
        float cut_iy = s_candidate_inset_y;
        int x0 = int(floor(cut_ix * float(MP_GRID_W) + 0.5));
        int y0 = int(floor(cut_iy * float(MP_GRID_H) + 0.5));
        bool inside = int(gx) >= x0 && int(gx) < MP_GRID_W - x0
                   && int(gy) >= y0 && int(gy) < MP_GRID_H - y0;
        float c = s_probe[k];
        float p = prev_ready ? prev_probe[k] : c;
        if (inside) {
            atomicAdd(s_probe_count, 1u);
            atomicAdd(s_luma_now[tone_bin(c)], 1u);
            atomicAdd(s_luma_prev[tone_bin(p)], 1u);
            if (prev_ready && abs(c - p) > 0.018)
                atomicAdd(s_probe_changed, 1u);
        }
        prev_probe[k] = c;
    }
    barrier();

    if (lid == 0u) {
        float probe_count = max(float(s_probe_count), 1.0);
        float probe_changed = prev_ready
                            ? float(s_probe_changed) / probe_count : 0.0;
        float probe_hist = 0.0;
        if (prev_ready) {
            for (int b = 0; b < MP_TONE_BINS; b++)
                probe_hist += abs(float(s_luma_now[b])
                                - float(s_luma_prev[b]));
            probe_hist /= probe_count;
        }
        bool hard_cut = prev_ready && probe_changed > 0.75
                     && probe_hist > 0.25;

        float cell_x = 1.0 / float(MP_GRID_W);
        float cell_y = 1.0 / float(MP_GRID_H);
        // The streak and blackout counters are carried in LOCALS and stored
        // once at the end of this block (FXC per-arm-store rule, see
        // m_shot_gain): the arms below mix constant stores with
        // read-modify-writes of the same scalars.
        float streak_x = m_geom_streak;
        float streak_y = m_geom_streak_y;
        float blackout_x = m_geom_blackout;
        float blackout_y = m_geom_blackout_y;
        bool pending_ready_x = streak_x >= MP_GEOM_X_BOOTSTRAP;
        bool pending_ready_y = streak_y >= MP_GEOM_Y_BOOTSTRAP;
        bool pending_match_y = pending_ready_y
                            && s_candidate_valid_y != 0u
                            && abs(s_candidate_inset_y - m_pending_inset_y)
                               <= 0.5 * cell_y;
        bool blackout_frame = s_raster_signal <= MP_BLACKOUT_SIGNAL_MAX;
        bool blackout_armed_x = blackout_x >= 3.0;
        bool blackout_armed_y = blackout_y >= 3.0;
        if (blackout_frame) {
            blackout_x = min(blackout_x + 1.0, MP_BLACKOUT_LATCH_MAX);
            blackout_y = min(blackout_y + 1.0, MP_BLACKOUT_LATCH_MAX);
        }

        bool valid_x = s_candidate_valid_x != 0u;
        bool valid_y = s_candidate_valid_y != 0u;
        bool diff_x = valid_x
                   && abs(s_candidate_inset_x - m_active_inset_x)
                      > 0.5 * cell_x;
        bool diff_y = valid_y
                   && abs(s_candidate_inset_y - m_active_inset_y)
                      > 0.5 * cell_y;
        if (!blackout_frame && valid_x)
            blackout_x = diff_x ? max(blackout_x - 1.0, 0.0) : 0.0;
        if (!blackout_frame && valid_y)
            blackout_y = diff_y ? max(blackout_y - 1.0, 0.0) : 0.0;

        if (diff_x) {
            bool same = abs(s_candidate_inset_x - m_pending_inset_x)
                      <= 0.5 * cell_x;
            if (same)
                streak_x = min(streak_x + 1.0, 65535.0);
            else {
                m_pending_inset_x = s_candidate_inset_x;
                streak_x = 1.0;
            }
        } else if (valid_x) {
            m_pending_inset_x = m_active_inset_x;
            streak_x = 0.0;
            m_geom_known = 1.0;
        } else {
            streak_x = max(streak_x - 1.0, 0.0);
        }

        if (diff_y) {
            bool same = abs(s_candidate_inset_y - m_pending_inset_y)
                      <= 0.5 * cell_y;
            if (same)
                streak_y = min(streak_y + 1.0, 65535.0);
            else {
                m_pending_inset_y = s_candidate_inset_y;
                streak_y = 1.0;
            }
        } else if (valid_y) {
            m_pending_inset_y = m_active_inset_y;
            streak_y = 0.0;
            m_geom_known_y = 1.0;
        } else {
            streak_y = max(streak_y - 1.0, 0.0);
        }

        bool commit_x = false;
        bool commit_y = false;
        float commit_inset_x = s_candidate_inset_x;
        float commit_inset_y = s_candidate_inset_y;
        if (diff_x) {
            commit_x = ((!prev_ready && s_candidate_immediate_x != 0u)
                     || ((hard_cut || blackout_armed_x)
                         && s_candidate_immediate_x != 0u)
                     || (m_geom_known < 0.5
                         && streak_x >= MP_GEOM_X_BOOTSTRAP
                         && s_candidate_clean_x != 0u));
        } else if (hard_cut && !valid_x && pending_ready_x
                   && abs(m_pending_inset_x - m_active_inset_x)
                      > 0.5 * cell_x) {
            commit_x = true;
            commit_inset_x = m_pending_inset_x;
        }
        if (diff_y) {
            commit_y = ((!prev_ready && s_candidate_immediate_y != 0u)
                     || (hard_cut
                         && (s_candidate_immediate_y != 0u
                             || pending_match_y))
                     || (blackout_armed_y && s_candidate_immediate_y != 0u)
                     || (m_geom_known_y < 0.5
                         && streak_y >= MP_GEOM_Y_BOOTSTRAP
                         && s_candidate_clean_y != 0u));
        } else if (hard_cut && !valid_y && pending_ready_y
                   && abs(m_pending_inset_y - m_active_inset_y)
                      > 0.5 * cell_y) {
            commit_y = true;
            commit_inset_y = m_pending_inset_y;
        }

        // Release (see MP_GEOM_RELEASE). Counters live in locals with one
        // store each (FXC per-arm-store rule, see m_shot_gain): a shrinking
        // clean read counts up; any other valid read, or a hard cut, resets;
        // an invalid (dark/ambiguous) frame holds.
        bool shrink_x = valid_x && s_candidate_clean_x != 0u
                     && s_candidate_inset_x
                        < m_active_inset_x - MP_GEOM_SHRINK_MIN;
        bool shrink_y = valid_y && s_candidate_clean_y != 0u
                     && s_candidate_inset_y
                        < m_active_inset_y - MP_GEOM_SHRINK_MIN;
        // A run continues only while the candidate stays within one refine
        // step of the run's last candidate; a jump restarts it at 1.
        bool steady_x = abs(s_candidate_inset_x - m_geom_shrink_cand_x)
                     <= 1.0 / 512.0;
        bool steady_y = abs(s_candidate_inset_y - m_geom_shrink_cand_y)
                     <= 1.0 / 512.0;
        float shrink_run_x = shrink_x
            ? (steady_x ? min(m_geom_shrink_x + 1.0, 65535.0) : 1.0)
            : ((valid_x || hard_cut) ? 0.0 : m_geom_shrink_x);
        float shrink_run_y = shrink_y
            ? (steady_y ? min(m_geom_shrink_y + 1.0, 65535.0) : 1.0)
            : ((valid_y || hard_cut) ? 0.0 : m_geom_shrink_y);
        float shrink_cand_x = shrink_x ? s_candidate_inset_x
                                       : m_geom_shrink_cand_x;
        float shrink_cand_y = shrink_y ? s_candidate_inset_y
                                       : m_geom_shrink_cand_y;
        m_geom_shrink_cand_x = shrink_cand_x;
        m_geom_shrink_cand_y = shrink_cand_y;
        if (shrink_x && shrink_run_x >= MP_GEOM_RELEASE) {
            commit_x = true;
            commit_inset_x = s_candidate_inset_x;
        }
        if (shrink_y && shrink_run_y >= MP_GEOM_RELEASE) {
            commit_y = true;
            commit_inset_y = s_candidate_inset_y;
        }
        m_geom_shrink_x = commit_x ? 0.0 : shrink_run_x;
        m_geom_shrink_y = commit_y ? 0.0 : shrink_run_y;

        bool commit = commit_x || commit_y;
        if (commit_x) {
            m_active_inset_x = clamp(commit_inset_x, 0.0,
                                     MP_ACTIVE_INSET_MAX);
            m_pending_inset_x = m_active_inset_x;
            streak_x = 0.0;
            m_geom_known = 1.0;
            blackout_x = 0.0;
        }
        if (commit_y) {
            m_active_inset_y = clamp(commit_inset_y, 0.0,
                                     MP_ACTIVE_INSET_MAX);
            m_pending_inset_y = m_active_inset_y;
            streak_y = 0.0;
            m_geom_known_y = 1.0;
            blackout_y = 0.0;
        }
        m_geom_streak = streak_x;
        m_geom_streak_y = streak_y;
        m_geom_blackout = blackout_x;
        m_geom_blackout_y = blackout_y;
        s_active_inset_x = m_active_inset_x;
        s_active_inset_y = m_active_inset_y;
        s_probe_hist_l1 = probe_hist;
        s_probe_hard_cut = hard_cut ? 1u : 0u;
        s_history_ready = (prev_ready && !commit) ? 1u : 0u;
        m_geom_changed = commit ? 1.0 : 0.0;
    }
    barrier();

    if (lid == 0u) {
        s_valid_count = 0u;
        s_flat_count = 0u;
        s_changed_count = 0u;
        s_lk_gxx = 0u;
        s_lk_gyy = 0u;
        s_lk_gxy_p = 0u;
        s_lk_gxy_n = 0u;
        s_lk_bx_p = 0u;
        s_lk_bx_n = 0u;
        s_lk_by_p = 0u;
        s_lk_by_n = 0u;
    }
    for (uint k = lid;
         k < uint(MP_TONE_BINS * AMP_BINS); k += nthreads)
        s_hist[k] = 0u;
    for (uint k = lid; k < uint(AMP_BINS); k += nthreads)
        s_content_hist[k] = 0u;
    barrier();

    // Offsets are normalized picture-height coordinates expressed on the
    // finite MP_PICTURE_DENSITY analysis lattice, mapped from the active
    // picture rather than an assumed output resolution. The horizontal UV
    // pitch comes from the actual LUMA raster aspect, so the crosses stay
    // isotropic on 4:3, scope and portrait sources. The 2-sample cross
    // isolates the grain band; the 12-sample cross only measures edges for
    // the flat gate.
    vec2 active_inset = vec2(s_active_inset_x, s_active_inset_y);
    vec2 active_extent = vec2(1.0) - 2.0 * active_inset;
    float uvx_per_vtex = active_extent.y * HOOKED_size.y
                       / max(HOOKED_size.x * MP_PICTURE_DENSITY, 1.0);
    float uvy_per_vtex = active_extent.y / MP_PICTURE_DENSITY;
    vec2 dx1 = vec2(2.0 * uvx_per_vtex, 0.0);
    vec2 dy1 = vec2(0.0, 2.0 * uvy_per_vtex);
    vec2 dx6 = vec2(12.0 * uvx_per_vtex, 0.0);
    vec2 dy6 = vec2(0.0, 12.0 * uvy_per_vtex);
    // Picture-relative footprint (see grain_sample): one tap at <= 1080 lines.
    float fp_scale = HOOKED_size.y / 1080.0;
    int fp_taps = (fp_scale > 1.05) ? 4 : 1;
    vec2 fp_vsize = HOOKED_size / max(fp_scale, 1.0);

    for (uint k = lid; k < uint(MP_GRID_N); k += nthreads) {
        vec2 grid_uv = (vec2(float(k % uint(MP_GRID_W)),
                             float(k / uint(MP_GRID_W))) + 0.5)
                     / vec2(float(MP_GRID_W), float(MP_GRID_H));
        vec2 uv = active_inset + grid_uv * active_extent;
        float c = grain_sample(uv, fp_vsize, fp_taps);
        float xm1 = grain_sample(uv - dx1, fp_vsize, fp_taps);
        float xp1 = grain_sample(uv + dx1, fp_vsize, fp_taps);
        float ym1 = grain_sample(uv - dy1, fp_vsize, fp_taps);
        float yp1 = grain_sample(uv + dy1, fp_vsize, fp_taps);
        float xm6 = grain_sample(uv - dx6, fp_vsize, fp_taps);
        float xp6 = grain_sample(uv + dx6, fp_vsize, fp_taps);
        float ym6 = grain_sample(uv - dy6, fp_vsize, fp_taps);
        float yp6 = grain_sample(uv + dy6, fp_vsize, fp_taps);
        if (grain_source_trc >= 0.5) {
            c = measure_bridge(c);
            xm1 = measure_bridge(xm1); xp1 = measure_bridge(xp1);
            ym1 = measure_bridge(ym1); yp1 = measure_bridge(yp1);
            xm6 = measure_bridge(xm6); xp6 = measure_bridge(xp6);
            ym6 = measure_bridge(ym6); yp6 = measure_bridge(yp6);
        }

        float lp1 = (4.0 * c + xm1 + xp1 + ym1 + yp1) * 0.125;
        float band0 = c - lp1;
        float edge = 0.25 * (abs(xp6 - xm6) + abs(yp6 - ym6));
        bool flat_ok = c > 0.002 && c < 0.985 && edge < 0.026;
        bool prev_flat = s_history_ready != 0u && prev_grid[k] > 0.0;
        float prev_c = (s_history_ready != 0u)
                     ? max(abs(prev_grid[k]) - 1.0, 0.0) : c;
        // Per-cell stillness on the local mean (see MP_STILL_MEAN). Without
        // history every cell counts as still.
        float local_mean = (c + xm1 + xp1 + ym1 + yp1 + xm6 + xp6 + ym6 + yp6)
                         * (1.0 / 9.0);
        bool still = s_history_ready == 0u
                  || abs(local_mean - prev_mean[k]) <= MP_STILL_MEAN;

        int now_lb = tone_bin(c);
        if (s_history_ready != 0u
            && abs(c - prev_c) > 0.018 * grain_extreme)
            atomicAdd(s_changed_count, 1u);

        // Grid global-translation (single-step Lucas-Kanade) accumulation --
        // see the S4 veto note at the PAN defines. Gradients are taken at
        // +/-1 grid cell: at that spacing grain and fine texture alias away
        // and decorrelate, so their g*d products cancel across the lattice
        // while a coherent camera translation correlates. Runs before the
        // flat gate (the pan signal lives in coarse structure, edges
        // included). The border ring is skipped so no tap crosses into
        // mattes/bars and dilutes the fit. Signed sums split into +/- uint
        // pairs; the per-point clamp keeps 4096 x MP_LK_CLAMP inside uint32.
        uint lk_kx = uint(k) % uint(MP_GRID_W);
        uint lk_ky = uint(k) / uint(MP_GRID_W);
        if (s_history_ready != 0u && c > 0.002 && c < 0.985
            && lk_kx > 0u && lk_kx < uint(MP_GRID_W - 1)
            && lk_ky > 0u && lk_ky < uint(MP_GRID_H - 1)) {
            vec2 lk_step = active_extent
                         / vec2(float(MP_GRID_W), float(MP_GRID_H));
            // Same picture-relative footprint as the grain cross: the pan
            // thresholds sit on the static-grain noise floor of these taps,
            // which is raster-dependent through the bilinear footprint.
            float lk_xp = grain_sample(uv + vec2(lk_step.x, 0.0), fp_vsize, fp_taps);
            float lk_xm = grain_sample(uv - vec2(lk_step.x, 0.0), fp_vsize, fp_taps);
            float lk_yp = grain_sample(uv + vec2(0.0, lk_step.y), fp_vsize, fp_taps);
            float lk_ym = grain_sample(uv - vec2(0.0, lk_step.y), fp_vsize, fp_taps);
            if (grain_source_trc >= 0.5) {
                lk_xp = measure_bridge(lk_xp); lk_xm = measure_bridge(lk_xm);
                lk_yp = measure_bridge(lk_yp); lk_ym = measure_bridge(lk_ym);
            }
            float lk_gx = 0.5 * (lk_xp - lk_xm);
            float lk_gy = 0.5 * (lk_yp - lk_ym);
            float lk_d = c - prev_c;
            float lk_gxy = lk_gx * lk_gy;
            float lk_bx = -lk_gx * lk_d;
            float lk_by = -lk_gy * lk_d;
            atomicAdd(s_lk_gxx,
                      uint(min(lk_gx * lk_gx * MP_LK_SCALE, MP_LK_CLAMP)));
            atomicAdd(s_lk_gyy,
                      uint(min(lk_gy * lk_gy * MP_LK_SCALE, MP_LK_CLAMP)));
            if (lk_gxy >= 0.0)
                atomicAdd(s_lk_gxy_p,
                          uint(min(lk_gxy * MP_LK_SCALE, MP_LK_CLAMP)));
            else
                atomicAdd(s_lk_gxy_n,
                          uint(min(-lk_gxy * MP_LK_SCALE, MP_LK_CLAMP)));
            if (lk_bx >= 0.0)
                atomicAdd(s_lk_bx_p,
                          uint(min(lk_bx * MP_LK_SCALE, MP_LK_CLAMP)));
            else
                atomicAdd(s_lk_bx_n,
                          uint(min(-lk_bx * MP_LK_SCALE, MP_LK_CLAMP)));
            if (lk_by >= 0.0)
                atomicAdd(s_lk_by_p,
                          uint(min(lk_by * MP_LK_SCALE, MP_LK_CLAMP)));
            else
                atomicAdd(s_lk_by_n,
                          uint(min(-lk_by * MP_LK_SCALE, MP_LK_CLAMP)));
        }

        // Only flat AND still cells feed the grain statistics: a moving object
        // removes its own cells instead of vetoing the whole frame.
        if (flat_ok)
            atomicAdd(s_flat_count, 1u);
        if (flat_ok && still) {
            atomicAdd(s_hist[now_lb * AMP_BINS + amp_bin(abs(band0))], 1u);
            atomicAdd(s_valid_count, 1u);

            if (prev_flat) {
                float dhp = abs(band0 - prev_grid_off[k]);
                atomicAdd(s_content_hist[amp_bin(dhp)], 1u);
            }
        }

        // Sign stores the flat bit; abs(value)-1 stores every previous luma so
        // the next frame can reconstruct a cut histogram without another SSBO.
        prev_grid[k] = flat_ok ? 1.0 + c : -(1.0 + c);
        prev_grid_off[k] = band0;
        prev_mean[k] = local_mean;
    }
    barrier();

    if (lid == 0u) {
        float sigma_band[MP_TONE_BINS];
        uint tone_count[MP_TONE_BINS];
        float sigma_sum = 0.0;
        float sigma_weight = 0.0;
        for (int b = 0; b < MP_TONE_BINS; b++) {
            uint count = 0u;
            int base = b * AMP_BINS;
            for (int a = 0; a < AMP_BINS; a++) count += s_hist[base + a];
            tone_count[b] = count;
            uint target = (count + 1u) / 2u;
            uint acc = 0u;
            int med_bin = 0;
            uint acc_before = 0u;
            for (int a = 0; a < AMP_BINS; a++) {
                acc += s_hist[base + a];
                if (acc >= target) {
                    med_bin = a;
                    acc_before = acc - s_hist[base + a];
                    break;
                }
            }
            // Grouped median, interpolated in the log domain inside its
            // bin (amp_median); a bin-0 median stays exact zero.
            float med_abs = amp_median(med_bin, target, acc_before,
                                       s_hist[base + med_bin]);
            float sigma = med_abs * MP_MEDABS_TO_STD * MP_HP_TO_SOURCE;
            sigma_band[b] = (count >= MP_MIN_BIN_SAMPLES) ? sigma : 0.0;
            if (count >= MP_MIN_BIN_SAMPLES) {
                sigma_sum += sigma * float(count);
                sigma_weight += float(count);
            }
        }
        float observed = (sigma_weight > 0.0) ? sigma_sum / sigma_weight : 0.0;

        uint tcount = 0u;
        for (int a = 0; a < AMP_BINS; a++) tcount += s_content_hist[a];
        uint ttarget = (tcount + 1u) / 2u;
        uint tacc = 0u;
        int tmed_bin = 0;
        uint tacc_before = 0u;
        for (int a = 0; a < AMP_BINS; a++) {
            tacc += s_content_hist[a];
            if (tacc >= ttarget) {
                tmed_bin = a;
                tacc_before = tacc - s_content_hist[a];
                break;
            }
        }
        // Same log-domain grouped median and exact-zero rule as the spatial
        // estimator, on the same log scale (no grain_extreme range any more).
        float tmed_abs = (tcount == 0u) ? 0.0
                       : amp_median(tmed_bin, ttarget, tacc_before,
                                    s_content_hist[tmed_bin]);
        float temporal = tmed_abs * MP_MEDABS_TO_STD
                       * MP_HP_TO_SOURCE * 0.70710678;
        float temporal_ratio = temporal / max(observed, 1.0e-6);
        float coverage = float(s_valid_count) / float(MP_GRID_N);
        // Share of the flat cells that stayed still (1 without history). Grain
        // cannot trip it (local-mean test), so it measures content motion.
        float still_share = (s_flat_count > 0u)
                          ? float(s_valid_count) / float(s_flat_count) : 1.0;
        float changed = (s_history_ready != 0u)
                      ? float(s_changed_count) / float(MP_GRID_N) : 0.0;
        float hist_l1 = s_probe_hist_l1;
        bool hard_cut = s_probe_hard_cut != 0u;
        bool shot_boundary = !prev_ready || hard_cut;
        bool geometry_only = m_geom_changed > 0.5 && !shot_boundary;

        // Global-translation magnitude from the LK normal equations (2x2
        // solve). The shift is in grid-cell units; convert per axis to
        // picture-lattice samples so the measure is resolution-independent.
        // Magnitude
        // (not the vector) is EMA'd: alternating shake must hold the level.
        // Update skips boundary frames -- a same-geometry hard cut leaves
        // s_history_ready set while prev_grid holds pre-cut luma, so keying
        // the skip on shot_boundary keeps that garbage frame out of the EMA.
        float lk_Gxx = float(s_lk_gxx) / MP_LK_SCALE;
        float lk_Gyy = float(s_lk_gyy) / MP_LK_SCALE;
        float lk_Gxy = (float(s_lk_gxy_p) - float(s_lk_gxy_n)) / MP_LK_SCALE;
        float lk_BX = (float(s_lk_bx_p) - float(s_lk_bx_n)) / MP_LK_SCALE;
        float lk_BY = (float(s_lk_by_p) - float(s_lk_by_n)) / MP_LK_SCALE;
        float lk_det = lk_Gxx * lk_Gyy - lk_Gxy * lk_Gxy;
        float pan_px_inst = 0.0;
        if (lk_det > 1.0e-7) {
            float lk_sx = (lk_Gyy * lk_BX - lk_Gxy * lk_BY) / lk_det;
            float lk_sy = (lk_Gxx * lk_BY - lk_Gxy * lk_BX) / lk_det;
            float lk_cellx = (active_extent.x / float(MP_GRID_W))
                           / max(uvx_per_vtex, 1.0e-9);
            float lk_celly = MP_PICTURE_DENSITY / float(MP_GRID_H);
            pan_px_inst = sqrt(lk_sx * lk_sx * lk_cellx * lk_cellx
                             + lk_sy * lk_sy * lk_celly * lk_celly);
        }
        if (!shot_boundary && !geometry_only && s_history_ready != 0u)
            m_pan_px = (m_measured < 1.5) ? pan_px_inst
                     : mix(m_pan_px, pan_px_inst, 0.30);

        // Temporal similarity supplies authority, not amount. It gates title
        // learning and shot refinement but never provides a master-power target.
        float q_amp = smoothstep(0.00018, 0.00080, observed);
        float q_cov = smoothstep(0.08, 0.28, coverage);
        float q_random = 1.0 - smoothstep(0.35, 0.90,
                                          abs(log2(max(temporal_ratio, 0.01))));
        // Stillness is judged per cell in the lattice loop (5B): coverage
        // counts only flat AND still cells, so q_cov carries the frame's
        // stillness and a moving object no longer vetoes the whole frame
        // (the frame-level test tripped on coarse grain and film weave).
        // S4 evidence-quality veto. Applied as a SEPARATE factor on the
        // rise-capable lanes only -- never folded into q_cov/q_random
        // themselves, because the downward lanes consume the raw q_cov and
        // vetoing a reduce-only lane would RAISE rendered power on moving
        // textured content.
        float q_source = 1.0 - smoothstep(MP_PAN_LO, MP_PAN_HI, m_pan_px);
        float static_gate_raw = (s_history_ready != 0u && !hard_cut)
                              ? q_cov * q_random : 0.0;
        float static_gate = static_gate_raw * q_source;
        float evidence = q_amp * static_gate;

        // Estimate shot sensitivity after dividing out the title's luma curve.
        // A picture-population mean of sigma would confuse composition/exposure
        // with noise sensitivity, especially across bright/dark cuts. The
        // divisor must be EXACTLY the unit-gain curve presentation renders
        // (title curve blended with the per-bin master by local evidence) —
        // dividing by the bare per-bin master lets the title-borrowed level
        // re-enter through shot gain and double-count into the committed
        // presentation.
        float prior_base_p = MP_PRIOR_SIGMA * MP_PRIOR_SIGMA;
        float gain_log_sum = 0.0;
        float gain_weight = 0.0;
        for (int b = 0; b < MP_TONE_BINS; b++) {
            float sigma0 = sigma_band[b];
            float count_r = smoothstep(24.0, 96.0, float(tone_count[b]));
            float amp_r = smoothstep(0.00012, 0.00070, sigma0);
            float w = count_r * amp_r;
            if (w > 0.0) {
                float shape = prior_tone_shape(b);
                float title_unit = m_title_power * shape * shape;
                float local_q = smoothstep(0.10, 0.35, m_master_w[b]);
                // Floor the divisor at a quarter of the acquisition prior:
                // a title far below its prior must not read the first grainy
                // shot as gain-4 sensitivity and then suppress its own master
                // establishment (a guard; 5B's master floor keeps titles at or
                // above the prior).
                float unit_master = max(mix(title_unit, m_master_p[b],
                                            local_q),
                                        0.25 * shape * shape * prior_base_p);
                float ratio = sigma0 * sigma0
                            / max(unit_master, 1.0e-10);
                gain_log_sum += w * log2(clamp(ratio, 0.25, 16.0));
                gain_weight += w;
            }
        }
        float frame_gain_est = (gain_weight > 0.0)
                             ? exp2(gain_log_sum / gain_weight) : 1.0;
        float gain_weight_support = smoothstep(0.50, 2.0, gain_weight);
        // q_random is a continuous learning weight. Shot presentation uses a
        // steeper confidence curve: once temporal behaviour is convincingly
        // stochastic, amplitude comes from measured power, not from how far
        // inside the authenticity band this frame happened to land.
        float shot_auth = gain_weight_support * q_cov * q_source
                        * smoothstep(0.02, 0.15, q_random);


        // A real cut commits only the authenticated title presentation. Spatial
        // evidence on the boundary cannot distinguish grain from picture texture;
        // the next three frames are the sole fast refinement window for proving
        // shot-local sensitivity and character temporally.
        // m_shot_gain is carried through this chain in a LOCAL and stored once
        // below, deliberately. Written as the natural per-arm stores -- the
        // boundary arm assigning the constant 1.0, the other arms
        // read-modify-writing the same SSBO scalar -- FXC/D3D11 DROPPED the
        // boundary arm's reset while every sibling store in that same arm
        // landed correctly, so the 4-frame refinement window ran on the
        // PREVIOUS shot's gain and the error compounded across cuts (measured
        // 2026-07-29: 1.633 vs 1.0 at the same m_shot_age, d3d11 vs vulkan,
        // bit-reproducible across runs). Same NitMeter PASS 2 nm_maxcll class
        // -- see that comment. Do not re-split this into per-arm SSBO stores.
        float shot_gain = m_shot_gain;
        if (shot_boundary) {
            shot_gain = 1.0;
            m_shot_age = 0.0;
        } else if (m_shot_age < 4.0 && !geometry_only) {
            float frame_gain = clamp(frame_gain_est, 0.50, 4.0);
            float gain_target = mix(1.0, frame_gain, shot_auth);
            shot_gain = mix(shot_gain, gain_target, 0.35);
        } else if (evidence > 0.0) {
            float frame_gain = clamp(frame_gain_est, 0.50, 4.0);
            shot_gain = mix(shot_gain, frame_gain, 0.0015 * evidence);
        }
        // Single store; must precede the first reader below (pobs_title).
        m_shot_gain = shot_gain;
        // Evidence mass this shot has spent (the acquisition budget below):
        // local carry, one unconditional store (FXC rule, see above).
        float shot_ev = shot_boundary ? 0.0 : m_shot_ev;
        bool fast_shot = shot_ev < 3.0;
        m_shot_ev = shot_ev + evidence;

        // Scene tone centroid (sqrt-bin domain, bar/matte-excluded histogram).
        // Master evidence from bins far ABOVE it is exposure-suspect: bright
        // pixels inside a dark scene (lamps, rim light) carry the dark
        // scene's capture character and book it into a tone bin that
        // genuinely bright scenes later render (M3 field trace, Aldnoah E01
        // 2026-08-20: bin 6 climbed to 2.5x prior during a stretch with no
        // daylight). This guard is a PARTIAL bound on the extreme tail only:
        // the measured climber sat ~1.5 bins above its scenes' centroids —
        // at this ramp's lower edge, where the guard is inert — and the
        // field A/B trimmed ~25% of the excess. The edges await
        // M1/golden-pair calibration; do not present this as the full fix.
        // Genuine bright scenes have their centroid AT those bins, so they
        // are untouched. The guard discounts the RISE rate and the authority
        // earn only — downward reads stay reduce-safe at full effect — and
        // floors at 0.35, which keeps the anti-rectification rate ordering
        // (rise 0.003 x 0.35 >= down 0.001) so symmetric sigma jitter cannot
        // rectify into a one-way decline.
        float cent_n = 0.0;
        float cent_s = 0.0;
        for (int b = 0; b < MP_TONE_BINS; b++) {
            float c = float(s_luma_now[b]);
            cent_n += c;
            cent_s += c * float(b);
        }
        float scene_centroid = (cent_n > 0.0) ? cent_s / cent_n : 3.5;

        float weight_sum = 0.0;
        float char_sum = 0.0;
        float restore_sum = 0.0;
        float acq_max = 1.0;
        // Masters learn only from frames with history and no hard cut: band0
        // compared across a cut (or against nothing) reads as fresh noise and
        // passes q_random at 0.77-0.88 (5B review; the removed frame q_still
        // used to block these frames).
        bool learn_ok = s_history_ready != 0u && !hard_cut;
        // Evidence quality: a frame whose flat cells mostly moved counts less,
        // in BOTH directions. Encoders thin grain in motion (within one shot,
        // YUA reads 17-23% lower on moving frames), and the old frame-level
        // gate preferred static evidence. Symmetric on purpose: weighting only
        // the downward rate shifted the tracker's quantile instead (its lift
        // survived shuffling the still share); this weight's lift does not
        // (3-15% under shuffling). YUA 2.41 -> 2.84 units, TRL 1.48 -> 1.69.
        float still_w = smoothstep(0.80, 0.97, still_share);
        for (int b = 0; b < MP_TONE_BINS; b++) {
            float sigma0 = sigma_band[b];
            float pobs = sigma0 * sigma0;
            float count_r = smoothstep(24.0, 96.0, float(tone_count[b]));
            float amp_r = smoothstep(0.00012, 0.00070, sigma0);
            float plaus = 1.0 - smoothstep(MP_SIGMA_PLAUS_LO,
                                           MP_SIGMA_PLAUS_HI,
                                           sigma0 / grain_extreme);
            float spatial_reliability = count_r * amp_r * plaus * q_cov;
            float reliability_raw = learn_ok
                                  ? spatial_reliability * q_random : 0.0;
            float reliability = reliability_raw * q_source;

            // Best-preserved evidence updates the absolute title master curve.
            // Downward evidence is deliberately much slower because delivery
            // erasure is more likely than a physically noiseless master. The
            // master is TITLE-referenced: divide the current shot sensitivity
            // out of the observation, or one long high-gain shot leaks its
            // sensitivity into the persistent curve and, through the derived
            // title level, the title-wide base. The absolute ceiling is the film-plausible
            // roof for sustained band-edge evidence the soft reject passes.
            if (reliability_raw > 0.0) {
                // Sensitivity normalization deflates hot shots only. Dividing
                // by gain < 1 INFLATES the learned target, and an over-learned
                // master reads exactly as gain < 1 at the next cut -- the
                // runaway measured on the Odyssey benchmark (master bins up
                // 3x, title pinned at its cap in ~2 min). A genuinely quiet
                // shot now learns conservatively low at the slow rate instead
                // -- recoverable, unlike the ratchet.
                float pobs_title = pobs / clamp(m_shot_gain, 1.0, 4.0);
                float clipped = clamp(pobs_title, 0.25 * m_master_p[b],
                                      4.0 * m_master_p[b]);
                clipped = min(clipped,
                              MP_MASTER_P_MAX * grain_extreme
                              * grain_extreme);
                // Floor at the prior (5B): the prior is the engine's clean
                // level, its conservative acquisition-grain assumption, so a
                // master never learns below it. The log-spaced histograms make
                // clean titles' low noise measurable; without this floor their
                // masters would drain below the clean level the way the old
                // clean lane drained My Gift (1.22 -> 0.55 units). A master
                // still walks back down to the prior after an upward misread.
                float m_shape = prior_tone_shape(b);
                clipped = max(clipped, m_shape * m_shape * prior_base_p);
                // 3:1, not 10:1: a blind rate asymmetry rectifies fluctuating
                // evidence (fire/texture sigma jitter) into a one-way climb.
                // The erasure prior still earns a downward discount, but down
                // reads here carry measurable grain (amp_r > 0). Calibration
                // point for the ProRes ground-truth pass.
                // The S4 veto gates the RISE and the authority earn only:
                // translation decorrelation can only INFLATE sigma, so a
                // sub-master read under motion is a valid one-sided bound
                // and keeps walking the level down (reversibility).
                float above = max(float(b) - scene_centroid, 0.0);
                float exposure_guard = 1.0
                                     - 0.65 * smoothstep(1.5, 3.5, above);
                // Per-shot acquisition budget (5B, audit lane B 5.2 option): a
                // bin that has not yet earned authority (w < 0.5) learns its
                // level 16x faster while the shot's first 3 units of evidence
                // are being spent. Speed then follows the number of shots that
                // show grain, not frames, so a one-shot noise effect cannot
                // move a bin far (My Gift's 2 s look-alike at 14 min: 1.39
                // units for 0.9 min, vs 1.80 for 5.5 min with a per-bin 8x
                // gain). The meter counts the frame's evidence (with q_amp and
                // the pan veto), while the bins spend their own reliability:
                // under the pan veto the budget is not spent, so DOWNWARD
                // learning stays fast for that whole shot -- which is what
                // walks look-alikes back on clean titles. Up and down share
                // the gain, so the 3:1 ratio and the exposure-guard ordering
                // hold. The authority earn below keeps its base rate: it is
                // the in-band-twin defense.
                float acq = (fast_shot && m_master_w[b] < 0.5) ? 16.0 : 1.0;
                acq_max = max(acq_max, acq);
                // In the fast lane the stored level may not run ahead of what
                // the bin PRESENTS by more than 2x: authority opens slowly, so
                // an unbounded fast rise stayed invisible and then appeared at
                // once when local_q opened (5B review: Gunbuster x2.2 in one
                // cut, x3 within a minute; now x1.7 / x1.7). A stored level
                // already above the cap is pulled down toward it.
                if (acq > 1.0 && clipped > m_master_p[b]) {
                    float lq_now = smoothstep(0.10, 0.35, m_master_w[b]);
                    float presented = mix(m_title_power * m_shape * m_shape,
                                          m_master_p[b], lq_now);
                    clipped = min(clipped, 2.0 * presented);
                }
                float master_rate = (clipped > m_master_p[b])
                                  ? 0.003 * acq * q_source * exposure_guard
                                    * still_w
                                  : 0.001 * acq * still_w;
                m_master_p[b] = mix(m_master_p[b], clipped,
                                    master_rate * reliability_raw);
                m_master_w[b] += 0.005 * reliability * exposure_guard
                               * (1.0 - m_master_w[b]);

            } else {
                // Staleness: per-bin authority is re-earned, not permanent.
                // Evidence-free stretches relax local_q back toward the title
                // curve (tau ~4.6 min at 24p). The learned LEVEL stays stored
                // -- absence is not evidence of a clean master -- but since the
                // title level is derived from authority-weighted masters (5B),
                // a long drought also relaxes the PRESENTED level toward the
                // prior; it returns as authority is re-earned. Keyed on RAW
                // evidence: motion-vetoed frames HOLD authority rather than
                // drain it (freeze-not-drain, S4).
                m_master_w[b] *= (1.0 - 1.5e-4);
            }

        }

        // Title level, derived each frame (5B, audit lane B 5.1): the
        // authority-weighted log-mean of the per-bin masters in prior units
        // (bins 2-7; bins 0-1 sit below limited-range black), shrunk to the
        // prior with pseudo-weight 1 so one weak bin cannot set the title. It
        // replaces the rise-only presence integrator, the clean lane and title
        // confidence: no state of its own, no drift, bounded by the prior (the
        // masters' floor) and the plausibility roof. Unevidenced tones borrow
        // it through the presentation mix below.
        float tl_sum = 0.0;
        float tl_w = 0.0;
        for (int b = 2; b < MP_TONE_BINS; b++) {
            float tl_q = smoothstep(0.10, 0.35, m_master_w[b]);
            float tl_shape = prior_tone_shape(b);
            tl_sum += tl_q * log(max(m_master_p[b]
                                     / (tl_shape * tl_shape * prior_base_p),
                                     1.0e-6));
            tl_w += tl_q;
        }
        m_title_power = clamp(exp(tl_sum / (tl_w + 1.0)), 1.0,
                              3.24 * grain_extreme * grain_extreme)
                      * prior_base_p;

        // Title-class certificate for the censoring key below: only
        // exposure-safe DARK-bin evidence may certify heavy grain. The
        // known exposure-conflation residual books contaminated master
        // power UPWARD (practicals inside dark scenes land in the
        // mid-bright bins — the M3 climber measured 2.2-2.5x prior on
        // the motivating title, and an ungated per-bin key re-inflated
        // exactly those bins to the ceiling in the 2026-08-21 trace),
        // so it cannot inflate b1/b2 — while every measured grainy
        // class shows dark-bin evidence (lain 12x, cybercity 12x,
        // ladies 4x, edgerunners 4x prior vs aldnoah 1.4x, clean 1.0x).
        float ev_gate = 0.0;
        for (int gb = 1; gb <= 2; gb++) {
            float gshape = prior_tone_shape(gb);
            float gratio = m_master_p[gb]
                         / max(gshape * gshape * prior_base_p, 1.0e-12);
            // Certification is binary-ish at 3.0x, but the ceiling GRANT
            // keeps ramping: the author's sitting (2026-08-22) found
            // mid-anchor titles (~4x: Ladies vs Butlers 3.95, Edgerunners
            // 4.09) borderline overgrained at the full legacy ceiling
            // while heavy anchors (Lain/CyberCity ~12x) read right, so
            // the 3-8x band earns a partial ceiling and only proven
            // heavy grain collects it all. The floor is the taste
            // constant: 0.75 sat as still-hot on LvB (2026-08-22
            // re-sit); 0.2 is the author's deeper pick.
            ev_gate = max(ev_gate,
                          smoothstep(0.10, 0.35, m_master_w[gb])
                        * smoothstep(1.6, 3.0, gratio)
                        * mix(0.2, 1.0, smoothstep(3.0, 8.0, gratio)));
        }

        for (int b = 0; b < MP_TONE_BINS; b++) {
            float sigma0 = sigma_band[b];
            float count_r = smoothstep(24.0, 96.0, float(tone_count[b]));
            float amp_r = smoothstep(0.00012, 0.00070, sigma0);
            float plaus = 1.0 - smoothstep(MP_SIGMA_PLAUS_LO,
                                           MP_SIGMA_PLAUS_HI,
                                           sigma0 / grain_extreme);
            float spatial_reliability = count_r * amp_r * plaus * q_cov;
            float reliability_raw = spatial_reliability * q_random;
            float reliability = reliability_raw * q_source;
            float shape = prior_tone_shape(b);
            float title_curve = m_title_power * shape * shape * m_shot_gain;
            float learned_master = m_master_p[b] * m_shot_gain;
            float local_q = smoothstep(0.10, 0.35, m_master_w[b]);
            // Local evidence wins in BOTH directions: a confidently-measured
            // quiet bin must be allowed to render below the title curve, or
            // downward per-bin learning is presentation-inert.
            float master = mix(title_curve, learned_master, local_q);
            // Evidence-keyed censoring (author direction 2026-08-20 night):
            // encoders censor grain roughly in proportion to how much there
            // is to code — measured missing power tracks grain character
            // (clean digital anime ~0; film 0.39 at remux, 0.55-0.75 at
            // AVC 5M). A bin whose OWN measured master sits well above the
            // prior level earns the legacy film-exact fraction, but only
            // on a title the dark-bin certificate above proves grainy;
            // prior-level, unevidenced, or measured-quiet bins keep the
            // recalibrated clean/remux floor. Keyed to LOCAL per-bin
            // evidence, never the title curve: title_power saturates on
            // dark-survivor titles and a title-level key would re-inflate
            // their quiet mids.
            float ev_ratio = m_master_p[b]
                           / max(shape * shape * prior_base_p, 1.0e-12);
            float ev_q = ev_gate * local_q
                       * smoothstep(1.3, 2.8, ev_ratio);
            float missing_fraction = prior_missing_fraction(b)
                                   * mix(MP_MISSING_FLOOR_SCALE, 1.0, ev_q);
            float char_target = MP_COMPLEMENT_POWER * master;
            float restore_target = MP_BED * missing_fraction * master;

            float char_up_rate, char_down_rate;
            float restore_up_rate, restore_down_rate;
            if (geometry_only) {
                // Re-framing the observer changes neither the title nor the
                // shot presentation on the commit frame.
                char_up_rate = 0.0;
                char_down_rate = 0.0;
                restore_up_rate = 0.0;
                restore_down_rate = 0.0;
            } else if (shot_boundary) {
                // Commit the title-shaped shot character on the boundary rather
                // than letting an eye-visible adjustment trail into the shot.
                // The boundary uses the authenticated title baseline, so complete
                // both presentation terms atomically at that neutral shot gain.
                char_up_rate = 1.0;
                char_down_rate = 1.0;
                restore_up_rate = 1.0;
                restore_down_rate = 1.0;
            } else if (m_shot_age < 4.0) {
                // Upward post-boundary refinement requires temporal
                // authentication; downward refinement stays motion-ungated --
                // disappearing proof must revoke toward the title baseline even
                // inside a moving cut.
                char_up_rate = 0.20 * evidence;
                char_down_rate = 0.50 * reliability_raw;
                restore_up_rate = 0.35 * reliability;
                restore_down_rate = 0.50 * spatial_reliability;
            } else {
                // Once the shot is established, presentation moves on a
                // roughly ten-minute horizon. The observer may keep learning
                // quickly internally without making that learning visible;
                // title-level movement surfaces at the next boundary commit,
                // never mid-shot.
                char_up_rate = 0.00004 * static_gate;
                char_down_rate = 0.00008;
                restore_up_rate = 0.00006 * static_gate;
                restore_down_rate = 0.00008;
            }
            float ar = (char_target > m_char_p[b])
                     ? char_up_rate : char_down_rate;
            float rr = (restore_target > m_restore_p[b])
                     ? restore_up_rate : restore_down_rate;
            m_char_p[b] = mix(m_char_p[b], char_target, ar);
            m_restore_p[b] = mix(m_restore_p[b], restore_target, rr);

            weight_sum += m_master_w[b];
            char_sum += m_char_p[b];
            restore_sum += m_restore_p[b];
        }

        float avg_w = weight_sum / float(MP_TONE_BINS);
        // Same power sum as OUTPUT's composite (char + restore_gain^2 x restore).
        float p_restore = restore_gain * restore_gain
                        * restore_sum / float(MP_TONE_BINS);
        float p_total = char_sum / float(MP_TONE_BINS) + p_restore;
        m_evidence = evidence;
        m_ev_gate = ev_gate;
        m_acq_max = acq_max;
        m_q_random = q_random;
        m_q_source = q_source;
        // Underlying renderable power, deliberately independent of live
        // gain/match knobs. OUTPUT gates and scales those controls directly;
        // caching them here made pause-time off->on toggles inherit a stale
        // zero until playback resumed.
        m_eff_render = sqrt(max(p_total, 0.0)) / MP_FIELD_STD;
        m_eff_render = clamp(m_eff_render, 0.0, 0.75);
        m_observed = observed;
        m_temporal_support = temporal;
        m_auth_mean = avg_w;
        m_coverage = coverage;
        m_motion = changed;
        m_cut_score = hist_l1;
        m_shot_age = min(m_shot_age + 1.0, 65535.0);
        m_measured = min(m_measured + 1.0, 65535.0);
        m_prev_ready = 1.0;

        // One clock. m_arr_seed follows grain_rate and seeds both the visible
        // arrangement (PASS 3) and the template (PASS 2): every visible tick
        // regenerates the template, so a tick always shows fresh grain.
        float prev_gen_frame = m_gen_frame;
        // Float SSBO counters stop accepting +1 at 2^24. Wrap well before
        // that boundary; the seed hash intentionally tolerates this roughly
        // four-day cycle at 24p, while the cadence clock keeps advancing.
        m_gen_frame += 1.0;
        if (m_gen_frame >= 8388608.0) m_gen_frame = 0.0;
        float visible_seed = floor(m_gen_frame * grain_rate);
        float prev_visible_seed = floor(prev_gen_frame * grain_rate);
        bool visible_tick = visible_seed != prev_visible_seed;
        if (visible_tick)
            m_arr_seed = visible_seed;

        bool baked_params_changed =
               abs(m_baked_grain_size - grain_size) > 1.0e-6
            || abs(m_baked_grain_contrast - grain_contrast) > 1.0e-6
            || abs(m_baked_value_warp - value_warp) > 1.0e-6
            || abs(m_baked_grain_base_sat - grain_base_sat) > 1.0e-6
            || abs(m_baked_grain_soften - grain_soften) > 1.0e-6;
        // GRAIN_FIELD content is params + seed only, so a cut without a
        // visible tick keeps its template (a rebuild would be bit-identical).
        // A look edit rebuilds under the current seed.
        if (visible_tick || baked_params_changed)
            m_regen_pending = 1.0;
        if (baked_params_changed) {
            m_baked_grain_size = grain_size;
            m_baked_grain_contrast = grain_contrast;
            m_baked_value_warp = value_warp;
            m_baked_grain_base_sat = grain_base_sat;
            m_baked_grain_soften = grain_soften;
        }

        // PASS 2 can be disabled by its PARAM-only WHEN. Snapshot a sticky
        // request only when that pass will execute; it must never clear this
        // value itself because its workgroups have no global ordering.
        bool gen_active = (grain_gain > 0.0 && match_grain > 0.0)
                       || debug_match > 0.5;
        m_regen = 0.0;
        if (gen_active && m_regen_pending > 0.5) {
            m_regen = 1.0;
            m_regen_pending = 0.0;
        }
        // Raster-change detector only (see s_raster_changed): a new LUMA
        // aspect resets the geometry commits. OUTPUT does not read it -- its
        // canvas is the picture (5A removed the old rectangle recovery).
        m_source_aspect = HOOKED_size.x / max(HOOKED_size.y, 1.0);
        imageStore(out_image, ivec2(0), vec4(0.0));
    }
    barrier();
}

void hook() {
    lean_observe();
}

//!HOOK LUMA
//!BIND HOOKED
//!BIND GRAIN_STATE
//!BIND GRAIN_FIELD
//!SAVE GRAIN_GEN_TRIGGER
//!WIDTH 960
//!HEIGHT 540
//!COMPUTE 32 32
//!WHEN grain_gain match_grain * debug_match +
//!DESC Film Grain Match: GRAIN gen (960x540 template, source-locked)

// LUMA hook = mpv's FRESH group: runs once per SOURCE frame regardless of
// video-sync / display refresh (measured 24.x/s under both display-resample
// @120Hz and audio sync, 2026-07-06). The OUTPUT composite (redraw group,
// per-present) just fetches GRAIN_FIELD, so re-presents cost ~nothing and
// grain cadence can't ride the display refresh. TPL ARCHITECTURE
// (2026-07-10): grain is a normalized active-picture field represented on the
// current 2160-sample implementation lattice,
// whose width follows the committed active-picture aspect, but this
// pass only generates the physical 960x540 TEMPLATE — the composite
// assembles the picture field from per-block randomized template windows
// (AV1-FGS style; see the block shuffle there). Template texels REPRESENT
// picture-lattice samples, so every sigma below is calibrated in normalized
// active-picture-height units and
// generation cost scales with TEMPLATE area (~0.2 ms vs 3.4 at full size;
// 960x540 = the measured quality/perf knee, dev grain-genrate README).
//
// STORAGE-TEXTURE RECOVERY (2026-07-06): the earlier split SAVE'd GRAIN_FIELD
// from this fresh pass and BIND'd it in the OUTPUT redraw pass — but a SAVE'd
// texture is a per-frame transient and did NOT survive the fresh->redraw group
// gap, so grain generated but never reached the presented frame. Fix: GRAIN_FIELD
// is now a persistent, shader-owned TEXTURE+STORAGE image (declared
// top-of-file, like the GRAIN_STATE SSBO) that we imageStore into here and
// imageLoad from in the composite. Persistent storage retains its contents across
// presents, so a redraw (no fresh dispatch) reads the last-written field. This
// fresh pass still needs a dispatch grid, so it SAVEs a throwaway trigger
// texture (GRAIN_GEN_TRIGGER, never bound) purely to size the 960x540 dispatch.
// GRAIN_FIELD is rgba16f: rgb = final signed grain (bandpass + warp),
// a is unused.

// 17-tap support (was 13, 9, 7): the blue channel's base sigma (1.20)
// reaches ~1.15 even at the crisp neutral and higher when coarse, where 7 taps
// (clean only to sigma ~1.0) truncate the Gaussian into a box and ripple the
// spectrum. 9 taps held sigma up to ~1.5 (the SIGMA_MAX cap). grain_soften
// widens every kernel AFTER that cap, up to sqrt(1.5^2 + 2.5^2) = 2.92 at its
// Phase 4 ceiling; 17 taps hold that with the edge tap at 2.4% of peak.
// Shared footprint 48x48x3 floats = 27.6 KB, under the 32 KB D3D11 limit.
// Arrays/loops are parametrized on MAX_TAPS so the support can never drift
// out of sync.
#define MAX_TAPS 8

// Neutral correlation-length calibration. The physical quantity is this sigma
// divided by PICTURE_DENSITY: a fraction of active picture height. K=0.75 was
// calibrated against real grain plates and retains broadband energy to the
// current lattice Nyquist. Source-matched size is therefore independent of
// master and output raster dimensions.
#define PICTURE_DENSITY 2160.0
// MUST equal MP_PICTURE_DENSITY / PICTURE_DENSITY_OUT in PASS 1 / PASS 3.
#define K_NEUTRAL_NORM (0.75 / 2160.0)
#define K_NEUTRAL (K_NEUTRAL_NORM * PICTURE_DENSITY)
// Physical TEMPLATE dims. The noise seed wraps on these so the template is
// TOROIDAL: the composite's per-block window fetches (offset + flip + overlap
// halo) wrap on the template extent and must not show a seam. Wrapping the
// seed coordinate makes noise(grid)==noise(0); the separable DoG halo then
// wraps too (each workgroup regenerates its halo from global coords), so the
// seam is continuous. In-range coords (the whole template interior) are
// unchanged. MUST equal this pass's WIDTH/HEIGHT directives above (960 x 540)
// Same translation unit, no compile guard. This is not a picture resolution:
// template is a reusable vocabulary on the current synthesis lattice, not a
// picture resolution. THREE same-file sites are hand-kept in lockstep: the
// TEXTURE SIZE directive, this define, and the composite's const gsize (it
// hardcodes the dims too since the 2026-07-23 perf round — constant divisors
// let its wrap mods strength-reduce; it no longer reads imageSize).
// (Directive prefix omitted here on purpose: the parser splits sections on
// that marker even inside a comment.)
#define GEN_GRID ivec2(960, 540)
// Per-channel render-sigma cap. The coarse extreme and a higher-density future
// res-scaling can push a channel past it, so cap GRACEFULLY (slightly finer than
// ideal at that extreme) rather than ripple the spectrum. The actual film range
// (fine digital .. ~16mm) stays under it, so this never touches normal operation.
// KEPT at 1.5 although the support is now 17 taps: the blue outer DoG sigma
// (1.2 * 1.8 = 2.16) is capped here at the calibration point, and raising the
// cap would move the calibrated defaults. grain_soften widens post-cap (see
// soften_var) and is what the wider support is for.
#define SIGMA_MAX    1.5
// CONTRAST / "sandpaper" axis (2026-06-05). The generator (white noise -> ONE Gaussian
// blur) is LOWPASS (DC-peaked = soft cloud); real film grain is BANDPASS (suppressed DC,
// mid-freq peak) per the offline profiler + ITU-T H.274's frequency-filtering grain model.
// Fix = difference-of-Gaussians: grain = blur(s1) - a*blur(s1*BP_RATIO), which suppresses
// DC -> bandpass. s1 = the per-channel render sigma (size lever); a = BP_ALPHA*grain_contrast
// (the hardness/sandpaper dial). grain_contrast=0 -> a=0 -> grain=blur(s1) = BIT-IDENTICAL
// to the old lowpass (A/B-safe). The 2nd (wider) blur reuses the MAX_TAPS blur machinery by
// REGENERATING the (reproducible) noise; s2 is capped at SIGMA_MAX (see the cap note) so the
// support holds it even after the grain_soften widening.
// An analytic RMS norm (from the blur weights) keeps grain STRENGTH constant as contrast
// rises. Offline-locked to CyberCity (tools/mgt_bandpass_design.py): BP_RATIO 1.8, A0 0.60.
#define BP_RATIO     1.8
#define BP_ALPHA     0.60
// VISIBLE-STRENGTH NORMALIZATION (Phase 4, 2026-09-29). Every shape knob
// (grain_size, grain_contrast, grain_soften) holds the grain's VISIBLE RMS,
// its RMS after a Gaussian of variance VIS_VAR lattice samples, at the value
// the default kernel renders, so grain_gain alone sets the amount. field_norm
// by itself pins RAW RMS, and at the 4K reference much of a fine field's top
// octave is near-invisible: on this model, holding raw RMS made soft and
// coarse grain read up to ~1.65x stronger than fine grain at the same gain.
// Film measures granularity through a fixed aperture too (Kodak's 48 um ~
// sigma 1.4-2.5 samples on 35 mm scans, by extraction); Barten's CSF halves
// near sigma 1.35 (desk-distance 4K) to 2.1 (100 px/deg). 1.5 is a prior in
// that range, not a measured match: near threshold the CSF is bandpass, and a
// bandpass model rates soft grain weaker than this one does (evidence audit
// 2026-09-29: soften 2.5 scores 0.49-1.16x across standard CSF models). The
// author's gain-matching sitting calibrates it. Per
// channel: each channel holds its own visible RMS, so the VISIBLE channel
// balance of the default look survives every knob (red, the finest kernel,
// gains the most visible fraction as grain softens or coarsens: a green-only
// common scalar measured +6% luma and a visible red shift at the softest
// setting). VIS_ANCHOR_* is the default look and MUST equal the grain_size /
// grain_contrast / grain_soften PARAM defaults (no compile guard; the gains
// are exactly 1 there).
#define VIS_VAR             2.25
// Fine-side ceiling on the visible-strength gain. Very fine kernels keep most
// of their energy above the visibility cutoff, so holding their visible RMS
// takes up to ~3x the default's RAW amplitude, and the density arm's
// nonlinear steps (log-normal mean correction, symmetric headroom clamps, the
// 8.0 tone roof) act on raw amplitude: they turn that invisible excess into
// visible shadow darkening (review 2026-09-29: -12% mean at size 0.5 in a
// deep shadow at 1080p). Past 1.5x, fine grain reads a little weaker than
// the anchor instead; grain_gain still reaches it. Softer/coarser kernels
// (gain < 1) are never limited.
#define VIS_GAIN_MAX        1.5
#define VIS_ANCHOR_SIZE     1.34
#define VIS_ANCHOR_CONTRAST 2.0
#define VIS_ANCHOR_SOFTEN   0.29
// Composite tile scale reference: R = Var / Var(lag-1 difference) of the
// green DoG field at the default kernel (1.8607 on 17 taps), with a 2% margin
// so the default floors to a scale of exactly 1. See m_tpl_scale.
#define TPL_R_DEFAULT       1.90
// Chroma/energy calibration (2026-08): common generator amplitude scale plus a
// corpus-fit saturation triple, verified against master RGB grain correlations
// on both film families (correlation error ~0.02 vs ~0.33 uncalibrated; luma
// field RMS unchanged to 0.01%). Prior triple 0.6/0.5/0.80 with unit scales
// and base_sat default 0.25 — kept here because the historical GRAIN_STD
// anchors below were measured at exactly that mix.
#define RED_VARIANCE_SCALE   1.027766
#define GREEN_VARIANCE_SCALE 1.027766
#define BLUE_VARIANCE_SCALE  1.027766
#define RED_SATURATION       0.736
#define GREEN_SATURATION     0.262
#define BLUE_SATURATION      0.718
// Per-channel base sigma ratios: the generator's chromatic grain-size
// signature (red finest, blue coarsest), in units of k_size.
const vec3 CHANNEL_SIGMA = vec3(0.78, 1.00, 1.20);

const uvec2 isize = uvec2(gl_WorkGroupSize) + uvec2(2 * MAX_TAPS);

shared float grain_r[isize.y][isize.x];
shared float grain_g[isize.y][isize.x];
shared float grain_b[isize.y][isize.x];
shared float dyn_wr[2 * MAX_TAPS + 1];
shared float dyn_wg[2 * MAX_TAPS + 1];
shared float dyn_wb[2 * MAX_TAPS + 1];
shared float dyn_wr2[2 * MAX_TAPS + 1];   // bandpass DoG outer-blur weights (s2 = BP_RATIO*s1)
shared float dyn_wg2[2 * MAX_TAPS + 1];
shared float dyn_wb2[2 * MAX_TAPS + 1];
shared float bp_norm[3];                   // per-channel analytic RMS norm (amplitude-stable)
shared float vsum_sigma[3];                // per-channel RMS of vsum (= GRAIN_STD*s1c), for value_warp
shared float warp_renorm;                  // 1/sqrt(E[tanh^2(value_warp*Z)]) -- amplitude-preserving
shared float field_norm[3];                // size-invariant absolute field RMS


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

// Takes the VARIANCE so the grain_soften fold below needs no sqrt/square
// round trip (D3D11 only promises sqrt to 1 ULP; this keeps soften 0 exact).
float gaussian_weight_var(float dx, float sigma2) {
    return exp(-0.5 * dx * dx / max(sigma2, 1e-6));
}

// grain_soften fold: cap first (calibration point), then widen by the common
// capture sigma in quadrature. Gaussian * Gaussian = Gaussian, and the blur
// is linear, so blur(DoG(s1, s2)) = DoG(soften(s1), soften(s2)). Returns the
// widened VARIANCE; soft2 == 0 reproduces the legacy c*c exactly.
float soften_var(float sigma, float soft2) {
    float c = min(sigma, SIGMA_MAX);
    return c * c + soft2;
}

// White-noise energy of the separable DoG w1 - a*w2 built from the variance
// pair (v1, v2) exactly as the weight fill builds it (discrete, normalized,
// 2*MAX_TAPS+1 taps). x = raw energy; y = VISIBLE energy, after the VIS_VAR
// visibility Gaussian. Past that blur each kernel is Gaussian to good accuracy
// at variance (its discrete second moment + VIS_VAR), so the visible term is
// the continuous closed form: the 2-D inner product of unit Gaussians of
// variances va, vb is 1/(2 pi (va + vb)). Offline check against measured
// fields: visible fraction within 1.4% at every size/contrast/soften corner,
// with a near-constant bias that cancels in the anchor ratio.
vec2 dog_energy(float v1, float v2, float a) {
    float n1 = 0.0, n2 = 0.0, q11 = 0.0, q22 = 0.0, q12 = 0.0;
    float m1 = 0.0, m2 = 0.0;
    for (int i = -MAX_TAPS; i <= MAX_TAPS; i++) {
        float x = float(i);
        float w1 = gaussian_weight_var(x, v1);
        float w2 = gaussian_weight_var(x, v2);
        n1 += w1; n2 += w2;
        q11 += w1 * w1; q22 += w2 * w2; q12 += w1 * w2;
        m1 += x * x * w1; m2 += x * x * w2;
    }
    float i1 = 1.0 / n1, i2 = 1.0 / n2;
    q11 *= i1 * i1; q22 *= i2 * i2; q12 *= i1 * i2;
    float raw = q11 * q11 - 2.0 * a * q12 * q12 + a * a * q22 * q22;
    float V1 = m1 * i1 + VIS_VAR, V2 = m2 * i2 + VIS_VAR;
    float vis = (0.5 / V1 - 2.0 * a / (V1 + V2) + 0.5 * a * a / V2)
              * 0.15915494;
    return vec2(raw, vis);
}

void hook() {
    uint lid = gl_LocalInvocationIndex;
    uint num_threads = gl_WorkGroupSize.x * gl_WorkGroupSize.y;

    // The observer already produced the complete amount record. Generation is
    // source-locked; m_regen is this frame's immutable request, and the
    // template is seeded by the visible arrangement tick (one clock).
    uint frame_seed = uint(max(0.0, m_arr_seed));
    bool regenerate = m_regen > 0.5;
    if (gl_GlobalInvocationID.x == 0u && gl_GlobalInvocationID.y == 0u)
        imageStore(out_image, ivec2(0), vec4(0.0));

    // PARAM-uniform skips only. m_eff_render may NOT join this guard: it is an SSBO
    // read, and an early return conditioned on buffer data puts every barrier
    // below into varying control flow for FXC (D3D11 X3663 -- the same class
    // the unconditional outer blur already works around; params are cbuffer
    // values and provably uniform, buffer loads are not). The skip it bought
    // was structurally dead anyway: the restoration bed keeps m_eff_render > 0
    // on real content, and OUTPUT still gates on it safely (no barriers there).
    if ((grain_gain <= 0.0 || match_grain <= 0.0) && debug_match <= 0.5)
        return;

    // Grain is generated on the picture-space implementation lattice. Size and
    // hardness are explicit levers, never evidence: delivery-grade encodes
    // destroy per-title size identity (master-side separation collapses to
    // noise after 5 Mbps AVC/AV1; our own generated sizes, re-measured after
    // a 6 Mbps 1080p encode, fell below per-frame SNR 0.5), so the observer
    // measures amount only. grain_size scales the calibrated neutral correlation length directly.
    // Phase 4 removed the grain_sharpness trim that sat between them (it
    // mixed toward the frozen observer size 1.040); its default 0.3 lives on
    // in grain_size's default 1.34 (k 1.005 vs the old 1.0045).
    float k_size = K_NEUTRAL * grain_size;

    // CONTRAST/BANDPASS: build inner (s1) AND outer (s2 = BP_RATIO*s1) blur weights.
    // bp_alpha is UNIFORM across the workgroup (a cbuffer PARAM times a
    // constant), and the outer blur below runs UNCONDITIONALLY (X3663 rework),
    // so every barrier stays in uniform control flow. bp_alpha=0 -> vsum2
    // multiplied out in the combine, bp_norm=1, grain = blur(s1) = the
    // lowpass generator. render_hardness is the calibration point the
    // hardness lever was measured at (the former frozen observer state 0.50),
    // kept as the same expression so the kernels stay bit-identical. The max
    // alpha is 0.6 x 2 x 0.408 = 0.49; the DoG stays positively correlated with
    // its inner blur (zero-crossing at alpha 1), so no cap is needed while
    // grain_contrast stays <= 4. match_grain is an OUTPUT mix only: it no
    // longer reaches the kernels.
    float render_hardness = smoothstep(0.25, 0.82, 0.50);
    float bp_alpha = BP_ALPHA * grain_contrast * render_hardness;
    if (regenerate && lid < uint(2 * MAX_TAPS + 1)) {
        int idx = int(lid);
        float dx = float(idx - MAX_TAPS);
        float s1r = CHANNEL_SIGMA.r * k_size;
        float s1g = CHANNEL_SIGMA.g * k_size;
        float s1b = CHANNEL_SIGMA.b * k_size;
        // grain_soften: SUB-PIXEL CAPTURE MODEL (2026-08-22). A physical grain
        // is a continuous object that a scanner/camera pixel integrates; it is
        // not a dot sitting on the synthesis lattice. A lattice of random
        // impulses blurred by the grain kernel is statistically the same as
        // continuous-position grains at these sizes (lattice-vs-continuous
        // covariance error 0.5% at sigma 0.78), so what the legacy generator
        // lacked is the CAPTURE-side MTF: a common Gaussian (sigma in LATTICE
        // samples -- picture-height units, a title property like every other
        // size lever, NOT rescaled by the output raster; 0.29 = 1/sqrt(12),
        // the minimal one-sample scan aperture; more = softer scan/optics)
        // convolved into every channel. The composite's display-side footprint
        // box at non-1:1 outputs and the panel's own hold are separate,
        // legitimate apertures -- this one is the title's. Direction is
        // supported by the master-PSD record (the DoG basis cannot reach the
        // measured master coarseness), so a fitted value is a calibration
        // follow-up; 0.29 is the minimal physical floor.
        // Symmetric kernels commute with the composite's window flips and
        // integer offsets, so folding it here equals filtering each template
        // window for zero per-present cost. (Not the assembled field: the
        // composite's overlap crossfade is not a filter, which is why its band
        // scales with the kernel -- m_tpl_scale below.) Applied AFTER the
        // SIGMA_MAX cap so 0 restores the legacy kernels exactly (verified
        // byte-identical with MAX_TAPS pinned to 4; the wider support alone
        // carries Gaussian tails 9 taps truncated, < 0.01% RMS) and the
        // calibrated cap point never moves; bp_norm/field_norm/covariance below are analytic from
        // these weights. The amplitude holds VISIBLE strength (field_gain
        // below, Phase 4; it held raw RMS before) on the generation lattice;
        // at sub-2160 outputs the composite's footprint box (not
        // RMS-renormalized by design) loses less of a softer field, so
        // delivered RMS rises a little there (measured 1080p before Phase 4:
        // +1/+4/+6% at 0.29/0.75/1.0; 4K: <= 0.34%). Red
        // (the finest channel, ~33% of its power at periods < 4 px at the
        // defaults vs 5% for blue; measured 0.338 at 4K) gives up the most
        // ABSOLUTE fine power -- the per-pixel speckle grain_size cannot
        // reach without coarsening green/blue along with it. Ordering note:
        // the fold precedes value_warp, and a pointwise warp re-broadens the
        // spectrum, so at value_warp >= ~2 the capture-MTF reading weakens;
        // accepted (default warp 0), not a claim at high warp.
        float soft2 = grain_soften * grain_soften;
        dyn_wr[idx]  = gaussian_weight_var(dx, soften_var(s1r, soft2));
        dyn_wg[idx]  = gaussian_weight_var(dx, soften_var(s1g, soft2));
        dyn_wb[idx]  = gaussian_weight_var(dx, soften_var(s1b, soft2));
        dyn_wr2[idx] = gaussian_weight_var(dx, soften_var(s1r * BP_RATIO, soft2));
        dyn_wg2[idx] = gaussian_weight_var(dx, soften_var(s1g * BP_RATIO, soft2));
        dyn_wb2[idx] = gaussian_weight_var(dx, soften_var(s1b * BP_RATIO, soft2));
    }
    barrier();
    if (regenerate && lid == 0u) {
        float nr = 0.0, ng = 0.0, nb = 0.0, nr2 = 0.0, ng2 = 0.0, nb2 = 0.0;
        for (int i = 0; i < 2 * MAX_TAPS + 1; i++) {
            nr += dyn_wr[i]; ng += dyn_wg[i]; nb += dyn_wb[i];
            nr2 += dyn_wr2[i]; ng2 += dyn_wg2[i]; nb2 += dyn_wb2[i];
        }
        for (int i = 0; i < 2 * MAX_TAPS + 1; i++) {
            dyn_wr[i] /= nr; dyn_wg[i] /= ng; dyn_wb[i] /= nb;
            dyn_wr2[i] /= nr2; dyn_wg2[i] /= ng2; dyn_wb2[i] /= nb2;
        }
        // Analytic RMS norm: Var(blur1 - a*blur2)/Var(blur1) on white noise. For a
        // separable 2D kernel, sum-of-squares and the cross-sum get squared. Keeps
        // grain STRENGTH constant as contrast rises (clean A/B). a=0 -> vr=1 -> norm=1.
        float s1c[3]; float s2c[3]; float s12c[3];
        s1c[0] = 0.0; s1c[1] = 0.0; s1c[2] = 0.0;
        s2c[0] = 0.0; s2c[1] = 0.0; s2c[2] = 0.0;
        s12c[0] = 0.0; s12c[1] = 0.0; s12c[2] = 0.0;
        for (int i = 0; i < 2 * MAX_TAPS + 1; i++) {
            s1c[0] += dyn_wr[i]*dyn_wr[i];  s2c[0] += dyn_wr2[i]*dyn_wr2[i];  s12c[0] += dyn_wr[i]*dyn_wr2[i];
            s1c[1] += dyn_wg[i]*dyn_wg[i];  s2c[1] += dyn_wg2[i]*dyn_wg2[i];  s12c[1] += dyn_wg[i]*dyn_wg2[i];
            s1c[2] += dyn_wb[i]*dyn_wb[i];  s2c[2] += dyn_wb2[i]*dyn_wb2[i];  s12c[2] += dyn_wb[i]*dyn_wb2[i];
        }
        for (int c = 0; c < 3; c++) {
            float r1 = s1c[c] * s1c[c];
            float vr = 1.0 + bp_alpha * bp_alpha * (s2c[c]*s2c[c]) / r1
                           - 2.0 * bp_alpha * (s12c[c]*s12c[c]) / r1;
            bp_norm[c] = inversesqrt(max(vr, 1e-4));
        }
        // Visible-strength hold (see VIS_VAR): each channel's kernel pair
        // against the same channel at the default kernel. Exactly 1 at the
        // defaults (uniform PARAM branch), so the calibrated look is untouched.
        vec3 field_gain = vec3(1.0);
        if (abs(grain_size - VIS_ANCHOR_SIZE) > 1.0e-6
            || abs(grain_contrast - VIS_ANCHOR_CONTRAST) > 1.0e-6
            || abs(grain_soften - VIS_ANCHOR_SOFTEN) > 1.0e-6) {
            float live_soft2 = grain_soften * grain_soften;
            float anchor_soft2 = VIS_ANCHOR_SOFTEN * VIS_ANCHOR_SOFTEN;
            float anchor_k = K_NEUTRAL * VIS_ANCHOR_SIZE;
            float anchor_alpha = BP_ALPHA * VIS_ANCHOR_CONTRAST * render_hardness;
            for (int c = 0; c < 3; c++) {
                float kl = CHANNEL_SIGMA[c] * k_size;
                float ka = CHANNEL_SIGMA[c] * anchor_k;
                vec2 e_live = dog_energy(soften_var(kl, live_soft2),
                                         soften_var(kl * BP_RATIO, live_soft2),
                                         bp_alpha);
                vec2 e_anchor = dog_energy(soften_var(ka, anchor_soft2),
                                           soften_var(ka * BP_RATIO, anchor_soft2),
                                           anchor_alpha);
                field_gain[c] = min(sqrt((e_anchor.y * e_live.x)
                                         / max(e_anchor.x * e_live.y, 1.0e-12)),
                                    VIS_GAIN_MAX);
            }
        }
        // Two per-channel sigma families since the chroma/energy calibration.
        // GRAIN_STD is the FROZEN marginal std of the historical anchor mix —
        // it stays the field_norm denominator so the calibrated saturations
        // and amplitude scale flow through to rendered power as intentional
        // character (re-anchoring it to the live mix would cancel them).
        // mix_std is the LIVE marginal std of the actual RGB mix (drifts with
        // grain_base_sat); the value-warp knee must normalize by it, or tanh
        // bites each channel at the wrong amplitude.
        const vec3 GRAIN_STD = vec3(0.16223, 0.1742, 0.15653);
        const vec3 base_luma = vec3(0.299, 0.587, 0.114);
        const vec3 noise_scale = vec3(RED_VARIANCE_SCALE,
                                      GREEN_VARIANCE_SCALE,
                                      BLUE_VARIANCE_SCALE);
        vec3 lum_mix = base_luma * noise_scale;
        vec3 sat = vec3(RED_SATURATION, GREEN_SATURATION,
                        BLUE_SATURATION) * grain_base_sat;
        vec3 mix_r = mix(lum_mix, vec3(noise_scale.r, 0.0, 0.0), sat.r);
        vec3 mix_g = mix(lum_mix, vec3(0.0, noise_scale.g, 0.0), sat.g);
        vec3 mix_b = mix(lum_mix, vec3(0.0, 0.0, noise_scale.b), sat.b);
        const float TRIANGULAR_STD = 0.612 / sqrt(6.0);
        vec3 mix_std = TRIANGULAR_STD * sqrt(vec3(
            dot(mix_r, mix_r), dot(mix_g, mix_g), dot(mix_b, mix_b)));
        // value_warp amplitude bookkeeping (uniform; computed once per frame). vsum_sigma =
        // the per-channel RMS of vsum (bp_norm makes Var(vsum)=Var(vsum1)=mix_std^2*s1c^2;
        // verified ratio 1.000 in the pre-calibration GRAIN_STD form at its own mix).
        // warp_renorm = 1/sqrt(E[tanh^2(value_warp*Z)]), Z~N(0,1),
        // via fixed Gaussian quadrature -> the tanh warp preserves grain strength (RMS
        // stable to ~1% offline). value_warp<=0.05 -> renorm 1 (the warp branch is skipped).
        vsum_sigma[0] = mix_std.x * s1c[0];
        vsum_sigma[1] = mix_std.y * s1c[1];
        vsum_sigma[2] = mix_std.z * s1c[2];
        // Canonical absolute RMS at the neutral picture-space kernel. Without
        // this normalization coarse kernels render less power and fine kernels
        // more power even when the observer requested the same sigma_plus.
        // Anchored to GRAIN_STD, deliberately NOT vsum_sigma (see above).
        field_norm[0] = 0.08318138 / max(GRAIN_STD.x * s1c[0], 1.0e-6);
        field_norm[1] = 0.06602582 / max(GRAIN_STD.y * s1c[1], 1.0e-6);
        field_norm[2] = 0.04909565 / max(GRAIN_STD.z * s1c[2], 1.0e-6);
        field_norm[0] *= field_gain.r;
        field_norm[1] *= field_gain.g;
        field_norm[2] *= field_gain.b;

        // Analytic pre-warp RGB covariance of the field this dispatch builds.
        // H.274/AV1 model grain per colour component, and the generator's
        // channel-specific size/RMS is intentional character. Keep it; carry
        // its six covariance terms so OUTPUT can prevent multiplicative
        // RGB compositing from turning that character into extra LUMA energy on
        // saturated colours. For separable 2-D kernels, every cross inner
        // product is the square of its 1-D inner product. value_warp is a
        // monotone marginal transform; the private empirical sweep finds that
        // combined legal artistic-override residuals stay within ~5%, so no fitted warp
        // heuristic belongs in this physical covariance record.
        if (gl_WorkGroupID.x == 0u && gl_WorkGroupID.y == 0u) {
        vec3 d11 = vec3(0.0), d12 = vec3(0.0);
        vec3 d21 = vec3(0.0), d22 = vec3(0.0);
        for (int i = 0; i < 2 * MAX_TAPS + 1; i++) {
            d11 += vec3(dyn_wr[i]  * dyn_wg[i],
                        dyn_wr[i]  * dyn_wb[i],
                        dyn_wg[i]  * dyn_wb[i]);
            d12 += vec3(dyn_wr[i]  * dyn_wg2[i],
                        dyn_wr[i]  * dyn_wb2[i],
                        dyn_wg[i]  * dyn_wb2[i]);
            d21 += vec3(dyn_wr2[i] * dyn_wg[i],
                        dyn_wr2[i] * dyn_wb[i],
                        dyn_wg2[i] * dyn_wb[i]);
            d22 += vec3(dyn_wr2[i] * dyn_wg2[i],
                        dyn_wr2[i] * dyn_wb2[i],
                        dyn_wg2[i] * dyn_wb2[i]);
        }
        float a2 = bp_alpha * bp_alpha;
        vec3 cross_energy = d11 * d11
                          - bp_alpha * (d12 * d12 + d21 * d21)
                          + a2 * d22 * d22;
        vec3 self_energy = vec3(
            s1c[0]*s1c[0] - 2.0*bp_alpha*s12c[0]*s12c[0] + a2*s2c[0]*s2c[0],
            s1c[1]*s1c[1] - 2.0*bp_alpha*s12c[1]*s12c[1] + a2*s2c[1]*s2c[1],
            s1c[2]*s1c[2] - 2.0*bp_alpha*s12c[2]*s12c[2] + a2*s2c[2]*s2c[2]);
        vec3 filter_corr = cross_energy * inversesqrt(max(
            vec3(self_energy.x * self_energy.y,
                 self_energy.x * self_energy.z,
                 self_energy.y * self_energy.z), vec3(1.0e-12)));

        vec3 base_corr = vec3(dot(mix_r, mix_g),
                              dot(mix_r, mix_b),
                              dot(mix_g, mix_b)) * inversesqrt(max(vec3(
            dot(mix_r, mix_r) * dot(mix_g, mix_g),
            dot(mix_r, mix_r) * dot(mix_b, mix_b),
            dot(mix_g, mix_g) * dot(mix_b, mix_b)), vec3(1.0e-12)));
        // field_norm keeps the historical GRAIN_STD anchors, so the rendered
        // field's true std is anchor * mix_std/GRAIN_STD — at the calibrated
        // defaults every channel now sits above its anchor (red most, ~1.17x)
        // and it drifts further when base_sat is changed. Derive the live
        // diagonals from the actual mix (and the amount normalization): OUTPUT
        // uses them for the colour-energy cap AND, since Phase 4, for the
        // log-normal mean correction.
        vec3 cap_field_std = vec3(0.08318138, 0.06602582, 0.04909565)
                           * mix_std / GRAIN_STD * field_gain;
        vec3 cap_field_var = cap_field_std * cap_field_std;
        // Schur product of two Gram matrices is PSD algebraically. Project the
        // third correlation into the valid interval implied by the first two;
        // this removes tiny float32 cancellation excursions at coarse/clamped
        // kernel corners without the non-PSD risk of three independent clamps.
        vec3 field_corr = base_corr * filter_corr;
        float corr_rg = clamp(field_corr.x, -1.0, 1.0);
        float corr_rb = clamp(field_corr.y, -1.0, 1.0);
        float corr_gb_mid = corr_rg * corr_rb;
        float corr_gb_span = sqrt(max((1.0 - corr_rg * corr_rg)
                                    * (1.0 - corr_rb * corr_rb), 0.0));
        float corr_gb = clamp(field_corr.z,
                              corr_gb_mid - corr_gb_span,
                              corr_gb_mid + corr_gb_span);
        m_field_cov_rg = corr_rg * cap_field_std.r * cap_field_std.g;
        m_field_cov_rb = corr_rb * cap_field_std.r * cap_field_std.b;
        m_field_cov_gb = corr_gb * cap_field_std.g * cap_field_std.b;
        m_field_var_r = cap_field_var.r;
        m_field_var_g = cap_field_var.g;
        m_field_var_b = cap_field_var.b;
        // Composite tile scale for this field (Phase 4 seam fix). The OUTPUT
        // overlap crossfade (weights w, sum of squares 1, slope delta ~ pi/2OV)
        // adds band gradient energy delta^2 (R - 1/2) relative to the
        // field's own, where R = Var / Var(lag-1 difference) is its squared
        // correlation length (the -1/2 is the w.A x dA cross term): the fixed
        // 8-sample band that is seamless on the default kernel measured up to
        // +20% on soft grain. Scale TPL_BLOCK and TPL_OV together by
        // sqrt((R - 1/2) / (TPL_R_DEFAULT - 1/2)) to hold the default's excess,
        // floored at exactly 1 so the default and every finer kernel keep
        // the historical composite. R comes from the green DoG weights, exact
        // for the discrete template (softening a bandpass field lengthens its
        // correlation faster than its inner sigma, so no sigma proxy). Band
        // fraction, fetch count and per-present cost stay flat; OV alone
        // would put ~57% of pixels in multi-fetch bands at the softest
        // setting.
        float g11 = 0.0, g22 = 0.0, g12 = 0.0;
        for (int i = 0; i < 2 * MAX_TAPS; i++) {
            g11 += dyn_wg[i] * dyn_wg[i + 1];
            g22 += dyn_wg2[i] * dyn_wg2[i + 1];
            g12 += dyn_wg[i] * dyn_wg2[i + 1];
        }
        float lag1 = g11 * s1c[1] - 2.0 * bp_alpha * g12 * s12c[1]
                   + a2 * g22 * s2c[1];
        float corr_len2 = self_energy.y
                        / max(2.0 * (self_energy.y - lag1), 1.0e-12);
        m_tpl_scale = max(1.0, sqrt(max(corr_len2 - 0.5, 0.0)
                                    / (TPL_R_DEFAULT - 0.5)));
        }
        if (value_warp > 0.05) {
            float num = 0.0, den = 0.0;
            for (int j = -16; j <= 16; j++) {
                float z = float(j) * 0.25;
                float wpdf = exp(-0.5 * z * z);
                float t = tanh(value_warp * z);
                num += wpdf * t * t; den += wpdf;
            }
            warp_renorm = inversesqrt(max(num / den, 1e-4));
        } else {
            warp_renorm = 1.0;
        }
    }
    barrier();

    // --- inner blur(s1): generate noise -> separable blur -> vsum1 ---
    if (regenerate) for (uint i = lid; i < isize.y * isize.x; i += num_threads) {
        uvec2 local_pos = uvec2(i % isize.x, i / isize.x);
        ivec2 global_coord_i = ivec2(gl_WorkGroupID.xy * gl_WorkGroupSize.xy)
                             + ivec2(local_pos) - ivec2(MAX_TAPS);
        // global_coord_i >= -MAX_TAPS, so one added grid extent keeps the
        // dividend non-negative. GLSL leaves % undefined on negative operands,
        // and NVIDIA's Vulkan compiler really does wrap them as unsigned
        // (measured 2026-09-29: the template's row 0 and column 0 took their
        // halo noise from columns 248..255 instead of 952..959 -- a torus seam
        // that showed as moving line segments, 2.3x gradient energy at the
        // defaults and 17x on soft grain).
        uvec2 global_pos = uvec2((global_coord_i + GEN_GRID) % GEN_GRID);
        uint seed_init = (global_pos.x * 1664525u) + (global_pos.y * 22695477u)
                       + (frame_seed * 314159265u);
        float g_r = rand_triangular(seed_init, RED_VARIANCE_SCALE);
        float g_g = rand_triangular(seed_init, GREEN_VARIANCE_SCALE);
        float g_b = rand_triangular(seed_init, BLUE_VARIANCE_SCALE);
        float grain_lum = dot(vec3(g_r, g_g, g_b), vec3(0.299, 0.587, 0.114));
        grain_r[local_pos.y][local_pos.x] = mix(grain_lum, g_r, RED_SATURATION * grain_base_sat);
        grain_g[local_pos.y][local_pos.x] = mix(grain_lum, g_g, GREEN_SATURATION * grain_base_sat);
        grain_b[local_pos.y][local_pos.x] = mix(grain_lum, g_b, BLUE_SATURATION * grain_base_sat);
    }
    barrier();
    // Horizontal pass, race-free: every lane reads its whole footprint into
    // registers, a barrier, THEN the centre columns are overwritten. Written
    // in place, a lane's store of column x+MAX_TAPS raced its neighbours'
    // reads of it on any SIMD width below 32 (a 32-lane row only happened to
    // sit in one NVIDIA warp / AMD wave). Each lane owns at most two rows:
    // isize.y <= 2 * gl_WorkGroupSize.y while MAX_TAPS <= 16. The barrier is
    // unconditional (regenerate is an SSBO read -> X3663).
    uint hy0 = gl_LocalInvocationID.y;
    uint hy1 = gl_LocalInvocationID.y + gl_WorkGroupSize.y;
    float h0r = 0.0, h0g = 0.0, h0b = 0.0, h1r = 0.0, h1g = 0.0, h1b = 0.0;
    if (regenerate) for (int x = 0; x < 2 * MAX_TAPS + 1; x++) {
        uint sx = gl_LocalInvocationID.x + uint(x);
        h0r += dyn_wr[x] * grain_r[hy0][sx];
        h0g += dyn_wg[x] * grain_g[hy0][sx];
        h0b += dyn_wb[x] * grain_b[hy0][sx];
        if (hy1 < isize.y) {
            h1r += dyn_wr[x] * grain_r[hy1][sx];
            h1g += dyn_wg[x] * grain_g[hy1][sx];
            h1b += dyn_wb[x] * grain_b[hy1][sx];
        }
    }
    barrier();
    if (regenerate) {
        uint cx = gl_LocalInvocationID.x + uint(MAX_TAPS);
        grain_r[hy0][cx] = h0r; grain_g[hy0][cx] = h0g; grain_b[hy0][cx] = h0b;
        if (hy1 < isize.y) {
            grain_r[hy1][cx] = h1r; grain_g[hy1][cx] = h1g; grain_b[hy1][cx] = h1b;
        }
    }
    barrier();
    float vsum1_r = 0.0, vsum1_g = 0.0, vsum1_b = 0.0;
    if (regenerate) for (int y = 0; y < 2 * MAX_TAPS + 1; y++) {
        vsum1_r += dyn_wr[y] * grain_r[gl_LocalInvocationID.y + y][gl_LocalInvocationID.x + MAX_TAPS];
        vsum1_g += dyn_wg[y] * grain_g[gl_LocalInvocationID.y + y][gl_LocalInvocationID.x + MAX_TAPS];
        vsum1_b += dyn_wb[y] * grain_b[gl_LocalInvocationID.y + y][gl_LocalInvocationID.x + MAX_TAPS];
    }

    // --- outer blur(s2) for the DoG: regenerate the SAME noise, blur with dyn_*2 ---
    // UNCONDITIONAL (barriers must not sit in varying control flow -> D3D X3663). When
    // grain_contrast=0, bp_alpha=0 so vsum2 is multiplied out in the combine (still
    // A/B-safe); we just always pay the cheap 2nd blur instead of skipping it.
    float vsum2_r = 0.0, vsum2_g = 0.0, vsum2_b = 0.0;
    barrier();
    {
        if (regenerate) for (uint i = lid; i < isize.y * isize.x; i += num_threads) {
            uvec2 local_pos = uvec2(i % isize.x, i / isize.x);
            ivec2 global_coord_i = ivec2(gl_WorkGroupID.xy * gl_WorkGroupSize.xy)
                                 + ivec2(local_pos) - ivec2(MAX_TAPS);
            uvec2 global_pos = uvec2((global_coord_i + GEN_GRID) % GEN_GRID);
            uint seed_init = (global_pos.x * 1664525u) + (global_pos.y * 22695477u)
                           + (frame_seed * 314159265u);
            float g_r = rand_triangular(seed_init, RED_VARIANCE_SCALE);
            float g_g = rand_triangular(seed_init, GREEN_VARIANCE_SCALE);
            float g_b = rand_triangular(seed_init, BLUE_VARIANCE_SCALE);
            float grain_lum = dot(vec3(g_r, g_g, g_b), vec3(0.299, 0.587, 0.114));
            grain_r[local_pos.y][local_pos.x] = mix(grain_lum, g_r, RED_SATURATION * grain_base_sat);
            grain_g[local_pos.y][local_pos.x] = mix(grain_lum, g_g, GREEN_SATURATION * grain_base_sat);
            grain_b[local_pos.y][local_pos.x] = mix(grain_lum, g_b, BLUE_SATURATION * grain_base_sat);
        }
        barrier();
        // Same race-free horizontal pass as the inner blur.
        h0r = 0.0; h0g = 0.0; h0b = 0.0; h1r = 0.0; h1g = 0.0; h1b = 0.0;
        if (regenerate) for (int x = 0; x < 2 * MAX_TAPS + 1; x++) {
            uint sx = gl_LocalInvocationID.x + uint(x);
            h0r += dyn_wr2[x] * grain_r[hy0][sx];
            h0g += dyn_wg2[x] * grain_g[hy0][sx];
            h0b += dyn_wb2[x] * grain_b[hy0][sx];
            if (hy1 < isize.y) {
                h1r += dyn_wr2[x] * grain_r[hy1][sx];
                h1g += dyn_wg2[x] * grain_g[hy1][sx];
                h1b += dyn_wb2[x] * grain_b[hy1][sx];
            }
        }
        barrier();
        if (regenerate) {
            uint cx = gl_LocalInvocationID.x + uint(MAX_TAPS);
            grain_r[hy0][cx] = h0r; grain_g[hy0][cx] = h0g; grain_b[hy0][cx] = h0b;
            if (hy1 < isize.y) {
                grain_r[hy1][cx] = h1r; grain_g[hy1][cx] = h1g; grain_b[hy1][cx] = h1b;
            }
        }
        barrier();
        if (regenerate) for (int y = 0; y < 2 * MAX_TAPS + 1; y++) {
            vsum2_r += dyn_wr2[y] * grain_r[gl_LocalInvocationID.y + y][gl_LocalInvocationID.x + MAX_TAPS];
            vsum2_g += dyn_wg2[y] * grain_g[gl_LocalInvocationID.y + y][gl_LocalInvocationID.x + MAX_TAPS];
            vsum2_b += dyn_wb2[y] * grain_b[gl_LocalInvocationID.y + y][gl_LocalInvocationID.x + MAX_TAPS];
        }
    }

    if (regenerate) {
        // Bandpass combine (DoG = blur(s1) - a*blur(s2)), RMS-normalized so
        // strength holds as measured hardness changes.
        float vsum_r = bp_norm[0] * (vsum1_r - bp_alpha * vsum2_r);
        float vsum_g = bp_norm[1] * (vsum1_g - bp_alpha * vsum2_g);
        float vsum_b = bp_norm[2] * (vsum1_b - bp_alpha * vsum2_b);

        // VALUE-DOMAIN contrast (value_warp): tanh the grain toward a
        // bimodal/high-per-grain-contrast marginal while preserving RMS.
        if (value_warp > 0.05) {
            vsum_r = vsum_sigma[0] * warp_renorm * tanh(value_warp * vsum_r / max(vsum_sigma[0], 1e-6));
            vsum_g = vsum_sigma[1] * warp_renorm * tanh(value_warp * vsum_g / max(vsum_sigma[1], 1e-6));
            vsum_b = vsum_sigma[2] * warp_renorm * tanh(value_warp * vsum_b / max(vsum_sigma[2], 1e-6));
        }
        vsum_r *= field_norm[0];
        vsum_g *= field_norm[1];
        vsum_b *= field_norm[2];

        // Final field store. The trigger dispatch rounds 540 up to 544 rows,
        // so guard the four out-of-range rows explicitly.
        ivec2 gpos = ivec2(gl_GlobalInvocationID.xy);
        if (all(lessThan(gpos, imageSize(GRAIN_FIELD)))) {
            imageStore(GRAIN_FIELD, gpos, vec4(vsum_r, vsum_g, vsum_b, 0.0));
            if (all(equal(gpos, ivec2(0))))
                m_field_valid = 1.0;
        }
    }
}

//!HOOK OUTPUT
//!BIND HOOKED
//!BIND GRAIN_STATE
//!BIND GRAIN_FIELD
//!COMPUTE 32 32
//!WHEN grain_gain match_grain * debug_match +
//!DESC Film Grain Match: OUTPUT composite + debug

// The per-present half: this is the shader's only pass in mpv's REDRAW group
// (re-runs per present -- up to display Hz under display-resample), so it
// stays fetch + key + apply. All grain generation and every scalar that used
// to be derived here (eff_render/mid/steep, conf, shape_w) now come from the
// source-locked gen pass via GRAIN_STATE / GRAIN_FIELD.
#define DENSITY_SHADOW_FLOOR 0.015
#define PICTURE_DENSITY_OUT 2160.0
// MUST equal MP_PICTURE_DENSITY / PICTURE_DENSITY in PASS 1 / PASS 2.
#define TPL_BLOCK_NORM (64.0 / 2160.0) // active-picture-height fraction
#define TPL_OV_NORM    (8.0 / 2160.0)  // active-picture-height fraction
#define TPL_BLOCK (TPL_BLOCK_NORM * PICTURE_DENSITY_OUT)
#define TPL_OV    (TPL_OV_NORM * PICTURE_DENSITY_OUT)
#define MP_TONE_BINS_OUT 8
#define MP_FIELD_STD_OUT 0.0185
// MUST equal PASS 1's MP_STATE_MAGIC (same translation-unit-sync rule as
// MP_TONE_BINS_OUT / MP_FIELD_STD_OUT — no compile guard exists).
#define MP_STATE_MAGIC_OUT 0.956340
// MUST equal PASS 1's MEASURE_BLACK / MEASURE_WHITE (same no-guard sync rule).
#define MEASURE_BLACK_OUT (16.0 / 255.0)
#define MEASURE_WHITE_OUT (235.0 / 255.0)
// grain_floor's unit, FROZEN on purpose: the summed added power over the
// rendered tone bins 2-7 (restore_gain 1) of a clean title at the defaults --
// 0.67 x the no-evidence reset level (My Gift / Dandadan settle at 0.81-0.83
// in sigma), from the 2026-09-30 survey. A literal, not derived from PASS 1's
// prior constants: retuning those later must not silently rescale every
// profile's grain_floor. Do not retune.
#define FLOOR_UNIT_SUM 7.059e-7
// Lowering the floor eases out between the clean level (1) and this amount
// (sigma units of FLOOR_UNIT_SUM): titles the engine recognises as grainy
// sit at ~1.7-7 once learned (5B replay, 2026-09-30) and keep their amount.
#define FLOOR_KNEE 1.5

const vec3 luma_coeff = vec3(0.2126, 0.7152, 0.0722);

// Workgroup snapshot of every GRAIN_STATE scalar the live path reads. On the
// NVIDIA Vulkan driver the per-pixel SSBO loads in this pass measured
// ~0.75 ms/present at 4K — 6x the entire remaining pass — while the same
// code through SPIRV-Cross/FXC on d3d11 shows no such cost (mechanism
// unproven; end-to-end probes in win-harness results-0723-matchgrain-audit).
// One lane loads the state, a
// barrier publishes it, and every pixel reads shared memory instead. The
// values are the same fp32 bits, so output is bit-identical. The barrier
// sits in uniform control flow BEFORE every data-dependent return below,
// which keeps FXC/D3D11 X3663-legal (no barrier is ever reached in varying
// flow; this pass has no other barriers).
shared float s_state_magic;
shared float s_state_epoch;
shared float s_field_valid;
shared float s_eff_render;
shared float s_active_inset_x;
shared float s_active_inset_y;
shared float s_arr_seed;
shared float s_field_var_r;
shared float s_field_var_g;
shared float s_field_var_b;
shared float s_field_cov_rg;
shared float s_field_cov_rb;
shared float s_field_cov_gb;
shared float s_tpl_scale;
shared float s_tpl_block;
shared float s_tpl_ov;
shared float s_tpl_block_inv;
shared float s_tpl_ov_inv;
shared float s_char_p[8];
shared float s_restore_p[8];
shared float s_floor_lift2;

// --- HDR (PQ BT.2020 output) domain bridge, grain_hdr=1. The grain model is
// measured on gamma-encoded SDR source codes at LUMA, but whenever the player
// target is PQ (target-trc=pq, or an HDR-signalled display) the OUTPUT pixels
// this pass receives are true PQ BT.2020: libplacebo runs OUTPUT hooks AFTER
// the conversion to the target colorspace, so measure and apply sit in
// different domains unless we bridge. Bridge per pixel: PQ code -> linear
// BT.2020 nits -> linear BT.709 -> SDR-equivalent 2.4-gamma code vs
// grain_ref_white; key + apply the model there; convert back and re-encode.
// Grain lands exactly as the SDR path wherever the image sits at SDR levels
// and fades to zero shortly above reference white (the measured bell
// extrapolates; grain does not persist into expanded highlight cores).
// Standard ST 2084 constants, so the round trip shares a transfer with
// whatever performed the PQ encode upstream.
float pq_eotf_nits(float e) {
    float p = pow(e, 1.0 / 78.84375);
    return 10000.0 * pow(max(p - 0.8359375, 0.0)
                         / (18.8515625 - 18.6875 * p), 1.0 / 0.1593017578125);
}
float pq_oetf_code(float nits) {
    float y = pow(clamp(nits / 10000.0, 0.0, 1.0), 0.1593017578125);
    return pow((0.8359375 + 18.8515625 * y) / (1.0 + 18.6875 * y), 78.84375);
}

vec3 bt2020_to_bt709(vec3 rgb) {
    return vec3(
         1.6604903 * rgb.r - 0.5876391 * rgb.g - 0.0728516 * rgb.b,
        -0.1245500 * rgb.r + 1.1328999 * rgb.g - 0.0083480 * rgb.b,
        -0.0181511 * rgb.r - 0.1005787 * rgb.g + 1.1187299 * rgb.b
    );
}

vec3 bt709_to_bt2020(vec3 rgb) {
    return vec3(
        0.6274040 * rgb.r + 0.3292820 * rgb.g + 0.0433136 * rgb.b,
        0.0690970 * rgb.r + 0.9195400 * rgb.g + 0.0113612 * rgb.b,
        0.0163916 * rgb.r + 0.0880132 * rgb.g + 0.8955950 * rgb.b
    );
}

vec3 signed_pow(vec3 v, float p) {
    return sign(v) * pow(abs(v), vec3(p));
}

float matched_grain_scale(float lum, float hdr_mode) {
    // Curves store absolute independent power per exposure-weighted tone bin.
    // PASS 1 learns the bins on raw LIMITED-range LUMA codes (black ~16/255),
    // while `lum` here is full-range work-domain luma. Read the bins in PASS 1's
    // coordinate, or every shadow pixel lands up to 1.5 bins darker than the
    // bin that measured it (0.55 bins at L = 0.1; the 2026-09-29 audit). Only
    // the bin coordinate moves -- sigma units and every calibrated constant stay.
    // Video black sits at the bin-2 edge, so bins 0-1 hold only sub-black codes
    // and never gather evidence: floor the lookup at bin 2's centre (~L 0.04,
    // ~0.37 nits at ref white 116) so no pixel reads a bin that cannot be
    // measured. Below it the shadow toe still carries grain down to black.
    float lum_meas = MEASURE_BLACK_OUT
                   + clamp(lum, 0.0, 1.0) * (MEASURE_WHITE_OUT - MEASURE_BLACK_OUT);
    float p = clamp(sqrt(lum_meas)
                    * float(MP_TONE_BINS_OUT) - 0.5,
                    2.0, float(MP_TONE_BINS_OUT - 1));
    int i0 = int(floor(p));
    int i1 = min(i0 + 1, MP_TONE_BINS_OUT - 1);
    float char_p = mix(s_char_p[i0], s_char_p[i1], fract(p));
    float restore_p = mix(s_restore_p[i0], s_restore_p[i1], fract(p));
    // Keep this expression identical to PASS 1's p_total accounting.
    float power = char_p + restore_gain * restore_gain * restore_p;
    if (abs(grain_floor - 1.0) > 1.0e-6)
        power *= s_floor_lift2;
    float sigma = grain_gain * match_grain * sqrt(max(power, 0.0));
    float black_lo = mix(0.0010, 0.00025, hdr_mode);
    float black_hi = mix(0.0120, 0.00600, hdr_mode);
    // ONE fade-to-white envelope for every chain (author decree 2026-07-19,
    // resolving charter-audit P2): between the shadow toe and upper fade,
    // amount follows the rendition curve -- no aesthetic shoulder.
    // grain_fade sets the work-domain luma where grain reaches zero.
    // Clip-limited chains (plain
    // SDR, or SDR in a PQ container) cap the top at 0.95: waiting for
    // mathematical 1.0 leaves a tiny moving tail in perceptual whites --
    // temporal playback reveals it and channel clipping makes it one-sided
    // (the 2026-07-15 live-white finding). With headroom the top is the
    // user's above-ref-white reach; we cannot know the upstream expansion's
    // tuning.
    // The approach into the fade is sized in photographic stops, so its
    // perceptual width cannot collapse as the knob moves down. Through the
    // content range (top <= 0.70) the fade spans start/top = 0.60 -- about
    // 1.8 stops in the 2.4-gamma work domain, the width of a natural
    // sensitometric rolloff. Over top 0.70 to 0.95 the ratio tightens
    // smoothly to the stock 0.96/1.10 half-stop band, so every default
    // (0.95-capped clip-limited chains and the 1.10 stock top) renders
    // exactly the stock geometry: content up there is sparse and the
    // live-white finding wants a decisive end near clip.
    // Motivation (2026-08-20 sky finding): the old proportional geometry at
    // grain_fade 0.5 left a 0.064-wide band (16->22 nits at ref white 116)
    // and a smooth sky gradient crossed it in ~400 px, so grain read as
    // switching off along an iso-luma line.
    // Aggressive low fades still need a matching shadow toe: the stock black
    // gate reaches full grain almost immediately, which makes a 0.2 fade
    // read as a hard band. Below 0.5, widen the rise and shorten its
    // full-strength shelf.
    // OUTPUT's per-channel clip room keys on the same top; the black gate
    // stays container-keyed on hdr_mode.
    float white_hdr = hdr_mode * step(0.5, grain_headroom);
    // Floored at the declared PARAM minimum: a degenerate override would
    // collapse the smoothstep edges below (NaN through the whole tone scale).
    float fade_user = max(grain_fade, 0.1875);
    float fade_top = mix(min(fade_user, 0.95), fade_user, white_hdr);
    float low_fade_q = 1.0 - smoothstep(0.30, 0.50, fade_top);
    float wide_q = 1.0 - smoothstep(0.70, 0.95, fade_top);
    float toe_hi = mix(black_hi, max(black_hi, 0.45 * fade_top), low_fade_q);
    float fade_start = fade_top * mix(0.96 / 1.10, 0.60, wide_q);
    float shadow_toe = smoothstep(black_lo, toe_hi, lum);
    float white_fade = 1.0 - smoothstep(fade_start, fade_top, lum);
    float protection = shadow_toe * white_fade;
    float scale = sigma / MP_FIELD_STD_OUT * protection;
    // Returns the UNCLAMPED scale; each combine arm applies its own 8.0 roof
    // at the call site (the density arm after its luma compensation). Clamping
    // here first would change the density arm whenever scale > 8 and the
    // work-domain luma is > 1 (PQ headroom chains at high grain_gain).
    return scale;
}

// Multiplicative RGB density is not automatically colour-energy neutral. The
// small-signal working-code luma perturbation is
//   dY = scale * (luma_coeff * carrier_rgb)^T * grain_rgb,
// so its variance is q^T C q. On a neutral at the same luma q is simply the
// neutral level times luma_coeff. Because this generator intentionally gives
// red the strongest/finer component, an uncapped path makes equal-luma
// saturated red ~1.44x neutral RMS at the calibrated defaults (up to ~1.63x
// at grain_base_sat=1; pre-calibration these were ~1.24x/~1.42x).
// Carry the generator's current covariance and reduce only chromaticities above
// the neutral reference. Never boost a quiet direction: H.274/AV1 permit real
// per-component grain character, and this correction removes compositing bias
// rather than flattening that character. Signed-gamma out-of-709 intermediates
// in the PQ bridge must stay signed: their cancellation is part of the actual
// BT.709-domain luma perturbation, while the downward-only cap cannot amplify it.
// The covariance describes the canonical 2160-sample field; OUTPUT footprint
// filtering can make lower-density presentations more conservative, so this is
// a no-excess cap rather than a claim of resolution-wide exact normalization.
float density_colour_energy_cap(vec3 carrier) {
    float neutral_level = max(dot(luma_coeff, carrier), 0.0);
    if (neutral_level <= 1.0e-8)
        return 1.0;
    vec3 q = luma_coeff * carrier;
    vec3 cap_field_var = vec3(s_field_var_r, s_field_var_g, s_field_var_b);
    float carrier_var = dot(q * q, cap_field_var)
        + 2.0 * (q.r * q.g * s_field_cov_rg
               + q.r * q.b * s_field_cov_rb
               + q.g * q.b * s_field_cov_gb);
    float neutral_var = neutral_level * neutral_level * (
          dot(luma_coeff * luma_coeff, cap_field_var)
        + 2.0 * (luma_coeff.r * luma_coeff.g * s_field_cov_rg
               + luma_coeff.r * luma_coeff.b * s_field_cov_rb
               + luma_coeff.g * luma_coeff.b * s_field_cov_gb));
    if (carrier_var <= neutral_var * 1.0001)
        return 1.0;
    return min(sqrt(max(neutral_var, 0.0)
                  / max(carrier_var, 1.0e-12)), 1.0);
}

// Wrapped point fetch of the toroidal grain template. GRAIN_FIELD is a
// persistent storage image, so every filtered read below is assembled from
// explicit imageLoads. Coordinates are implementation-lattice samples, with
// sample i centred at i+0.5. Exact-density samples therefore retain the
// historical one-load path, while arbitrary footprint taps use the correct
// half-texel cell boundaries.
vec3 grain_point(vec2 fpos, ivec2 g) {
    ivec2 i = ivec2(floor(fpos));
    // Callers guarantee a non-negative coordinate (tpl_sample adds one
    // template extent for exactly this reason), so a single mod is
    // wrap-identical — and with the compile-time g it strength-reduces to
    // multiply/shift instead of a per-tap integer divide.
    ivec2 a = i % g;                            // component-wise wrap
    return imageLoad(GRAIN_FIELD, a).rgb;
}

// Same PCG as the measure/gen units — the block shuffle below needs a few
// decorrelated words per visible tick.
uint pcg_hash(uint s) {
    uint state = s * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

// One block's grain sample for an arbitrary picture-lattice position pos. b is
// the block index doing the presenting (possibly a neighbour of pos's own
// block, when evaluating the overlap bands — intra then exceeds [0,BLOCK)
// and the flip can go one period negative, bounded by -s_tpl_ov, <= 32). One template
// extent is added before the fetch so the coordinate reaching grain_point's
// integer wrap is ALWAYS non-negative: GLSL leaves % formally undefined on
// negative operands, and since the 2026-07-23 perf round grain_point uses a
// SINGLE mod — this add IS the correctness guarantee, not a spec nicety.
// Removing it would feed negative coords to the wrap (worst case intra
// >= -s_tpl_ov on flipped neighbour-overlap samples) and break the torus.
// Each block shows a randomized (integer offset + H/V
// flip) window of the toroidal template, re-hashed per visible tick.
vec3 tpl_sample(vec2 b, vec2 pos, float vseed, ivec2 g) {
    vec2 intra = pos - b * s_tpl_block;
    uint h0 = pcg_hash(uint(int(b.x)) * 374761393u
                     + uint(int(b.y)) * 3266489917u
                     + uint(vseed) * 668265263u);
    uint h1 = pcg_hash(h0);
    uint h2 = pcg_hash(h1);
    if ((h2 & 1u) != 0u) intra.x = s_tpl_block - intra.x;
    if ((h2 & 2u) != 0u) intra.y = s_tpl_block - intra.y;
    return grain_point(intra + vec2(g)
                       + vec2(float(h0 % uint(g.x)), float(h1 % uint(g.y))), g);
}

// Evaluate one point of the assembled picture-space field. Filtering must call this
// complete evaluator for every tap: filtering only the physical template and
// reusing the centre pixel's block weights would crossfade the wrong shuffled
// windows at block boundaries.
vec3 picture_point(vec2 pos, float vseed, ivec2 g) {
    vec2 b = floor(pos * s_tpl_block_inv);
    vec2 u = pos - b * s_tpl_block;
    float wxp = 0.0, wxc = 1.0, wyp = 0.0, wyc = 1.0;
    if (u.x < s_tpl_ov) {
        float t = u.x * s_tpl_ov_inv * 1.5707963;
        wxp = cos(t); wxc = sin(t);
    }
    if (u.y < s_tpl_ov) {
        float t = u.y * s_tpl_ov_inv * 1.5707963;
        wyp = cos(t); wyc = sin(t);
    }
    vec3 field = (wxc * wyc) * tpl_sample(b, pos, vseed, g);
    if (wxp > 0.0)
        field += (wxp * wyc)
               * tpl_sample(b - vec2(1.0, 0.0), pos, vseed, g);
    if (wyp > 0.0)
        field += (wyp * wxc)
               * tpl_sample(b - vec2(0.0, 1.0), pos, vseed, g);
    if (wxp > 0.0 && wyp > 0.0)
        field += (wxp * wyp)
               * tpl_sample(b - vec2(1.0, 1.0), pos, vseed, g);
    return field;
}

// Tent reconstruction of the canonical field for outputs denser than its
// picture-space lattice. This reveals the same finite-bandwidth field at more
// sample points instead of repeating lattice samples. At an exact sample centre
// fract() is zero and the result is exactly picture_point(base).
vec3 picture_linear(vec2 pos, float vseed, ivec2 g) {
    vec2 base = floor(pos - 0.5) + 0.5;
    vec2 f = pos - base;
    vec3 c00 = picture_point(base, vseed, g);
    vec3 c10 = picture_point(base + vec2(1.0, 0.0), vseed, g);
    vec3 c01 = picture_point(base + vec2(0.0, 1.0), vseed, g);
    vec3 c11 = picture_point(base + vec2(1.0, 1.0), vseed, g);
    return mix(mix(c00, c10, f.x), mix(c01, c11, f.x), f.y);
}

// Sample the continuous picture field through one output-pixel footprint.
// output_step is the output pixel width in implementation-lattice samples:
//   == 1: one point per lattice sample
//    < 1: tent reconstruction between canonical samples
//  1..1.25: the same tent is the deterministic narrow-footprint integral
//   > 1.25: stratified integration, growing the grid with footprint span
// Adjacent estimators crossfade over a short transition so changing window
// height cannot produce a visible grain-power step. The transitions are
// deliberately NARROW (0.05 step-units, was 0.25): inside a blend every
// pixel pays BOTH estimators, and common scope presentations sit statically
// right in the old tent+grid2 band (2.39:1 on a 16:9 display = step 1.34 ->
// 8 evaluations/pixel, measured 2.8x the flat-content composite). A narrow
// blend keeps resize continuity while static content lands on one 4-tap
// estimator; per-tick re-arrangement makes the estimator handoff itself
// statistically invisible (perf audit 2026-07-23, results-0723-matchgrain-
// audit).
// The footprint average deliberately loses only power the output grid cannot
// resolve; it is not RMS-renormalized. A source/output gain would make the
// grain a property of the user's scaler instead of the title.
vec3 picture_grid2(vec2 centre, float output_step, float vseed, ivec2 g) {
    vec2 d = vec2(0.25 * output_step);
    return 0.25 * (
          picture_point(centre + vec2(-d.x, -d.y), vseed, g)
        + picture_point(centre + vec2( d.x, -d.y), vseed, g)
        + picture_point(centre + vec2(-d.x,  d.y), vseed, g)
        + picture_point(centre + vec2( d.x,  d.y), vseed, g));
}

vec3 picture_grid3(vec2 centre, float output_step, float vseed, ivec2 g) {
    vec3 integrated = vec3(0.0);
    for (int y = 0; y < 3; ++y) {
        for (int x = 0; x < 3; ++x) {
            vec2 q = (vec2(float(x), float(y)) + 0.5) / 3.0 - 0.5;
            integrated += picture_point(centre + q * output_step, vseed, g);
        }
    }
    return integrated * (1.0 / 9.0);
}

vec3 picture_grid4(vec2 centre, float output_step, float vseed, ivec2 g) {
    vec3 integrated = vec3(0.0);
    for (int y = 0; y < 4; ++y) {
        for (int x = 0; x < 4; ++x) {
            vec2 q = (vec2(float(x), float(y)) + 0.5) / 4.0 - 0.5;
            integrated += picture_point(centre + q * output_step, vseed, g);
        }
    }
    return integrated * (1.0 / 16.0);
}

vec3 picture_pixel(vec2 centre, float output_step, float vseed, ivec2 g) {
    if (abs(output_step - 1.0) < 0.000001)
        return picture_point(centre, vseed, g);
    if (output_step < 1.25)
        return picture_linear(centre, vseed, g);

    // Keep the sample pitch near one implementation-lattice sample as output gets
    // smaller. The grid grows while output pixel count falls by the same
    // square factor, so 720p/540p gain accuracy without an exploding total
    // evaluator count. Four per axis is the deliberate portability ceiling;
    // very small outputs remain an approximation rather than compiling large
    // dynamic loops into every backend.
    if (output_step < 1.30) {
        float t = smoothstep(1.25, 1.30, output_step);
        return mix(picture_linear(centre, vseed, g),
                   picture_grid2(centre, output_step, vseed, g), t);
    }
    if (output_step < 2.25)
        return picture_grid2(centre, output_step, vseed, g);
    if (output_step < 2.30) {
        float t = smoothstep(2.25, 2.30, output_step);
        return mix(picture_grid2(centre, output_step, vseed, g),
                   picture_grid3(centre, output_step, vseed, g), t);
    }
    if (output_step < 3.25)
        return picture_grid3(centre, output_step, vseed, g);
    if (output_step < 3.30) {
        float t = smoothstep(3.25, 3.30, output_step);
        return mix(picture_grid3(centre, output_step, vseed, g),
                   picture_grid4(centre, output_step, vseed, g), t);
    }
    return picture_grid4(centre, output_step, vseed, g);
}

void hook() {
    // Publish the per-frame state snapshot before any return: the barrier
    // must dominate every exit path (FXC uniformity), and mpv dispatches
    // whole workgroups, so every lane reaches it.
    if (gl_LocalInvocationIndex == 0u) {
        s_state_magic = m_state_magic;
        s_state_epoch = m_state_epoch;
        s_field_valid = m_field_valid;
        s_eff_render = m_eff_render;
        s_active_inset_x = m_active_inset_x;
        s_active_inset_y = m_active_inset_y;
        s_arr_seed = m_arr_seed;
        s_field_var_r = m_field_var_r;
        s_field_var_g = m_field_var_g;
        s_field_var_b = m_field_var_b;
        s_field_cov_rg = m_field_cov_rg;
        s_field_cov_rb = m_field_cov_rb;
        s_field_cov_gb = m_field_cov_gb;
        // Tile geometry of the stored field (PASS 2 writes it with the field;
        // never re-derived here). Valid state peaks near 3.5 (size >= 2,
        // contrast 0, soften 2.5); the clamp only guards torn/foreign state.
        // Reciprocals keep the per-tap divides out of picture_point (exact at
        // the default scale 1: 1/64 and 1/8).
        s_tpl_scale = clamp(m_tpl_scale, 1.0, 4.0);
        s_tpl_block = TPL_BLOCK * s_tpl_scale;
        s_tpl_ov = TPL_OV * s_tpl_scale;
        s_tpl_block_inv = 1.0 / s_tpl_block;
        s_tpl_ov_inv = 1.0 / s_tpl_ov;
        float floor_have = 0.0;
        for (int i = 0; i < 8; i++) {
            s_char_p[i] = m_char_p[i];
            s_restore_p[i] = m_restore_p[i];
            // Rendered bins only: the lookup floors at bin 2 (sub-black below).
            if (i >= 2) floor_have += s_char_p[i] + s_restore_p[i];
        }
        // grain_floor: ONE factor per title, moving the engine's built-in
        // minimum. a = the title's added amount at restore_gain 1 in sigma
        // units of the clean level (clean titles sit at ~1.0). Raising (L > 1)
        // lifts titles below L to L; lowering (L < 1) scales titles at or
        // below the clean level by L and ramps back to identity at
        // FLOOR_KNEE, continuous and monotone, so no title steps while the
        // engine learns it. Every pixel's live power scales by the same
        // factor: the learned tone shape is kept (a per-bin max raised only
        // the tails of partially credited titles -- review 2026-09-30) and
        // restore_gain / grain_gain remain relative trims on top. Uniform
        // PARAM branch: 1 is exact.
        s_floor_lift2 = 1.0;
        if (abs(grain_floor - 1.0) > 1.0e-6) {
            float a = sqrt(max(floor_have, 1.0e-12) / FLOOR_UNIT_SUM);
            float lvl = max(grain_floor, 0.0);
            float a_new = a;
            if (lvl > 1.0)
                a_new = max(a, lvl);
            else if (a <= 1.0)
                a_new = a * lvl;
            else if (a < FLOOR_KNEE)
                a_new = lvl + (a - 1.0) * (FLOOR_KNEE - lvl) / (FLOOR_KNEE - 1.0);
            s_floor_lift2 = (a_new * a_new) / (a * a);
        }
    }
    barrier();

    vec4 color = HOOKED_tex(HOOKED_pos);

    // State-validity guard: on RGB / no-LUMA sources PASS 1 never runs, so
    // GRAIN_STATE holds whatever the API left there (zero-init is common but
    // not guaranteed; NaN m_eff_render would pass a <=0 gate as false). An
    // unvalidated state must passthrough unconditionally — including debug,
    // whose rows would render garbage.
    if (!(abs(s_state_magic - MP_STATE_MAGIC_OUT) < 0.0001
          && abs(s_state_epoch - state_epoch) < 0.5
          && s_field_valid > 0.5)) {
        imageStore(out_image, ivec2(gl_GlobalInvocationID), color);
        return;
    }

    // Real zero path: avoid template reads and, in HDR mode, avoid a decode/encode
    // round trip. PASS 1 still observes, so later evidence opens without a toggle-
    // induced reset or stale EMA.
    if ((grain_gain <= 0.0 || match_grain <= 0.0 || s_eff_render <= 0.0)
        && debug_match <= 0.5) {
        imageStore(out_image, ivec2(gl_GlobalInvocationID), color);
        return;
    }

    // Source-locked grain lives in canonical active-picture coordinates: the
    // committed baked-picture rectangle (letterbox/pillarbox burned into the
    // video) nested inside the canvas. The canvas IS the displayed picture:
    // libplacebo runs OUTPUT hooks on the target crop, so display padding never
    // reaches this pass (probed 2026-09-30: contain, pos, cover-crop). The old
    // raster recovery assumed padding and, from the LUMA storage aspect,
    // masked real picture out -- anamorphic 720x480 SAR 32:27 lost 15.6% of
    // its width, a cover crop 10% of its rows. Known gap (older than 5A): the
    // baked inset is a fraction of the whole LUMA raster, so with panscan /
    // video-zoom / video-crop on a source WITH baked bars the mask lands inside
    // the cropped picture. Mapping through the source crop would need the
    // LUMA dimensions in the state; not built.
    ivec2 gid = ivec2(gl_GlobalInvocationID.xy);
    // Compile-time template dims: constant divisors let every wrap mod below
    // strength-reduce to multiply/shift instead of a hardware integer divide
    // per tap. MUST equal the GRAIN_FIELD SIZE directive and PASS 2's
    // GEN_GRID (960 x 540) — same hand-kept lockstep rule, no compile guard.
    ivec2 gsize = ivec2(960, 540);
    vec2 raster_size = HOOKED_size;
    vec2 raster_origin = vec2(0.0);
    vec2 baked_inset = clamp(vec2(s_active_inset_x, s_active_inset_y),
                             vec2(0.0), vec2(0.24));
    vec2 active_origin = raster_origin + baked_inset * raster_size;
    vec2 active_size = (vec2(1.0) - 2.0 * baked_inset) * raster_size;
    vec2 pixel_centre = vec2(gid) + 0.5;
    // The detector commits the last confirmed matte sample. For masking,
    // advance half a refinement substep and round inward to whole pixels so
    // lifted mattes and bar-resident subtitles cannot receive grain.
    vec2 mask_guard = vec2(baked_inset.x > 0.0 ? 1.0 / 1024.0 : 0.0,
                           baked_inset.y > 0.0 ? 1.0 / 1024.0 : 0.0);
    vec2 mask_lo = ceil(raster_origin
                      + (baked_inset + mask_guard) * raster_size);
    vec2 mask_hi = floor(raster_origin + raster_size
                       - (baked_inset + mask_guard) * raster_size);
    // The debug overlay may sit over a bar: only its own rectangle bypasses
    // the matte mask (the rest of the frame stays exactly as rendered). These
    // literals MUST match the overlay's X_OFF/Y_OFF/ANCHOR/NBITS/BW/NROWS/BH
    // at the end of this pass (hand-kept lockstep; that block's text is
    // pinned by external decoders, so it keeps its own constants).
    bool in_overlay = debug_match > 0.5
                   && gid.x >= 24 && gid.x < 24 + 10 + 16 * 10
                   && gid.y >= 400 && gid.y < 400 + 52 * 10;
    if (!in_overlay
        && (float(gid.x) < mask_lo.x || float(gid.y) < mask_lo.y
         || float(gid.x) >= mask_hi.x || float(gid.y) >= mask_hi.y)) {
        imageStore(out_image, gid, color);
        return;
    }
    // gscale maps normalized picture coordinates onto the finite synthesis
    // lattice, not the physical template extent;
    // gsize only wraps each shuffled template-window fetch.
    float gscale = PICTURE_DENSITY_OUT / max(active_size.y, 1.0);
    // Snap to a whole lattice step when within 6%: just off an integer the
    // tent/grid estimators paint a STATIC strength pattern (the jitter moves
    // the lattice in whole texels only) and lose ~10% RMS -- a 24.7 px beat for
    // 1.85:1 at 4K, 269 px for a 2 px inset (audit 2026-09-30). The usual case
    // is a step just ABOVE an integer (a letterboxed or cropped picture: 1.04
    // -> 1, 2.08 -> 2), where the snapped grain is up to 6% COARSER relative to
    // picture height and its amount returns to the integer-step value (+11%
    // over the unsnapped tent); below the size JND. Just outside the window the
    // beat remains (e.g. 1.90:1 at 4K, step 1.069: cv 0.026, 14.5 px) -- open.
    float gscale_n = max(1.0, floor(gscale + 0.5));
    if (abs(gscale / gscale_n - 1.0) < 0.06)
        gscale = gscale_n;
    vec2 fpos = (pixel_centre - active_origin) * gscale;
    // KNOWN LIMIT (audit 2026-07-10): the overlap blend preserves variance
    // exactly (weights' squares sum to 1) but not DISTRIBUTION SHAPE — a
    // weighted sum of independent warped samples is more Gaussian than its
    // inputs. At value_warp >= ~2 (deliberately bimodal grain) the bands
    // carry measurably softer per-grain contrast (kurtosis 1.19 -> 1.65
    // edge / 2.01 corner at warp 3), on ~23% of pixels. Invisible in
    // playback (the jitter moves the bands every tick) and borderline even
    // in adversarial freeze-frames at gain 12; accepted freeze-frame-only
    // limitation rather than restructuring warp to post-blend.
    // TPL BLOCK SHUFFLE (small-template architecture, AV1-FGS style).
    // Each normalized TPL_BLOCK-sized tile of the picture field presents a per-tile
    // randomized (integer offset + H/V flip) window of the physical
    // template, re-hashed every visible tick (see tpl_sample above).
    // Per-grain statistics are the template's texels (same DoG/warp
    // pipeline at the implementation-lattice scale); only the long-range arrangement
    // reuses template windows. The per-tick rehash supersedes the
    // full-size build's whole-field recycle transform. Two seam defenses,
    // both empirically required (dev/grain-genrate/README):
    //  - per-tick LATTICE PHASE JITTER (whole-texel origin shift hashed
    //    from vseed) so the boundary discontinuity never sits on the same
    //    pixels twice — kills temporal accumulation of the 64px grid;
    //  - AV1-style OVERLAP BLEND: within TPL_OV texels past a boundary,
    //    crossfade from the neighbour block's window with cos/sin weights
    //    (sum of squares = 1 -> grain RMS exactly preserved; adjacent
    //    windows are independent template regions) — kills the
    //    freeze-frame lattice (in-block DoG correlation breaks at raw
    //    boundaries: measured 1.65x gradient energy without this).
    // Continuity: at u == 0 the sample IS the neighbour's (weight 1) and
    // ramps out by u == TPL_OV. Outside the bands it is a single exact
    // texel fetch at gscale == 1.0, as before.
    float vseed = s_arr_seed;
    uint j0 = pcg_hash(uint(vseed) * 2246822519u + 3u);
    // Whole-texel jitter across one (scaled) block period.
    vec2 fj = fpos + floor(vec2(float(j0 % 64u), float((j0 >> 8) % 64u))
                           * s_tpl_scale);
    vec3 vsum = picture_pixel(fj, gscale, vseed, gsize);

    // grain_hdr bridge: work in the measured SDR domain (see helpers above).
    // Clamp the PQ input codes — YUV->RGB overshoot above 1.0 explodes the
    // PQ EOTF. SDR-equivalent codes may exceed 1.0 wherever an upstream stage
    // expanded highlights above reference white; the tone bell extrapolates
    // toward zero grain shortly above 1.0.
    bool hdr_bridge = grain_hdr > 0.5;
    vec3 work_rgb = color.rgb;
    if (hdr_bridge) {
        vec3 nits = vec3(pq_eotf_nits(clamp(color.r, 0.0, 1.0)),
                         pq_eotf_nits(clamp(color.g, 0.0, 1.0)),
                         pq_eotf_nits(clamp(color.b, 0.0, 1.0)));
        vec3 linear_709 = bt2020_to_bt709(nits / grain_ref_white);
        // An upstream SDR→HDR expansion usually stays inside the source 709
        // gamut, but wide-gamut expansion can produce valid 2020 colors
        // outside it. A signed gamma
        // extension preserves those negative intermediate 709 components so
        // the inverse/forward matrix pair remains a round trip.
        work_rgb = signed_pow(linear_709, 1.0 / 2.4);
    }

    float color_luma = dot(work_rgb, luma_coeff);
    float hdr_mode = hdr_bridge ? 1.0 : 0.0;
    float tone_raw = matched_grain_scale(color_luma, hdr_mode);
    float tone_add = min(tone_raw, 8.0);
    // Density multiplication contributes one factor of luma. Divide it back
    // out so the absolute master-power curve survives into shadows; the small
    // floor hands off continuously to the mean-neutral pedestal below. Each
    // arm takes the 8.0 roof on its OWN scale (pre-mix parity: the density
    // roof applied after the division, and still does).
    float tone_den = min(tone_raw / max(color_luma, 0.015), 8.0);
    vec3 pre_grain = work_rgb;
    // density_combine is a MIX, not a switch (2026-08-22): 0 = additive,
    // 1 = multiplicative density, between = linear blend of the two deltas.
    // Both arms ride the same field sample, so they are near-perfectly
    // correlated and the blended RMS interpolates linearly (no sqrt dip at
    // 0.5); on a neutral carrier the density delta equals the additive one to
    // first order, so what the mix actually interpolates is the per-channel
    // carrier weighting on saturated colours, the log-normal bright-biased
    // skew, and the shadow handling (0.015 floor + pedestal vs flat). The
    // headroom clamps below apply to the blended delta unchanged.
    float density_w = clamp(density_combine, 0.0, 1.0);
    vec3 grain_delta = vsum * tone_add;
    // Expected per-channel grain sigma of the delta (field std x tone scale),
    // for the clip-room gain below. Density rides the carrier, so its sigma
    // scales with each channel's own level.
    vec3 field_sd = sqrt(max(vec3(s_field_var_r, s_field_var_g, s_field_var_b),
                             vec3(0.0)));
    vec3 delta_sd = tone_add * field_sd;
    if (density_w > 0.0) {
        // Density multiplication cannot express an absolute noise floor below
        // the 0.015 divisor. Extend near-black values with the same zero-mean
        // RGB perturbation; literal black and mattes stay exact through the
        // shadow toe (zero tone scale there).
        float pedestal_signal = max(DENSITY_SHADOW_FLOOR
                                  - max(color_luma, 0.0), 0.0);
        vec3 x = vsum * tone_den;
        // Canonical-field log-normal bias correction, on the LIVE per-channel
        // variance of the stored field (PASS 2's diagonals: mix, amount
        // normalization and saturation included). It was a static triple
        // pinned at base_sat 0.75 and the default kernel; Phase 4's shape
        // knobs move raw variance 0.3-2.3x and base_sat up to 2 moves blue's
        // 4.5x, and a stale variance shifts the density mean (brighter when
        // too small, darker when too large). Footprint filtering changes
        // covariance, so non-native-density presentations retain a small
        // conservative bias rather than pretending sum(weights^2) is exact
        // for the correlated DoG field.
        vec3 density_delta = exp(x - 0.5 * tone_den * tone_den
                               * vec3(s_field_var_r, s_field_var_g, s_field_var_b)) - 1.0;
        vec3 density_grain = work_rgb * density_delta;
        density_grain += vec3(pedestal_signal) * density_delta;
        // One post-density scalar preserves channel log-normal shapes, their
        // zero-mean correction, hue speckle, and spatial/RMS ratios exactly.
        // At a neutral carrier the cap is identically one.
        float energy_cap = density_colour_energy_cap(
            work_rgb + vec3(pedestal_signal));
        density_grain *= energy_cap;
        vec3 density_sd = tone_den * field_sd
                        * abs(work_rgb + vec3(pedestal_signal)) * energy_cap;
        // Exact endpoint: FXC lowers mix() to x + s*(y - x), which is an ulp
        // or two off y at s == 1, so the shipped default 1.0 takes the
        // density arm directly (uniform PARAM branch, no barrier follows).
        grain_delta = (density_w >= 1.0)
                    ? density_grain
                    : mix(grain_delta, density_grain, density_w);
        delta_sd = (density_w >= 1.0) ? density_sd
                                      : mix(delta_sd, density_sd, density_w);
    }

    // CLIP ROOM, one rule for every chain. Each channel may move only as far
    // as it can move symmetrically inside the sanctioned domain [0, top]:
    // where grain cannot go up it may not go down, or the display (SDR) / the
    // encode (PQ) clamp would keep the dark pits and drop the bright
    // excursions -- temporally conspicuous on tinted whites. Clip-limited
    // chains (plain SDR, or SDR in a PQ container: grain_headroom 0) have
    // top = 1 and a floor arm at 0. Headroom chains (an upstream SDR->HDR
    // expansion) have top = max(grain_fade, 1) -- overshoot physics only
    // exists at/above ref white; a lower grain_fade is the LUMA fade's
    // cosmetic job, and enforcing it per channel would strip grain from
    // bright saturated channels (re-base audit P1) -- and no floor arm (the
    // PQ pedestal owns near-black; work-domain black is not a clip). Neutral
    // highlights share the tightest upper room, so a tinted white with one
    // clipped channel cannot keep chromatic flicker. Under the bridge a
    // negative signed-gamma component (out-of-709) gets zero room = zero
    // grain on that channel: fp/gamut noise for ceiling-limited content.
    bool floor_arm = !hdr_bridge || grain_headroom < 0.5;
    float clip_top = floor_arm ? 1.0 : max(grain_fade, 1.0);
    vec3 up_room = max(vec3(clip_top) - pre_grain, vec3(0.0));
    vec3 channel_room = floor_arm ? max(min(pre_grain, up_room), vec3(0.0))
                                  : up_room;
    float rgb_peak = max(max(pre_grain.r, pre_grain.g), pre_grain.b);
    float rgb_floor = min(min(pre_grain.r, pre_grain.g), pre_grain.b);
    float neutral_highlight = smoothstep(0.80 * clip_top, 0.95 * clip_top,
                                         rgb_floor);
    float shared_upper = max(clip_top - rgb_peak, 0.0);
    vec3 code_room = mix(channel_room, vec3(shared_upper), neutral_highlight);
    // Room-aware gain BEFORE the clamp: a channel with less than ~2 sigma of
    // room is scaled down instead of hard-clamped, so saturated near-clip
    // colours keep a Gaussian-looking grain instead of two-level dots (audit
    // 2026-09-30: density put 4-4.6x grey's amplitude into the lit channel;
    // a channel at 0.995 sat at the bound in 43% of samples at gain 1, 79% at
    // gain 3, kurtosis down to 1.13; with this gain <= 5% at bound, kurtosis
    // ~2.4). Exactly 1 wherever room >= 2 sigma. The clamp stays as the
    // backstop; with it the delta never leaves the domain, so the old HDR
    // flash guard (a min() after this clamp) was a no-op and is gone.
    grain_delta *= min(vec3(1.0), code_room / max(2.0 * delta_sd, vec3(1.0e-9)));
    grain_delta = clamp(grain_delta, -code_room, code_room);
    work_rgb += grain_delta;

    if (hdr_bridge) {
        vec3 linear_709 = signed_pow(work_rgb, 2.4);
        vec3 out_nits = grain_ref_white * max(bt709_to_bt2020(linear_709), vec3(0.0));
        color.rgb = vec3(pq_oetf_code(out_nits.r), pq_oetf_code(out_nits.g),
                         pq_oetf_code(out_nits.b));
    } else {
        color.rgb = work_rgb;
    }

    if (debug_match > 0.5) {
        const int X_OFF = 24, Y_OFF = 400, BW = 10, BH = 10;
        const int ANCHOR = 10, NBITS = 16, NROWS = 52;
        ivec2 gid = ivec2(gl_GlobalInvocationID.xy) - ivec2(X_OFF, Y_OFF);
        if (gid.x >= 0 && gid.y >= 0
            && gid.x < ANCHOR + NBITS * BW && gid.y < NROWS * BH) {
            int row = gid.y / BH;
            float v = 0.0;
            if      (row == 0)  v = m_observed * 2000000.0;
            else if (row == 1)  v = sqrt(max(m_title_power, 0.0)) * 2000000.0;
            else if (row == 2)  v = m_shot_gain * 20000.0;
            else if (row == 3)  v = m_temporal_support * 2000000.0;
            // Rows 4-9 and 36-43 changed meaning in 5B (2026-09-30): the
            // title-confidence / survivor / loss diagnostics were removed.
            else if (row == 4)  v = m_evidence * 65000.0;
            else if (row == 5)  v = m_ev_gate * 65000.0;
            else if (row == 6)  v = m_auth_mean * 65000.0;
            else if (row == 7)  v = m_acq_max * 4000.0;
            else if (row == 8)  v = m_q_random * 65000.0;
            else if (row == 9)  v = m_q_source * 65000.0;
            else if (row == 10) v = s_eff_render * 30000.0;
            else if (row == 11) v = 0.0; // retired in 5A (structure ratio)
            else if (row == 12) v = m_coverage * 65000.0;
            else if (row == 13) v = m_motion * 30000.0;
            // Rows 14/15 (measured size/hardness) were retired with the
            // estimates in 5A (2026-09-30) and read 0.
            else if (row == 14) v = 0.0;
            else if (row == 15) v = 0.0;
            else if (row == 16) v = m_cut_score * 30000.0;
            else if (row == 17) v = m_shot_age;
            else if (row == 18) v = m_measured;
            else if (row == 19) v = m_gen_frame;
            else if (row < 28)  v = sqrt(max(m_master_p[row - 20], 0.0))
                                      * 2000000.0;
            else if (row < 36)  v = sqrt(max(s_restore_p[row - 28], 0.0))
                                      * 2000000.0;
            else if (row < 44)  v = m_master_w[row - 36] * 65000.0;
            else if (row == 44) v = s_active_inset_x * 200000.0;
            else if (row == 45) v = s_active_inset_y * 200000.0;
            else if (row == 46) v = m_pending_inset_x * 200000.0;
            else if (row == 47) v = m_pending_inset_y * 200000.0;
            else if (row == 48) v = max(m_geom_streak, m_geom_streak_y);
            else if (row == 49) v = min(m_geom_known, m_geom_known_y) * 65000.0;
            else if (row == 50) v = m_geom_changed * 65000.0;
            else                v = m_pan_px * 2000.0;
            uint val = uint(clamp(v, 0.0, 65535.0));
            if (gid.x < ANCHOR)
                color.rgb = vec3(1.0);
            else {
                int b = (gid.x - ANCHOR) / BW;
                uint bit = (val >> uint(NBITS - 1 - b)) & 1u;
                color.rgb = (bit == 1u) ? vec3(1.0) : vec3(0.0);
            }
        }
    }

    imageStore(out_image, ivec2(gl_GlobalInvocationID), color);
}
