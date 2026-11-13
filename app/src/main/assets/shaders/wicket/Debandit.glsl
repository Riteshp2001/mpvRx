// Debandit v3.5 — Detect-and-Reconstruct Deband, plane-fit estimator
// Copyright (C) 2026 Agust Ari · GPL-3.0
//
// Second-generation Debandit. The detect-and-reconstruct philosophy, the
// two-regime apply (snap vs LF-only), the four soft gates, the chroma
// opponent path and the trust-mask machinery carry over from v1.11
// UNCHANGED — what changes is the RAMP ESTIMATOR and the contamination
// story around it:
//
// 1. WEIGHTED PLANE FIT replaces the premultiplied Gaussian chains. Per
//    1/4-res texel, the pre-quantization ramp is estimated as the weighted
//    least-squares plane over a box window (radius db_rw texels), weights =
//    trust mask x chroma fit-mask. A plane fit has no curvature bias to
//    first order, so the 2S - S2 deconvolution (and its four kernel-table
//    copies) retires; genuinely smooth shading is a structural no-op by
//    construction, and the fit tracks CURVED shading (faces, glows) that
//    the sigma-48 refit misread by ~1.7 codes — the v1.x luma-on-shaded-
//    surfaces class. One-sided windows at frame borders fit a plane
//    through in-frame texels only: the v1.x border DC tilt (~0.19 codes)
//    measures ~0.01 codes here.
// 2. FIT RESIDUAL as pointwise contamination signal. The fit hands us,
//    almost free (three quadratic moments), the RMS of M minus the fitted
//    plane over the window — per axis (luma / opponent). A banding
//    staircase leaves ~0.3-0.6 codes of sawtooth residual; a bulk-contrast
//    edge inside the window leaves tens. The apply gates each scale's
//    validity on its residual with NO dilation (the residual already
//    integrates exactly the window the fit used), which shrinks the
//    bulk-edge collar to roughly the window radius. The +-8-texel
//    contamination envelope stays as remote-bias insurance (seeds and
//    knees unchanged from v1.11).
// 3. VALIDITY-NORMALIZED SCALE BLEND. Wide (db_rw) and short (db_rs)
//    fits blend by relative validity: c_eff = (wL*cL + wS*cS)/(wL+wS),
//    wS = (1-vL)*vS*db_ms. Unlike the v1.10 linear mix, a fully-condemned
//    scale contributes NOTHING — a plane fit near a bulk edge can carry
//    |c| ~ 10 codes where the Gaussian bias was ~2, so any leftover
//    fraction of an invalid field is visible (measured: 1.1-code paint
//    inside colored squares with the linear mix, 0.06 with this blend).
//    The gate is the validity of what was actually blended:
//    w_env = (wL*vL + wS*vS)/(wL+wS).
// 4. FRAME NOISE FLOOR (SSBO) + ANISOTROPY DISCRIMINATOR. A COMPUTE
//    reduce estimates the frame's true dither/grain floor as the p10 of
//    raw block MADs (histogram walk, 2-3 frame EMA). It de-circularizes
//    the structure-tensor texture flag that v1.11 built and reverted:
//    significant ISOTROPIC gradient energy (2-D mottle, measured against
//    the FLOOR, not against local MAD which texture inflates) closes the
//    snap regime — the LF correction stays, grain-region semantics.
//    Banding contours are locally 1-D (coherence ~1) and never flag;
//    dither is isotropic but sits at ~0.3x floor, under the significance
//    knee. Measured on a 10-bit BD stress set: fine-texture
//    retention inside former snap zones +10 to +34 points, dithered-8-bit
//    suite bit-flat (the failure that killed the v1.11 experiment).
//
// v2.1 = v2.0 with PASS FUSION, no math change (outputs identical): 20
// passes -> 16. The three Wide Moments row passes fuse into one COMPUTE
// pass (one window walk instead of three identical ones), and the three
// H-stage gathers (flatness range / chroma range / tensor rows) fuse the
// same way. Multi-output passes write their extra fields to fixed-size
// storage textures (the film-grain template mechanism); their consumers
// run as COMPUTE and imageLoad them. Every texture the full-res Apply
// samples stays an ordinary hook texture, so its bilinear reads are
// untouched. Envelope: the fixed 1024x576 allocations cover sources up
// to 4096x2304; wider is outside this shader's design envelope (storage
// writes are bounds-checked and loads clamp both axes — degraded, not
// undefined).
//
// v2.2 = v2.1 + GRID DEBLOCK EVIDENCE (db_block). Six-source
// characterization (dev deblock/): DCT grid blocking is an AVC-web-dl
// artifact — boundaries structurally fixed at x,y = 16k source px, so the
// phase is HARD-CODED, no search. Blocking spoofs three protection layers
// at once (it is noise statistically shaped like texture): the aniso flag,
// the structure gate, and the block-MAD snap knees — and its steps can
// exceed the default snap authority. The fix stays estimator-shaped:
// measure grid-locked EXCESS (mean boundary-phase |grad| minus interior,
// windowed +-34px, per axis) and a CONTRAST classifier (boundary/interior,
// knee 2.2-3.0 — BD content's own faint MB grid reads <= ~2 on flats and
// must not fire); then every opened gate's knee shifts by a bounded
// multiple of the excess (CLAMPED to db_thr — beyond that is phase-
// aligned real structure, not blocking), and the snap authority extends
// by ~the measured step (same cap). DENSE real texture raises the
// interior term equally and self-normalizes the contrast to ~1; the
// residual spoof class is faint 16px-pitch phase-locked DELIBERATE
// patterns (pixel art, exact-16 tone work) — indistinguishable locally,
// db_block=0 is the escape. Known limits, honest: isolated DC stamps
// mostly sit UNDER the classifier (the +-34px window mean dilutes a
// single boundary column ~17x; they fire only on quiet low-floor darks
// — misses are the safe direction); texture CO-LOCATED with strong
// blocking loses aniso snap protection region-wide, softening bounded
// by the snap authority (~db_thr-scale codes); the structure-gate shift
// also reaches the chroma weight through the pre-existing shared w_tex
// factor (small, blocking is chroma-banded too — accepted); evidence is
// per-frame, GOP-cadence stability unverified (EMA = the v2.3 candidate
// if stamps/marginal mosaics pulse); top-cropped streams shift the y
// phase (x evidence still fires). Mosquito/ringing is a separate
// edge-locked mechanism, deliberately NOT this knob.
//
// v3.0 = v2.2 + BOUNDED-SMOOTHNESS RECONSTRUCTOR (db_curve). The sizing
// prototype (dev debandit-v3) falsified the "estimator needs curvature"
// charter: on curved-field banding (vignette rings, glow domes, dark
// gradient walls) EVERY least-squares upgrade — quadratic basis, wider
// windows, oriented 1-D fit, iterated refit — saturates at ~half the
// staircase energy, because a more flexible fit partially FOLLOWS the
// quantization staircase it should cross (the quadratic explains 30%
// more window variance yet corrects LESS). What reaches the measured
// ceiling is a masked lowpass under the existing bounds: on a CONFIRMED
// quantized surface the step size itself is the evidence bound, so
// smoothing inside the amplitude gate is evidence-bounded by
// construction — the estimator's job collapses to CONFIRMATION, and the
// reconstruction is the smoothness prior. Machinery: sigma-3 (1/4-res)
// fitmask-NORMALIZED separable lowpass of M (two 17-tap passes,
// mean-subtracted rows re-referenced in fp32 — the wide-moments
// pattern); in the apply the correction SOURCE blends fit -> lowpass
// per axis by db_curve x the LP's own confidence (trusted mass x support
// symmetry, carried in DB2_LPC alpha), and the envelope/residual gate
// w_env is BYPASSED in proportion to that share (it certifies the fit,
// not the lowpass — see the Apply note). The fit-residual term is
// deliberately NOT in the blend weight — on curved shading the plane's
// residual carries the very curvature+staircase the reconstructor
// exists for, and a residual-shaped share measures NO better than the
// fit alone (11% vs 38% ripple recovery on the 1312 rings). Bulk-edge
// safety on the LP share does NOT come from the envelope (bypassed, see
// above) but from the LP alpha — trusted mass x the symmetry fade (a
// normalized convolution is biased on a ramp under one-sided support —
// frame borders, mask collars; the fade keeps the FIT there, which is
// exact) — and from the amplitude, flatness and structure gates + snap
// machinery, which all read the BLENDED correction. Fallback: db_curve = 0 (DYNAMIC) is
// 1-ULP-equivalent to v2.2 (<= 1 px/frame — every algebraic blend form
// perturbs Metal's luma-chain scheduling that much; fine for live A/B);
// the BIT-exact escape is the deep define DB2_CURVE_PATH 0 in the Apply
// pass (compiles the blend out; proven byte-identical on the suite).
// Honest limits: a smoothness prior is NOT intrinsically conservative
// (a plane fit is) — measured naked it costs fine texture where the
// tower is what saves it, so db_curve must never be applied outside
// the gates; masked (hair-class) regions hand back to the fit via the
// alpha weight; sigma 3 under-reaches band pitches past ~12 texels
// (~45% residual at 15-texel rings — a safe miss; pitch tracking is
// the follow-up if coarse rings matter in the field); smooth REAL
// low-amplitude modulation (soft lighting, gentle undulation) loses
// the fit-residual protection on the LP path — pulled toward the
// local mean, always bounded by the amplitude gate (~<= db_thr, worst
// class ~1 code on soft-glow S-edges) — lower db_curve on clean
// high-bit sources; the positive banding-evidence blend term is the
// principled follow-up.
//
// v3.1 = v3.0 + FLOOR-ADAPTIVE SNAP KNEES (db_fsnap). The static snap
// MAD knees (1.3-2.1 / darks 0.6-1.6) were tuned on quiet encodes
// (flat-field dither ~0.2-0.3 codes); a BD remux carries ~0.7-0.9 and
// its dilated MAD sits past the knees, so the snap regime closes and
// dither rides through frame-wide (the "grain band on flat areas"
// field report). The p10 frame floor is the WRONG predictor — frames
// mixing pristine black with dithered skies read a quiet p10 while the
// sky needs the shift (measured: remux t=1176 floor 0.05, sky needs
// +1.2 codes). The Noise Floor pass therefore also measures the FLAT-
// FIELD dither level: the p50 of raw block MADs restricted to row-flat
// (range < 2 codes over the trust-masked +-8 gather) DITHERED blocks
// (MAD in 0.05..6 codes — the caps exclude untrusted-center collapse
// above and pristine solid fills below; pristine blocks are counted
// separately and, when they dominate the flats, converge dfl to the
// histogram floor instead of voting the p50 into bin 0 — see the
// three-way branch in the Noise Floor pass). Same log-hist / EMA /
// relock machinery, second SSBO scalar (db2_dfl, also FLOORTEX .y).
// The apply shifts BOTH luma snap knees (bright + dark mix) by
// db_fsnap x min(3.5 x max(dfl - 0.35, 0), 1.8) codes — hinge and cap
// calibrated on the 16-case catalogue + audit-round synthetics (dev
// debandit-v31-floorsnap): every remux stress frame lands inside the
// shifted knees (needs 1.2-1.6), a minority dithered sky next to a
// pristine-black majority still fires (the audit-F1 class), the Judas
// encode / quiet 10-bit scenes sit BELOW the hinge (dfl 0.27-0.30 vs
// 0.35, two histogram bins of margin -> shift exactly 0.0, static
// knees, byte-stable), and the cap refuses film-grain-class floors so
// heavy live-action grain is absorbed at most ~cap past the static
// knees (real420 face: dfl 0.91, capped — watch the retention tower).
// Chroma knees stay static this round (a chroma analog only if the
// field shows a chroma grain band); note dfl itself keys on the
// max-channel block MAD, so heavy chroma-only dither can raise it —
// accepted, luma/chroma dither correlate in practice. dfl is one
// frame-global scalar: a frame whose flats are dominated by a bright
// dithered sky also shifts the DARK knees (0.6-1.6 base) by the same
// amount — bounded by the cap, the flat-cert gates and the aniso
// flag; per-zone dfl is the escalation if the field ever shows dark
// shadow-detail loss. Knee-shift form: the shift is EXACTLY +0.0 when
// db_fsnap = 0 or the source is below the hinge, and x + 0.0 is
// IEEE-exact under any FMA/reassociation — so db_fsnap = 0 is a
// BIT-exact escape (framemd5-proven; no compile-out define needed,
// unlike the db_curve source-blend class).
//
// v3.4 = v3.1 + PASS FUSION round 2, no math change (output values
// identical): 19 passes -> 16, plus the frame-reduce parallelism fix.
// (v3.2/v3.3 were internal development explorations that did not ship.)
// The v2.1 fusion mechanism extends to three more sibling pairs whose
// walks are unrelated but share a dispatch: Median V absorbs the Fit
// Mask (chroma-agreement weights computed from the fp16-quantized median
// exactly as the separate pass read them back — FITMASK becomes r16f
// storage, its consumers imageLoad it), Flatness Range V absorbs Grid
// Env H (GRIDH -> storage), and Chroma Range V absorbs Contamination
// Env H (ENV_H -> storage; Env V converts to COMPUTE and imageLoads
// it). Short Fuse and Env V convert fragment -> COMPUTE (the v2.1
// rerun measured such conversions faster, not slower). The Noise Floor
// reduce — measured 23-31% of the whole shader on a desktop D3D11
// target because a single 144-lane workgroup serializes it — widens to
// a 1024-lane workgroup: bin counts are integer atomics, so any lane
// count produces the identical histogram, percentile, EMA and SSBO
// state, bitwise. The fusions retire two cross-pass constant-sync
// hazards (the Median/Fit mask knee pair and nothing else changed
// hands). Envelope note: FITMASK/GRIDH/ENV_H storage shares the fixed
// 1024x576 allocation rule (sources past 4096x2304 degrade, not break)
// — this newly covers the short fit's mask reads, which previously had
// no storage dependence; in-envelope behavior is unchanged.
//
// v3.5 = v3.4 + an audit round: PASS FUSION round 3, exact skips and a
// precision fix. 16 passes -> 15.
// - Wide Residual fuses into Wide Fuse (it re-ran the identical 33-row V
//   walk and solve). The residuals ride channels that were free: RANGE.y
//   (luma) and ANISO.w (opponent). RANGE.x now holds the range itself,
//   formed from the fp16-rounded extrema — the value the Apply used to
//   subtract.
// - (Recon LP H was also fused into Wide Moments and measured SLOWER:
//   storage reads in LP V cost more than the pass it saved. Kept
//   separate.)
// - H Gathers computes the opponent block MAD from the full-res block it
//   already reads (Chroma Range V re-read all 16 taps) and caches the row
//   lumas for the tensor rows (18 MED fetches per texel). Noise Floor's
//   stride grows with the frame (~32k samples; 1080p is the v3.4 grid).
// - Uniform skips: db_aniso = 0 skips the structure tensor, db_block = 0
//   the whole grid chain. Every consumer multiplies by the knob, so the
//   output is identical.
// - PRECISION: CORR, SCORR, LPC and the opponent range field are stored
//   RAW (signed). The 0.5 + c offset encode quantized every correction to
//   1/16-1/8 code at the fp16 ulp of 0.5 — a faint staircase locked to
//   the ramps being rebuilt, which CelFlare's expansion then amplifies.
//   The shader already needs signed float hook FBOs (WMOM_A, the LP sums),
//   so the unorm insurance bought nothing. Output moves by <= 0.25 code;
//   the regression tower is equal or better (border DC tilt 0.065 -> 0.041).
// - TENS_H stores the tensor x4096: raw squared gradients sat in fp16
//   SUBNORMALS on quiet sources, where the aniso flag read rounding noise.
// Measured (D3D11, RTX 5090, defaults): 4K MAIN 0.665 -> 0.541 ms,
// 1080p 0.218 -> 0.198 ms.
//
// Chain order: list BEFORE CelFlare in glsl-shaders (same-hook passes run
// in load order; CelFlare's expansion multiplies any step not flattened
// first). The previous v1.11 generation is archived outside the public
// release repo; its db_* PARAM names remain compatible for historical A/B.
//
// Known limits, documented honestly:
// - Hair-class dense structure (strand clusters every ~15px) still
//   passes through: both windows are mostly masked there, conf fades the
//   fit out. Passthrough beats fabrication; unchanged from v1.x.
// - The collar around bulk-contrast edges shrinks to ~window radius
//   (short scale: ~15-20px) but does not vanish; a fit whose window
//   straddles a 50-code edge is unusable no matter the estimator. The
//   honest full fix remains similarity-aware weights (a v2.1 candidate:
//   tonal kernel, non-separable).
// - Mid-scale (8-40px) mottle BELOW the noise-significance knee is still
//   absorbed in snap zones; the floor turns this from "30-50% of texture
//   energy" into a bounded tail.
// - The frame floor is a per-frame scalar: content mixing a pristine
//   quiet region with a heavily dithered one reads a low floor and the
//   anisotropy flag gets conservative (more texture protected, slightly
//   less dither absorbed) — the safe direction.
// - EMA state: db2_floor lives in an SSBO and survives shader reload
//   (2-3 frame convergence makes this harmless).
// - mod-4 dimension tap-phase caveat unchanged from v1.x.
// - REACH IS IN MAIN PIXELS. On 4K MAIN after a 2x upscale (an AI
//   upscaler in the vf chain) every window covers half the source pixels
//   it was calibrated on: the wide fit spans +-32 source px, not +-64.
//   Wide-pitch staircases are the visible cost — measured on AI-upscaled
//   1-code staircases, 96 / 192 / 384 px source pitch keep ~59 / 82 / 90%
//   of their banding. (A frame-scale reconstructor was prototyped in the
//   v3.5 round and parked: it fixed clean staircases but flattened soft
//   painted mottle, which needs a positive staircase-evidence term first.)
// - db_block's grid phase is hard-coded to a 16 px DCT grid on MAIN; after
//   a 2x upscale the grid is 32 px and smeared, so the evidence is inert
//   there (db_block = 0 then skips its cost).

// ---- shampv shader API (plain comments to libplacebo) ----
//@shampv input any
//@shampv choice db_debug off bypass correction-x32 luma-gate chroma-gate v2-fields grid-evidence v3-recon
//@shampv step db_flat_c 0.25

// =============================================================================
//  USER TUNING
// =============================================================================
// Sliders are DYNAMIC: glsl-shader-opts changes apply on the next frame, no
// recompile. db_debug / db_rw / db_rs are DEFINEs and recompile on change.
// Defaults = the shipped tune. NOTE: no comment lines may sit between a PARAM
// block and the next directive — the parser folds them into the value and
// fails to load.

//!PARAM db_strength
//!DESC Deband strength — fraction of the reconstructed gradient applied. ↑ 1 = full reconstruction (shipped) · ↓ 0 = off.
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 1.0
1.0

//!PARAM db_thr
//!DESC Banding step-size ceiling in 8-bit codes — drives the amplitude gate, snap reach and envelope. 1.5 protects faint soft structure; 2 = typical web, 4 = brutal encodes.
//!TYPE DYNAMIC float
//!MINIMUM 0.5
//!MAXIMUM 4.0
1.5

//!PARAM db_flat
//!DESC Luma flatness ceiling (8-bit codes, ±32px window) — above it = texture, correction fades out (zero at 2×). ↑ = reach steeper gradients · ↓ = protect more detail.
//!TYPE DYNAMIC float
//!MINIMUM 2.0
//!MAXIMUM 24.0
5.0

//!PARAM db_chroma
//!DESC Chroma-path strength (opponent). ↑ 1 = full (shipped) · 0 = luma-only. Independently gated — fixes chroma banding even where steep luma shading shuts the luma gates.
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 1.0
1.0

//!PARAM db_flat_c
//!DESC Chroma flatness ceiling (RGB codes, ±32px). Above it = color structure, fades out (zero at 2×). ↑ = reach richer color · ↓ = protect art. Banded ~0.5-1, art 8+.
//!TYPE DYNAMIC float
//!MINIMUM 1.0
//!MAXIMUM 24.0
4.0

//!PARAM db_ms
//!DESC Multi-scale rescue — near bulk edges blends in the short fit where only it is clean, shrinking the banding collar to ~15-20px. ↑ 1 = full (shipped) · 0 = wide fit only.
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 1.0
1.0

//!PARAM db_snr
//!DESC SNR-adaptive texture protection. 1 = structure knee tracks the local noise floor, sparing faint texture on quiet hi-bit sources · 0 = static knee (dither still snaps).
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 1.0
1.0

//!PARAM db_aniso
//!DESC Isotropic-texture snap protection. 1 = 2-D texture above the noise floor disables snap, sparing mottle (stone, cloud, DoF) · 0 = off (v1.11). 1-D banding never flags.
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 1.0
1.0

//!PARAM db_dither
//!DESC Dither absorption / contour snap (luma + chroma). 1 = cleanest gradients · ↓ = keep sub-code faint texture, less contour cleanup · 0 = low-frequency correction only.
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 1.0
1.0

//!PARAM db_block
//!DESC Grid-deblock evidence weight (AVC web-dl). 1 = absorb 16px DCT blocking (shipped) · 0 = bit-exact v2.1, the escape for deliberate 16px pixel-art/screentone.
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 1.0
1.0

//!PARAM db_curve
//!DESC Curved-shading reconstructor (v3.0). 1 = blend the plane fit toward a bounded lowpass on vignettes/glow domes (shipped) · 0 = v2.2 bit-exact. Evidence-bounded.
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 1.0
1.0

//!PARAM db_fsnap
//!DESC Floor-adaptive dither snap (v3.1). 1 = snap knees track the frame's measured flat-field dither (BD remux) · 0 = static v3.0 knees. Quiet sources unaffected.
//!TYPE DYNAMIC float
//!MINIMUM 0.0
//!MAXIMUM 1.0
1.0

//!PARAM db_rw
//!DESC Wide plane-fit radius (¼-res texels; recompiles). 16 = ±64px, spans coarse-banding plateaus. ↑ = wider plateaus but a wider bulk-edge collar · ↓ = tighter, less reach.
//!TYPE DEFINE
//!MINIMUM 8
//!MAXIMUM 24
16

//!PARAM db_rs
//!DESC Short plane-fit radius (¼-res texels; recompiles). 3 = ±12px, the bulk-edge rescue scale. Validated 3-5 — above 5 exhausts the envelope margin and widens its collar.
//!TYPE DEFINE
//!MINIMUM 2
//!MAXIMUM 6
3

//!PARAM db_debug
//!DESC Debug: 0 = off · 1 = bypass · 2 = correction ×32 on gray · 3 = luma gate · 4 = chroma gate · 5 = v2 fields · 6 = grid evidence · 7 = v3 reconstructor.
//!TYPE DEFINE
//!MINIMUM 0
//!MAXIMUM 7
0

//!BUFFER DB2_STATE
//!VAR float db2_floor
//!VAR float db2_dfl
//!VAR float db2_dshift
//!STORAGE

//!TEXTURE DB2_WMOM_B
//!SIZE 1024 576
//!FORMAT rgba16f
//!STORAGE

//!TEXTURE DB2_WMOM_C
//!SIZE 1024 576
//!FORMAT rgba16f
//!STORAGE

//!TEXTURE DB2_RANGEC_H
//!SIZE 1024 576
//!FORMAT rgba16f
//!STORAGE

//!TEXTURE DB2_TENS_H
//!SIZE 1024 576
//!FORMAT rgba16f
//!STORAGE

//!TEXTURE DB2_GRID_RAW
//!SIZE 1024 576
//!FORMAT rgba16f
//!STORAGE

//!TEXTURE DB2_LP_MX
//!SIZE 1024 576
//!FORMAT rgba16f
//!STORAGE

//!TEXTURE DB2_FITMASK
//!SIZE 1024 576
//!FORMAT r16f
//!STORAGE

//!TEXTURE DB2_GRIDH
//!SIZE 1024 576
//!FORMAT rgba16f
//!STORAGE

//!TEXTURE DB2_ENV_H
//!SIZE 1024 576
//!FORMAT rgba16f
//!STORAGE

//!TEXTURE DB2_RES_S
//!SIZE 1024 576
//!FORMAT rgba16f
//!STORAGE

//!HOOK MAIN
//!BIND HOOKED
//!SAVE DB2_DS
//!WIDTH HOOKED.w 4 /
//!HEIGHT HOOKED.h 4 /
//!DESC Debandit: Downsample 1/4

// =============================================================================
// PASS 1: DOWNSAMPLE 1/4 — plateau field P (exact 4x4 box mean)
// =============================================================================
// Identical to v1.11. Each 1/4-res texel is the uniform mean of its 4x4
// full-res block via 4 bilinear taps at half-integer positions (exact for
// mod-4 dimensions). Alpha carries BT.709 luma of the mean.

vec4 hook() {
    vec2 pt = HOOKED_pt;
    vec3 rgb  = HOOKED_tex(HOOKED_pos + vec2(-pt.x, -pt.y)).rgb;
    rgb      += HOOKED_tex(HOOKED_pos + vec2( pt.x, -pt.y)).rgb;
    rgb      += HOOKED_tex(HOOKED_pos + vec2(-pt.x,  pt.y)).rgb;
    rgb      += HOOKED_tex(HOOKED_pos + vec2( pt.x,  pt.y)).rgb;
    rgb *= 0.25;
    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    return vec4(rgb, luma);
}

//!HOOK MAIN
//!BIND DB2_DS
//!SAVE DB2_MED_H
//!WIDTH DB2_DS.w
//!HEIGHT DB2_DS.h
//!DESC Debandit: Median H

// =============================================================================
// PASS 2/3: SEPARABLE MED5 — line-reject field M (identical to v1.11)
// =============================================================================
// med5 identity (verified exhaustively): med5(a,b,c,d,e) =
// med3(c, max(min(a,b),min(d,e)), min(max(a,b),max(d,e))); componentwise,
// so all four channels ride in SIMD.

vec4 hook() {
    vec2 pt = DB2_DS_pt;
    vec2 pos = DB2_DS_pos;
    vec4 a = DB2_DS_tex(pos - vec2(2.0 * pt.x, 0.0));
    vec4 b = DB2_DS_tex(pos - vec2(pt.x, 0.0));
    vec4 c = DB2_DS_tex(pos);
    vec4 d = DB2_DS_tex(pos + vec2(pt.x, 0.0));
    vec4 e = DB2_DS_tex(pos + vec2(2.0 * pt.x, 0.0));
    vec4 f = max(min(a, b), min(d, e));
    vec4 g = min(max(a, b), max(d, e));
    return max(min(c, f), min(max(c, f), g));
}

//!HOOK MAIN
//!BIND DB2_MED_H
//!BIND DB2_DS
//!BIND DB2_FITMASK
//!SAVE DB2_MED
//!WIDTH DB2_MED_H.w
//!HEIGHT DB2_MED_H.h
//!COMPUTE 16 16
//!DESC Debandit: Median V + Fit Mask

// Alpha of the output is the LUMA TRUST MASK (identical to v1.11): 1 where
// this texel's box mean agrees with the median field, fading to 0 where
// they disagree (structure: line art, text strokes, dense texture). The
// RANGE passes consume it as-is — luma-only by design (v1.9 audit F2: an
// equal-luma pure-chroma structure closes the chroma range gate around
// itself, which is the correct conservative behavior).
//
// v3.4: the FIT MASK fuses in (was its own pass reading the STORED
// median back). The fit weight additionally rejects texels whose
// box-mean OPPONENT components disagree with the median field's —
// equal-luma chroma structure (colored line art, saturated edges'
// contour texels) that the luma-only trust mask cannot see. Same knees
// as the luma mask, in RGB-projected codes: chroma banding contours
// read ~0.5-1 here (partial rejection of the contour texel itself is
// harmless — the fit needs the plateaus), chroma grain sigma-2 ~1,
// saturated structure 2+. The RANGE gates deliberately do NOT use this
// mask. VALUE IDENTITY: the old pass computed its agreement from the
// fp16-STORED median and trust mask; the fused version quantizes its
// fp32 registers through packHalf2x16 before that math, so the stored
// r16f FITMASK is the same value the two-pass flow produced. The knee
// pair is no longer duplicated across passes (one sync hazard retired).
#define DB2_CODE_M    (1.0 / 255.0)
#define DB2_MASK_LO   0.9
#define DB2_MASK_HI   1.8

float db2_h16(float x) {
    return unpackHalf2x16(packHalf2x16(vec2(x, 0.0))).x;
}

void hook() {
    ivec2 gid = ivec2(gl_GlobalInvocationID.xy);
    ivec2 sz = ivec2(DB2_MED_H_size);
    if (gid.x >= sz.x || gid.y >= sz.y) return;
    vec2 pt = DB2_MED_H_pt;
    vec2 pos = (vec2(gid) + 0.5) * pt;
    vec4 a = DB2_MED_H_tex(pos - vec2(0.0, 2.0 * pt.y));
    vec4 b = DB2_MED_H_tex(pos - vec2(0.0, pt.y));
    vec4 c = DB2_MED_H_tex(pos);
    vec4 d = DB2_MED_H_tex(pos + vec2(0.0, pt.y));
    vec4 e = DB2_MED_H_tex(pos + vec2(0.0, 2.0 * pt.y));
    vec4 f = max(min(a, b), min(d, e));
    vec4 g = min(max(a, b), max(d, e));
    vec4 med = max(min(c, f), min(max(c, f), g));

    vec4 ds = DB2_DS_tex(pos);
    float lum_med = dot(med.rgb, vec3(0.2126, 0.7152, 0.0722));
    float dev = abs(ds.a - lum_med) / DB2_CODE_M;
    float mask = 1.0 - smoothstep(DB2_MASK_LO, DB2_MASK_HI, dev);
    imageStore(out_image, gid, vec4(med.rgb, mask));

    // Fit mask from the fp16-quantized median (see the header note).
    const vec3 W709 = vec3(0.2126, 0.7152, 0.0722);
    vec3 m16 = vec3(db2_h16(med.r), db2_h16(med.g), db2_h16(med.b));
    float y_d = dot(ds.rgb, W709);
    float y_m = dot(m16, W709);
    vec2 o_d = vec2(ds.b - y_d, ds.r - y_d);
    vec2 o_m = vec2(m16.b - y_m, m16.r - y_m);
    vec2 dv = abs(o_d - o_m) / DB2_CODE_M;
    float mask_c = 1.0 - smoothstep(DB2_MASK_LO, DB2_MASK_HI, max(dv.x, dv.y));
    if (all(lessThan(gid, imageSize(DB2_FITMASK))))
        imageStore(DB2_FITMASK, gid,
                   vec4(db2_h16(mask) * mask_c, 0.0, 0.0, 1.0));
}

// =============================================================================
// PASS 4: WIDE MOMENT ROWS — separable box sums for the plane fit
// =============================================================================
// Per output texel, sums over the row window x' in [-db_rw, +db_rw] with
// off-frame taps at ZERO WEIGHT (v1.7 border rule). Coordinates are
// window-relative and normalized (u = x'/db_rw, |u| <= 1) and M enters
// mean-subtracted against the OUTPUT texel's own median value — both are
// fp16-ulp insurance: every stored quantity stays near zero on smooth
// content (validated in the numpy prototype at <= 0.01 code fit error;
// the raw-coordinate formulation loses ~0.25 code to ulp). The fuse pass
// re-references rows to its own center in fp32 registers.
// Channel layout across the three textures (12 scalars):
//   A: (sum w, sum w*u, sum w*u^2, sum w*dY^2)
//   B: (sum w*dM.rgb, sum w*do1^2)
//   C: (sum w*u*dM.rgb, sum w*do2^2)
// dY/do1/do2 are the luma/opponent projections of dM = M(tap) - M(center):
// the quadratic moments feed the FIT RESIDUAL per gate axis.
// v2.1: ONE compute pass fills all three (was three passes making the
// identical window walk). A rides out_image (an ordinary hook texture —
// the fuse passes sample it); B/C go to storage. Per-accumulator sum
// order is unchanged, so the stored moments are bit-identical to v2.0.
//!HOOK MAIN
//!BIND DB2_MED
//!BIND DB2_FITMASK
//!BIND DB2_WMOM_B
//!BIND DB2_WMOM_C
//!SAVE DB2_WMOM_A
//!WIDTH DB2_MED.w
//!HEIGHT DB2_MED.h
//!COMPUTE 16 16
//!DESC Debandit: Wide Moments

void hook() {
    ivec2 gid = ivec2(gl_GlobalInvocationID.xy);
    ivec2 sz = ivec2(DB2_MED_size);
    if (gid.x >= sz.x || gid.y >= sz.y) return;
    ivec2 fbnd = min(sz, ivec2(imageSize(DB2_FITMASK))) - 1;
    vec2 pt = DB2_MED_pt;
    vec2 pos = (vec2(gid) + 0.5) * pt;
    const vec3 W709 = vec3(0.2126, 0.7152, 0.0722);
    vec3 m0 = DB2_MED_tex(pos).rgb;
    float h0 = 0.0, h1 = 0.0, h2 = 0.0, hqy = 0.0;
    vec3 hM = vec3(0.0), hxM = vec3(0.0);
    float hq1 = 0.0, hq2 = 0.0;
    for (int i = -db_rw; i <= db_rw; i++) {
        float xp = pos.x + float(i) * pt.x;
        // v3.4: FITMASK is storage — the clamped load matches the old
        // clamp-to-edge sample; off-frame taps stay zero-WEIGHTED by the
        // step guards either way.
        float w = imageLoad(DB2_FITMASK,
                            ivec2(clamp(gid.x + i, 0, fbnd.x),
                                  min(gid.y, fbnd.y))).x
                * step(0.0, xp) * step(xp, 1.0);
        vec3 dM = DB2_MED_tex(vec2(xp, pos.y)).rgb - m0;
        float u = float(i) / float(db_rw);
        float dy  = dot(dM, W709);
        float do1 = dM.b - dy;
        float do2 = dM.r - dy;
        h0 += w; h1 += w * u; h2 += w * u * u;
        hqy += w * dy * dy;
        hM  += w * dM;
        hq1 += w * do1 * do1;
        hxM += w * u * dM;
        hq2 += w * do2 * do2;
    }
    imageStore(out_image, gid, vec4(h0, h1, h2, hqy));
    if (all(lessThan(gid, imageSize(DB2_WMOM_B)))) {
        imageStore(DB2_WMOM_B, gid, vec4(hM, hq1));
        imageStore(DB2_WMOM_C, gid, vec4(hxM, hq2));
    }
}

//!HOOK MAIN
//!BIND DB2_MED
//!BIND DB2_FITMASK
//!BIND DB2_WMOM_A
//!BIND DB2_WMOM_B
//!BIND DB2_WMOM_C
//!BIND DB2_RES_S
//!SAVE DB2_CORR
//!WIDTH DB2_MED.w
//!HEIGHT DB2_MED.h
//!COMPUTE 16 16
//!DESC Debandit: Wide Fuse + Residual

// =============================================================================
// PASS 5: WIDE FUSE — V accumulation, 2x2 weighted-LS solve, c = fit - M
// =============================================================================
// v3.5: the WIDE FIT RESIDUAL fuses in (it was its own pass re-running this
// exact V walk and solve — ~10% of the shader at 4K). It needs only the B/C
// alpha moments, which ride the same imageLoads. The residual is the fit's
// RMS of M minus the fitted plane over the window, per gate axis: luma (x)
// and the worse of the two opponent axes (y), linear [0,1] units —
// resvar_X = S_XX - b_X*S_uX - c_X*S_vX per axis; axis moments are linear
// combinations of the RGB ones. The apply gates each PATH on its own axis
// (steep luma structure cannot veto a clean chroma fit — the v1.9 face
// lesson carries into v2), pointwise, with no dilation. The SHORT scale
// carries no residual on purpose: its +-8-texel envelope dilation already
// covers the whole +-(db_rs+2) contamination reach (verified inert in the
// prototype). Routing: stored to DB2_RES_S (storage), copied into the free
// .y of DB2_RANGE (Flatness Range V) and .w of DB2_ANISO (Tensor V) — both
// on this grid, so the Apply's bilinear reads land on identical values.
// Combines the row moments over y' in [-db_rw, db_rw] (v = y'/db_rw),
// re-referencing each row's mean-subtracted moments from the row center's
// median value to this texel's in fp32 (linear moments shift linearly;
// quadratics via (X-b)^2 = (X-a)^2 + 2(a-b)(X-a) + (a-b)^2). Solves the
// centered normal equations per RGB channel (shared 2x2 geometry matrix),
// evaluates the plane AT THIS TEXEL, and stores c = (fit - M) * conf RAW
// (signed; v3.5 — the old 0.5 + c encode quantized every correction to
// 1/16-1/8 code at the fp16 ulp of 0.5, a faint staircase locked to the
// very ramps being rebuilt; the shader already requires signed float
// hook FBOs for WMOM_A / LP sums, so the unorm insurance bought nothing).
// Alpha = max-channel |c| x fitmask^2, computed HERE at fp32 (v1.9
// lesson: never re-derive the envelope seed from a stored field).
//
// conf fades the fit out on low trusted mass (nothing to learn a ramp
// from); g_det on an ill-conditioned normal matrix (trusted texels nearly
// collinear — frame corners, heavy masking).
#define DB2_CONF_LO   0.06
#define DB2_CONF_HI   0.20
#define DB2_DET_LO    0.02
#define DB2_DET_HI    0.08

void hook() {
    ivec2 gid = ivec2(gl_GlobalInvocationID.xy);
    ivec2 sz = ivec2(DB2_MED_size);
    if (gid.x >= sz.x || gid.y >= sz.y) return;
    ivec2 bnd = min(sz, imageSize(DB2_WMOM_B)) - 1;
    vec2 pt = DB2_MED_pt;
    vec2 pos = (vec2(gid) + 0.5) * pt;
    const vec3 W709 = vec3(0.2126, 0.7152, 0.0722);
    vec3 m0 = DB2_MED_tex(pos).rgb;
    float fm0 = imageLoad(DB2_FITMASK,
                          min(gid, ivec2(imageSize(DB2_FITMASK)) - 1)).x;

    float W = 0.0, Wu = 0.0, Wv = 0.0, Wuu = 0.0, Wuv = 0.0, Wvv = 0.0;
    vec3 WM = vec3(0.0), WuM = vec3(0.0), WvM = vec3(0.0);
    vec3 WQ = vec3(0.0);   // residual quadratics on (Y, o1, o2)
    for (int j = -db_rw; j <= db_rw; j++) {
        float yp = pos.y + float(j) * pt.y;
        float inb = step(0.0, yp) * step(yp, 1.0);
        float v = float(j) / float(db_rw);
        ivec2 tp = ivec2(min(gid.x, bnd.x), clamp(gid.y + j, 0, bnd.y));
        vec4 A = DB2_WMOM_A_tex(vec2(pos.x, yp)) * inb;
        vec4 B = imageLoad(DB2_WMOM_B, tp) * inb;
        vec4 C = imageLoad(DB2_WMOM_C, tp) * inb;
        vec3 d = (DB2_MED_tex(vec2(pos.x, yp)).rgb - m0) * inb;
        W += A.x; Wu += A.y; Wv += v * A.x;
        Wuu += A.z; Wuv += v * A.y; Wvv += v * v * A.x;
        vec3 rowM = B.rgb + A.x * d;
        WM += rowM;
        WuM += C.rgb + A.y * d;
        WvM += v * rowM;
        // quadratic shift: per-axis d projections against per-axis row
        // linear moments (B.rgb are RGB moments; project to axes)
        float dy  = dot(d, W709);
        float do1 = d.b - dy;
        float do2 = d.r - dy;
        float hy  = dot(B.rgb, W709);
        float h1_ = B.rgb.b - hy;
        float h2_ = B.rgb.r - hy;
        WQ.x += A.w + 2.0 * dy  * hy  + dy  * dy  * A.x;
        WQ.y += B.a + 2.0 * do1 * h1_ + do1 * do1 * A.x;
        WQ.z += C.a + 2.0 * do2 * h2_ + do2 * do2 * A.x;
    }

    float Ws = max(W, 1e-6);
    float mu = Wu / Ws, mv = Wv / Ws;
    vec3 Mb = WM / Ws;
    float Suu = Wuu - mu * Wu;
    float Suv = Wuv - mu * Wv;
    float Svv = Wvv - mv * Wv;
    vec3 SuM = WuM - mu * WM;
    vec3 SvM = WvM - mv * WM;

    float det = Suu * Svv - Suv * Suv;
    float tr = Suu + Svv;
    float cond = det / max(tr * tr, 1e-9);
    float g_det = smoothstep(DB2_DET_LO, DB2_DET_HI, cond);
    float inv_det = 1.0 / max(det, 1e-9);
    vec3 b  = (Svv * SuM - Suv * SvM) * inv_det * g_det;
    vec3 cf = (Suu * SvM - Suv * SuM) * inv_det * g_det;

    float area = float((2 * db_rw + 1) * (2 * db_rw + 1));
    float conf = smoothstep(DB2_CONF_LO, DB2_CONF_HI, W / area);
    // Centroid-offset fade (audit F2): a plane EXTRAPOLATES — when the
    // trusted mass is lopsided (chroma/trust holes), the fit at (0,0) is
    // an extrapolation the in-window residual cannot see. Full one-sided
    // frame-border windows measure |mu| ~ 0.5 and stay unfaded; only
    // genuinely hole-punched support (|centroid| > 0.6 window radii)
    // fades out.
    conf *= 1.0 - smoothstep(0.6, 0.95, max(abs(mu), abs(mv)));
    vec3 c = (Mb + b * (0.0 - mu) + cf * (0.0 - mv)) * (conf * g_det);
    // A condemned fit near a bulk edge can carry |c| past the 0.5 encode
    // headroom (measured 0.61 units on a real scene). Clamp far above any
    // gate-relevant magnitude (0.2 units = 51 codes vs knees <= ~8): the
    // encode invariant holds by construction and every consumer already
    // saturates long before the clamp engages.
    c = clamp(c, vec3(-0.2), vec3(0.2));
    float seed = max(max(abs(c.r), abs(c.g)), abs(c.b)) * fm0 * fm0;
    imageStore(out_image, gid, vec4(c, seed));

    // Per-axis fit residuals, statically unrolled (no dynamic vector
    // indexing — the one construct SPIRV-Cross->HLSL has historically
    // fumbled). Axis vectors: Y, o1 = B - Y, o2 = R - Y.
    #define DB2_RES_AXIS(axis, q)         sqrt(max((q) - dot(WM, axis) * dot(WM, axis) / Ws                  - dot(b, axis) * dot(SuM, axis)                  - dot(cf, axis) * dot(SvM, axis), 0.0) / Ws)
    float res_y  = DB2_RES_AXIS(W709, WQ.x);
    float res_o1 = DB2_RES_AXIS(vec3(0.0, 0.0, 1.0) - W709, WQ.y);
    float res_o2 = DB2_RES_AXIS(vec3(1.0, 0.0, 0.0) - W709, WQ.z);
    if (all(lessThan(gid, imageSize(DB2_RES_S))))
        imageStore(DB2_RES_S, gid, vec4(res_y, max(res_o1, res_o2), 0.0, 1.0));
}

//!HOOK MAIN
//!BIND DB2_MED
//!BIND DB2_FITMASK
//!SAVE DB2_SCORR
//!WIDTH DB2_MED.w
//!HEIGHT DB2_MED.h
//!COMPUTE 16 16
//!DESC Debandit: Short Fuse

// =============================================================================
// PASS 7: SHORT FUSE — direct 2D plane fit, radius db_rs
// =============================================================================
// The bulk-edge rescue scale. At (2*db_rs+1)^2 = ~49 taps a direct 2D
// accumulation is cheaper than a separable pair and keeps every moment in
// fp32 registers end to end (no storage boundary at all). Same solve,
// same knees as the wide fuse; c_s stored raw (signed), alpha = fp32 seed.
// v3.4: COMPUTE (FITMASK is storage now); positions and math verbatim.
#define DB2_CONF_LO   0.06
#define DB2_CONF_HI   0.20
#define DB2_DET_LO    0.02
#define DB2_DET_HI    0.08

void hook() {
    ivec2 gid = ivec2(gl_GlobalInvocationID.xy);
    ivec2 sz = ivec2(DB2_MED_size);
    if (gid.x >= sz.x || gid.y >= sz.y) return;
    ivec2 fbnd = min(sz, ivec2(imageSize(DB2_FITMASK))) - 1;
    vec2 pt = DB2_MED_pt;
    vec2 pos = (vec2(gid) + 0.5) * pt;
    vec3 m0 = DB2_MED_tex(pos).rgb;
    float fm0 = imageLoad(DB2_FITMASK, min(gid, fbnd)).x;

    float W = 0.0, Wu = 0.0, Wv = 0.0, Wuu = 0.0, Wuv = 0.0, Wvv = 0.0;
    vec3 WM = vec3(0.0), WuM = vec3(0.0), WvM = vec3(0.0);
    for (int j = -db_rs; j <= db_rs; j++) {
        float yp = pos.y + float(j) * pt.y;
        float vin = step(0.0, yp) * step(yp, 1.0);
        float v = float(j) / float(db_rs);
        for (int i = -db_rs; i <= db_rs; i++) {
            float xp = pos.x + float(i) * pt.x;
            float w = imageLoad(DB2_FITMASK,
                                ivec2(clamp(gid.x + i, 0, fbnd.x),
                                      clamp(gid.y + j, 0, fbnd.y))).x * vin
                    * step(0.0, xp) * step(xp, 1.0);
            vec3 dM = DB2_MED_tex(vec2(xp, yp)).rgb - m0;
            float u = float(i) / float(db_rs);
            W += w; Wu += w * u; Wv += w * v;
            Wuu += w * u * u; Wuv += w * u * v; Wvv += w * v * v;
            WM += w * dM; WuM += w * u * dM; WvM += w * v * dM;
        }
    }

    float Ws = max(W, 1e-6);
    float mu = Wu / Ws, mv = Wv / Ws;
    vec3 Mb = WM / Ws;
    float Suu = Wuu - mu * Wu;
    float Suv = Wuv - mu * Wv;
    float Svv = Wvv - mv * Wv;
    vec3 SuM = WuM - mu * WM;
    vec3 SvM = WvM - mv * WM;

    float det = Suu * Svv - Suv * Suv;
    float cond = det / max((Suu + Svv) * (Suu + Svv), 1e-9);
    float g_det = smoothstep(DB2_DET_LO, DB2_DET_HI, cond);
    float inv_det = 1.0 / max(det, 1e-9);
    vec3 b  = (Svv * SuM - Suv * SvM) * inv_det * g_det;
    vec3 cf = (Suu * SvM - Suv * SuM) * inv_det * g_det;

    float area = float((2 * db_rs + 1) * (2 * db_rs + 1));
    float conf = smoothstep(DB2_CONF_LO, DB2_CONF_HI, W / area);
    // Same centroid-offset fade as the wide fuse (audit F2).
    conf *= 1.0 - smoothstep(0.6, 0.95, max(abs(mu), abs(mv)));
    vec3 c = (Mb + b * (0.0 - mu) + cf * (0.0 - mv)) * (conf * g_det);
    // Same encode-headroom clamp as the wide fuse (see its note).
    c = clamp(c, vec3(-0.2), vec3(0.2));
    float seed = max(max(abs(c.r), abs(c.g)), abs(c.b)) * fm0 * fm0;
    imageStore(out_image, gid, vec4(c, seed));
}

//!HOOK MAIN
//!BIND DB2_MED
//!BIND DB2_FITMASK
//!BIND DB2_LP_MX
//!SAVE DB2_LP_H
//!WIDTH DB2_MED.w
//!HEIGHT DB2_MED.h
//!COMPUTE 16 16
//!DESC Debandit: Recon LP H

// =============================================================================
// PASS 8/9: BOUNDED-SMOOTHNESS RECONSTRUCTOR FIELD (v3.0, db_curve)
// =============================================================================
// Fitmask-normalized separable Gaussian (sigma 3 texels = sigma 12px full
// res, radius 8 — the same +-8-texel reach the contamination envelope
// already prices in) over the median field M. The apply blends the
// correction source from the plane fit to c_rc = LP - M on surfaces where
// the wide fit's envelope is clean (see the header v3.0 note for why:
// every LS basis partially FOLLOWS the staircase on curved shading; the
// normalized lowpass crosses it at the measured ceiling).
// Precision: rows accumulate MEAN-SUBTRACTED against the output texel's
// own median (the wide-moments fp16-ulp insurance — raw M sums at fp16
// cost ~0.5 code); the V stage re-references each row in fp32. Off-frame
// taps at zero weight (v1.7 border rule). The H output (signed row sums,
// magnitudes << 1) rides a hook FBO — float on every backend this shader
// already requires (v2.1 storage textures); the 0.5-offset unorm
// insurance is deliberately NOT used here, its ulp-at-0.5 costs more
// than it protects. The signed FIRST MOMENT of the weights (sum w*i,
// texel units) side-outputs to DB2_LP_MX storage: the V stage folds it
// into a support-SYMMETRY fade — a normalized convolution is exact on a
// ramp only under symmetric trusted support; one-sided support (frame
// borders, mask-hole collars) pulls the mean up to ~2.4 texels toward
// the trusted side, a bias the plane fit does not have (audit F1).

const float DB2_LPW_H[9] = float[](1.0, 0.945959, 0.800737, 0.606531,
                                   0.411112, 0.249352, 0.135335,
                                   0.065729, 0.028566);

void hook() {
    ivec2 gid = ivec2(gl_GlobalInvocationID.xy);
    ivec2 sz = ivec2(DB2_MED_size);
    if (gid.x >= sz.x || gid.y >= sz.y) return;
    ivec2 fbnd = min(sz, ivec2(imageSize(DB2_FITMASK))) - 1;
    vec2 pt = DB2_MED_pt;
    vec2 pos = (vec2(gid) + 0.5) * pt;
    vec3 m0 = DB2_MED_tex(pos).rgb;
    vec3 num = vec3(0.0);
    float den = 0.0, mux = 0.0;
    for (int i = -8; i <= 8; i++) {
        float xp = pos.x + float(i) * pt.x;
        float w = DB2_LPW_H[abs(i)]
                * imageLoad(DB2_FITMASK,
                            ivec2(clamp(gid.x + i, 0, fbnd.x),
                                  min(gid.y, fbnd.y))).x
                * step(0.0, xp) * step(xp, 1.0);
        num += w * (DB2_MED_tex(vec2(xp, pos.y)).rgb - m0);
        den += w;
        mux += w * float(i);
    }
    imageStore(out_image, gid, vec4(num, den));
    if (all(lessThan(gid, imageSize(DB2_LP_MX))))
        imageStore(DB2_LP_MX, gid, vec4(mux, 0.0, 0.0, 0.0));
}

//!HOOK MAIN
//!BIND DB2_LP_H
//!BIND DB2_MED
//!BIND DB2_LP_MX
//!SAVE DB2_LPC
//!WIDTH DB2_LP_H.w
//!HEIGHT DB2_LP_H.h
//!COMPUTE 16 16
//!DESC Debandit: Recon LP V

// V stage: rows re-reference from their own median to this texel's in
// fp32 (linear moments shift linearly — the wide-fuse pattern; both M
// reads hit the same texture, so the stored quantization cancels).
// c_rc = LP - M comes out of the normalized division and is stored PURE
// (no gate baked into the field): alpha carries conf x symmetry, and the
// APPLY folds alpha into the BLEND weight — where the lowpass is starved
// or one-sided the blend keeps the plane fit (which is exact there),
// instead of pulling the correction toward zero (audit F2). conf = same
// low-mass knees as the fits; symmetry = fade on the 2D weight centroid
// (texel units; knees 0.5-1.5 — a clean interior measures ~0, a frame
// border ~2.4; measured: kills ~2/3 of the worst-case one-sided ramp
// bias at zero cost to banded-surface coverage).
// ENCODE: raw signed c (v3.5; was 0.5 + 8*c — the x8 only existed to
// dodge the fp16 ulp at the 0.5 offset). The +-0.06 clamp (+-15 codes)
// stays, beyond every gate knee (max cthr ~8.4).
#define DB2_LPC_CONF_LO  0.06
#define DB2_LPC_CONF_HI  0.20
#define DB2_LPW_MASS     56.05
#define DB2_LPC_SYM_LO   0.5
#define DB2_LPC_SYM_HI   1.5

const float DB2_LPW_V[9] = float[](1.0, 0.945959, 0.800737, 0.606531,
                                   0.411112, 0.249352, 0.135335,
                                   0.065729, 0.028566);

void hook() {
    ivec2 gid = ivec2(gl_GlobalInvocationID.xy);
    ivec2 sz = ivec2(DB2_LP_H_size);
    if (gid.x >= sz.x || gid.y >= sz.y) return;
    ivec2 bnd = min(sz, ivec2(imageSize(DB2_LP_MX))) - 1;
    vec2 pt = DB2_LP_H_pt;
    vec2 pos = (vec2(gid) + 0.5) * pt;
    vec3 m0 = DB2_MED_tex(pos).rgb;
    vec3 num = vec3(0.0);
    float den = 0.0, mux = 0.0, muy = 0.0;
    for (int j = -8; j <= 8; j++) {
        float yp = pos.y + float(j) * pt.y;
        float inb = step(0.0, yp) * step(yp, 1.0);
        vec4 h = DB2_LP_H_tex(vec2(pos.x, yp)) * inb;
        vec3 d = (DB2_MED_tex(vec2(pos.x, yp)).rgb - m0) * inb;
        // x guarded like inb, not clamped: past the MX envelope (>4K
        // 1/4-res) a clamped read would fetch a wrong-column moment and
        // corrupt the symmetry fade there (re-audit F4). Degraded, not
        // wrong: sym loses its x term outside the envelope, y term and
        // conf still stand.
        float mxin = step(float(gid.x), float(bnd.x));
        ivec2 tp = ivec2(min(gid.x, bnd.x), clamp(gid.y + j, 0, bnd.y));
        float g = DB2_LPW_V[abs(j)];
        num += g * (h.rgb + h.a * d);
        den += g * h.a;
        mux += g * imageLoad(DB2_LP_MX, tp).x * inb * mxin;
        muy += g * h.a * float(j);
    }
    float conf = smoothstep(DB2_LPC_CONF_LO, DB2_LPC_CONF_HI,
                            den / DB2_LPW_MASS);
    float dn = max(den, 1e-6);
    float sym = 1.0 - smoothstep(DB2_LPC_SYM_LO, DB2_LPC_SYM_HI,
                                 max(abs(mux), abs(muy)) / dn);
    vec3 c = clamp(num / dn, vec3(-0.06), vec3(0.06));
    imageStore(out_image, gid, vec4(c, conf * sym));
}

//!HOOK MAIN
//!BIND HOOKED
//!BIND DB2_DS
//!BIND DB2_MED
//!BIND DB2_RANGEC_H
//!BIND DB2_TENS_H
//!BIND DB2_GRID_RAW
//!SAVE DB2_RANGE_H
//!WIDTH DB2_MED.w
//!HEIGHT DB2_MED.h
//!COMPUTE 16 16
//!DESC Debandit: H Gathers

// =============================================================================
// PASS 10: H GATHERS — flatness range + chroma range + tensor rows (fused)
// =============================================================================
// v2.1: the three H-stage row gathers were three passes each walking MED
// rows; one compute pass now shares the walk and the center decode. All
// three field definitions are v2.0 verbatim:
// RANGE_H (out_image) — x/y: trust-masked separable min/max of median-
// field luma over +-8 texels (center always seeds). z: block MAD of the
// full-res 4x4 block vs the median field, max across channels (RAW here —
// the FLOOR pass reads this pre-dilation; the V pass dilates +-2).
// w: structure field (med5 mean-bias + signed-median coherence x2.5).
// RANGEC_H (storage) — trust-masked min/max of the median field's
// opponent axes over the same +-8 window, stored raw (rgba16f storage;
// the v3.4 o * 0.5 + 0.5 offset only cost fp16 precision, up to ~0.25
// code of chroma range); the chroma V pass reduces them to ranges.
// TENS_H (storage) — central-difference gradients of median-field luma,
// structure-tensor components row-summed over +-4 (clamp-sampling,
// order-stat-class, bias-free); column-summed and eigen-analyzed in the
// Tensor V pass. v3.5: .w = the OPPONENT block MAD (Y-plane dither cancels
// in the projection) from the same 16 full-res taps as the luma MAD —
// Chroma Range V used to re-fetch that whole block.
// GRID_RAW (storage, v2.2) — full-res luma |dx|/|dy| sums over this
// texel's own 4x4 block, split DCT-boundary-phase vs interior. The step
// INTO column x = 16k lands in this block iff gid.x % 4 == 0 (then it is
// the block's column 0); same for rows. The block taps are the MAD walk's
// (shared reads, MAD arithmetic order untouched); the left column / top
// row of neighbor taps complete the differences. x/y = boundary/interior
// |dx| sums, z/w = same for |dy|; per-texel sample counts are implied by
// gid parity (4/12 on a boundary texel, 0/16 off) — the grid evidence
// consumers (the Range V fusion and Tensor V) reconstruct them
// arithmetically, nothing stored.

const vec3 DB2_W709 = vec3(0.2126, 0.7152, 0.0722);

void hook() {
    ivec2 gid = ivec2(gl_GlobalInvocationID.xy);
    ivec2 sz = ivec2(DB2_MED_size);
    if (gid.x >= sz.x || gid.y >= sz.y) return;
    vec2 pt = DB2_MED_pt;
    vec2 pos = (vec2(gid) + 0.5) * pt;

    vec4 center = DB2_MED_tex(pos);
    float lum_c = dot(center.rgb, DB2_W709);
    float lo = lum_c;
    float hi = lum_c;
    vec2 o_c = vec2(center.b - lum_c, center.r - lum_c);
    vec2 loc = o_c;
    vec2 hic = o_c;

    // Shared +-8 row walk: luma range (RANGE_H.xy) + opponent range
    // (RANGEC_H), one MED read per tap instead of two. v3.5: the row lumas
    // are cached (rowY[8 + i]) for the tensor rows' horizontal gradients,
    // which used to re-fetch the same +-5 texels (bit-identical values:
    // same sample positions, same dot).
    float rowY[17];
    rowY[8] = lum_c;
    for (int i = 1; i <= 8; i++) {
        vec4 tp = DB2_MED_tex(pos + vec2(float(i) * pt.x, 0.0));
        vec4 tn = DB2_MED_tex(pos - vec2(float(i) * pt.x, 0.0));
        float yp = dot(tp.rgb, DB2_W709);
        float yn = dot(tn.rgb, DB2_W709);
        rowY[8 + i] = yp;
        rowY[8 - i] = yn;
        float op = step(0.5, tp.a);
        float on = step(0.5, tn.a);
        lo = min(lo, min(mix(1e3, yp, op), mix(1e3, yn, on)));
        hi = max(hi, max(mix(-1e3, yp, op), mix(-1e3, yn, on)));
        vec2 opp = vec2(tp.b - yp, tp.r - yp);
        vec2 onn = vec2(tn.b - yn, tn.r - yn);
        loc = min(loc, min(mix(vec2( 1e3), opp, op), mix(vec2( 1e3), onn, on)));
        hic = max(hic, max(mix(vec2(-1e3), opp, op), mix(vec2(-1e3), onn, on)));
    }

    vec2 fpt = HOOKED_pt;
    float mad = 0.0;
    float mad_c = 0.0;   // v3.5: opponent block MAD for Chroma Range V
    float blkY[16];
    for (int y = 0; y < 4; y++) {
        for (int x = 0; x < 4; x++) {
            vec2 off = vec2(float(x) - 1.5, float(y) - 1.5) * fpt;
            vec3 t = HOOKED_tex(pos + off).rgb;
            vec3 ds = t - center.rgb;
            vec3 d = abs(ds);
            mad += max(d.r, max(d.g, d.b));
            vec3 d_op = ds - vec3(dot(ds, DB2_W709));
            mad_c += max(max(abs(d_op.r), abs(d_op.g)), abs(d_op.b));
            blkY[y * 4 + x] = dot(t, DB2_W709);
        }
    }
    mad *= (1.0 / 16.0);
    mad_c *= (1.0 / 16.0);

    // v2.2 grid raw stats (see header). All indices are unroll-constant.
    // v3.5: db_block = 0 skips the whole grid chain (here, Grid Env H and
    // the Tensor V evidence) on a UNIFORM branch — every consumer already
    // multiplies the evidence by db_block, so the output is identical.
    vec4 graw = vec4(0.0);
    if (db_block > 0.0) {
    float lftY[4], topY[4];
    for (int k = 0; k < 4; k++) {
        lftY[k] = dot(HOOKED_tex(pos + vec2(-2.5, float(k) - 1.5) * fpt).rgb, DB2_W709);
        topY[k] = dot(HOOKED_tex(pos + vec2(float(k) - 1.5, -2.5) * fpt).rgb, DB2_W709);
    }
    float gcol0 = 0.0, gcolr = 0.0, grow0 = 0.0, growr = 0.0;
    for (int y = 0; y < 4; y++) {
        for (int x = 0; x < 4; x++) {
            float yv = blkY[y * 4 + x];
            // Index clamps guard the ternaries' DEAD branches (audit:
            // pre-unroll lowering to select would evaluate blkY[-1]).
            float dxv = abs(yv - ((x == 0) ? lftY[y] : blkY[max(y * 4 + x - 1, 0)]));
            float dyv = abs(yv - ((y == 0) ? topY[x] : blkY[max((y - 1) * 4 + x, 0)]));
            if (x == 0) { gcol0 += dxv; } else { gcolr += dxv; }
            if (y == 0) { grow0 += dyv; } else { growr += dyv; }
        }
    }
    bool gbx = (gid.x & 3) == 0;
    bool gby = (gid.y & 3) == 0;
    graw = vec4(gbx ? gcol0 : 0.0, gbx ? gcolr : (gcol0 + gcolr),
                gby ? grow0 : 0.0, gby ? growr : (grow0 + growr));
    }

    float s0 = DB2_DS_tex(pos - vec2(2.0 * pt.x, 0.0)).a - lum_c;
    float s1 = DB2_DS_tex(pos - vec2(pt.x, 0.0)).a - lum_c;
    float s2 = DB2_DS_tex(pos).a - lum_c;
    float s3 = DB2_DS_tex(pos + vec2(pt.x, 0.0)).a - lum_c;
    float s4 = DB2_DS_tex(pos + vec2(2.0 * pt.x, 0.0)).a - lum_c;

    float d0 = abs(s0); float d1 = abs(s1); float d2 = abs(s2);
    float d3 = abs(s3); float d4 = abs(s4);
    float f = max(min(d0, d1), min(d3, d4));
    float g = min(max(d0, d1), max(d3, d4));
    float tex = max(min(d2, f), min(max(d2, f), g));

    float fs = max(min(s0, s1), min(s3, s4));
    float gs = min(max(s0, s1), max(s3, s4));
    float coh = abs(max(min(s2, fs), min(max(s2, fs), gs)));

    // v3.5: db_aniso = 0 skips the tensor rows (uniform branch; the Apply
    // multiplies the flag by db_aniso, output identical). Stored x4096 —
    // raw squared gradients sit in fp16 SUBNORMALS on quiet sources (the
    // aniso knee there is ~1e-7), where the flag read rounding noise; the
    // power-of-two scale is exact wherever the values were normal.
    vec3 J = vec3(0.0);   // (sum gx^2, sum gy^2, sum gx*gy)
    if (db_aniso > 0.0) {
    for (int i = -4; i <= 4; i++) {
        vec2 tj = pos + vec2(float(i) * pt.x, 0.0);
        float xr = rowY[9 + i];
        float xl = rowY[7 + i];
        float yd = dot(DB2_MED_tex(tj + vec2(0.0, pt.y)).rgb, DB2_W709);
        float yu = dot(DB2_MED_tex(tj - vec2(0.0, pt.y)).rgb, DB2_W709);
        float gx = (xr - xl) * 0.5;
        float gy = (yd - yu) * 0.5;
        J += vec3(gx * gx, gy * gy, gx * gy);
    }
    }

    imageStore(out_image, gid, vec4(lo, hi, mad, max(tex, 2.5 * coh)));
    if (all(lessThan(gid, imageSize(DB2_RANGEC_H)))) {
        imageStore(DB2_RANGEC_H, gid, vec4(loc, hic).xzyw);
        imageStore(DB2_TENS_H, gid, vec4(J * 4096.0, mad_c));
    }
    if (all(lessThan(gid, imageSize(DB2_GRID_RAW)))) {
        imageStore(DB2_GRID_RAW, gid, graw);
    }
}

//!HOOK MAIN
//!BIND DB2_RANGE_H
//!BIND DB2_STATE
//!SAVE DB2_FLOORTEX
//!WIDTH 1
//!HEIGHT 1
//!COMPUTE 32 32
//!DESC Debandit: Noise Floor

// =============================================================================
// PASS 11: FRAME NOISE FLOOR — COMPUTE reduce (CelFlare Frame Stats pattern)
// =============================================================================
// One 32x32 workgroup, 1024 lanes (v3.4 — was 16x9/144: a single skinny
// workgroup serialized the reduce into 23-31% of the whole shader's GPU
// time on the desktop D3D11 target; 1024 is exactly the cs_5_0/Metal/
// Vulkan per-group ceiling, and the histogram is integer atomics, so ANY
// lane count yields the identical bin counts -> identical percentile,
// EMA and SSBO state, bitwise. CONSTANT SYNC: the loop stride below must
// equal the COMPUTE block's lane count. HARD REQUIREMENT: a backend whose
// per-group invocation cap is under 1024 makes libplacebo drop this pass
// SILENTLY — the floor/dfl then never update (fresh SSBO: no fsnap shift,
// aniso floor pinned to its minimum), a degraded-not-broken state; every
// shipped target (D3D11 FL11+, desktop Vulkan, Apple-silicon Metal) caps
// at exactly 1024 or higher.) Each lane strides the 1/4-res grid
// accumulating a shared 64-bin log histogram of the RAW block MADs
// (DB2_RANGE_H.z, pre-dilation); thread 0 walks the CDF to the 10th
// percentile — the frame's true dither/grain floor: quiet-but-noisy flat
// blocks land there, textured blocks sit far above, pristine digital
// flats below (the knee floors catch that case). A 2-3 frame EMA smooths
// knee flicker on cuts; first frame (or SSBO fresh from init) locks on
// instantly, so headless single-frame runs are deterministic. The SSBO
// survives shader reload (known caveat) — convergence in ~3 frames makes
// a stale carry harmless.
#define DB2_FLOOR_BINS   64
#define DB2_FLOOR_LO     (0.02 / 255.0)
#define DB2_FLOOR_HI     (24.0 / 255.0)
#define DB2_FLOOR_PCT    0.10
#define DB2_FLOOR_ALPHA  0.4
#define DB2_DFL_GATE     (2.0 / 255.0)
#define DB2_DFL_MADCAP   (6.0 / 255.0)
#define DB2_DFL_MADFLOOR (0.05 / 255.0)
#define DB2_DFL_PCT      0.50
// v3.1 knee-shift mapping (codes) — hinge/gain/cap calibrated in dev
// debandit-v31-floorsnap. Computed HERE (single source) and consumed
// pre-scaled by db_fsnap in the Apply (snap knees, via FLOORTEX .z)
// and Tensor V (aniso significance lift, via the SSBO) — no
// cross-pass define duplication.
#define DB2_FLS_LO    0.35
#define DB2_FLS_GAIN  3.5
#define DB2_FLS_CAP   1.8

shared uint db2_hist[DB2_FLOOR_BINS];
shared uint db2_hist_f[DB2_FLOOR_BINS];
shared uint db2_count;
shared uint db2_count_f;
shared uint db2_count_p;

void hook() {
    uint lid = gl_LocalInvocationIndex;   // 0..1023
    if (lid < uint(DB2_FLOOR_BINS)) {
        db2_hist[lid] = 0u;
        db2_hist_f[lid] = 0u;
    }
    if (lid == 0u) { db2_count = 0u; db2_count_f = 0u; db2_count_p = 0u; }
    barrier();

    // Strided subsample: a global percentile is statistically indifferent
    // to it and it divides the reduce cost (measured 0.28 ms full-grid on
    // M2-class hardware — the one pass that overshot the design budget).
    // v3.5: the stride grows with the frame so the sample count stays
    // ~32k (1080p: 2, exactly the v3.4 grid; 4K: 4). One lone workgroup
    // walks this grid serially, so on 4K MAIN the 2x2 stride made it
    // ~8% of the shader for 4x the samples a p10/p50 needs.
    ivec2 fsz = ivec2(DB2_RANGE_H_size);
    int st = max(2, int(sqrt(float(fsz.x * fsz.y) / 32400.0) + 0.001));
    ivec2 sz = fsz / st;
    int total = sz.x * sz.y;
    float log_lo = log2(DB2_FLOOR_LO);
    float log_hi = log2(DB2_FLOOR_HI);
    uint mine = 0u;
    uint mine_f = 0u;
    uint mine_p = 0u;
    for (int t = int(lid); t < total; t += 1024) {
        ivec2 p = ivec2((t % sz.x) * st, (t / sz.x) * st);
        vec4 rh = texelFetch(DB2_RANGE_H_raw, p, 0);
        float mad = rh.z;
        float lg = log2(max(mad, DB2_FLOOR_LO));
        int bin = clamp(int((lg - log_lo) / (log_hi - log_lo)
                            * float(DB2_FLOOR_BINS)), 0, DB2_FLOOR_BINS - 1);
        atomicAdd(db2_hist[bin], 1u);
        mine++;
        // v3.1 second histogram: FLAT-FIELD dither level. Row-flat gate
        // on the trust-masked +-8 range (.xy); the MAD cap excludes the
        // all-untrusted-row collapse (lo = hi = center seed reads range
        // 0 on dense structure whose MAD is edge-sized, not dither).
        // PRISTINE flat blocks (MAD ~ 0: solid fills, letterbox bars,
        // flat blacks) are counted but do NOT vote in the histogram —
        // audit F1: a majority-pristine frame would otherwise dilute
        // the p50 into bin 0 and zero the shift on exactly the mixed
        // pristine-black + dithered-sky class the feature exists for.
        if (rh.y - rh.x < DB2_DFL_GATE && mad < DB2_DFL_MADCAP) {
            if (mad > DB2_DFL_MADFLOOR) {
                atomicAdd(db2_hist_f[bin], 1u);
                mine_f++;
            } else {
                mine_p++;
            }
        }
    }
    atomicAdd(db2_count, mine);
    atomicAdd(db2_count_f, mine_f);
    atomicAdd(db2_count_p, mine_p);
    barrier();

    if (lid == 0u) {
        uint target = uint(float(db2_count) * DB2_FLOOR_PCT);
        uint acc = 0u;
        int hit = DB2_FLOOR_BINS - 1;
        for (int i = 0; i < DB2_FLOOR_BINS; i++) {
            acc += db2_hist[i];
            if (acc >= target) { hit = i; break; }
        }
        // geometric bin center
        float frac = (float(hit) + 0.5) / float(DB2_FLOOR_BINS);
        float new_floor = exp2(log_lo + frac * (log_hi - log_lo));
        // Instant re-lock on first frame AND on large jumps (audit F1):
        // the floor gates the SNAP REGIME via the aniso knees, so a 2-3
        // frame EMA settle after a hard cut (grainy -> quiet or back)
        // would flicker texture protection exactly when the eye resets.
        // A >50% relative move is a content change, not grain wobble —
        // lock on immediately; the EMA only smooths within-scene motion.
        bool relock = db2_floor <= 0.0
                   || abs(new_floor - db2_floor) > 0.5 * db2_floor;
        db2_floor = relock ? new_floor
                           : mix(db2_floor, new_floor, DB2_FLOOR_ALPHA);

        // v3.1 flat-field dither level: p50 of the DITHERED flat blocks.
        // Three-way branch (audit F1 + its counter-bug, both measured):
        // - Enough dithered votes (>= 0.5% of samples, min 64) AND the
        //   dithered flats are >= 10% of the whole flat population ->
        //   trust the p50. The ratio guard stops a ~1-2% spurious
        //   minority from opening the knees frame-wide on pristine
        //   content (remux t=20 measured exactly that without it); a
        //   real minority sky next to a black foreground is >= 10% of
        //   the flats and still gets its shift (synthetic: 23% -> full).
        // - Otherwise, if the flats are there but PRISTINE-dominant ->
        //   converge to the histogram floor (static knees). This is a
        //   content statement, not a data gap — holding here would latch
        //   a stale high dfl from the previous scene onto pristine
        //   content, where shifted knees eat faint REAL sub-code detail.
        // - No flats at all -> HOLD the previous level (action frames;
        //   snap has no w_flat surface to act on anyway). Fresh SSBO =
        //   0 -> no shift until the first valid frame (warming-guard-
        //   inside-the-gate rule).
        uint need_f = max(64u, db2_count / 200u);
        if (db2_count_f >= need_f && 9u * db2_count_f >= db2_count_p) {
            uint target_f = uint(float(db2_count_f) * DB2_DFL_PCT);
            uint acc_f = 0u;
            int hit_f = DB2_FLOOR_BINS - 1;
            for (int i = 0; i < DB2_FLOOR_BINS; i++) {
                acc_f += db2_hist_f[i];
                if (acc_f >= target_f) { hit_f = i; break; }
            }
            float frac_f = (float(hit_f) + 0.5) / float(DB2_FLOOR_BINS);
            float new_dfl = exp2(log_lo + frac_f * (log_hi - log_lo));
            bool relock_f = db2_dfl <= 0.0
                         || abs(new_dfl - db2_dfl) > 0.5 * db2_dfl;
            db2_dfl = relock_f ? new_dfl
                               : mix(db2_dfl, new_dfl, DB2_FLOOR_ALPHA);
        } else if (db2_count_p >= need_f) {
            float new_dfl = DB2_FLOOR_LO;
            bool relock_f = db2_dfl <= 0.0
                         || abs(new_dfl - db2_dfl) > 0.5 * db2_dfl;
            db2_dfl = relock_f ? new_dfl
                               : mix(db2_dfl, new_dfl, DB2_FLOOR_ALPHA);
        }
        // Raw knee shift in codes (no EMA of its own — db2_dfl already
        // carries it). Deterministic derivation, stored for consumers.
        db2_dshift = min(DB2_FLS_GAIN * max(db2_dfl * 255.0 - DB2_FLS_LO, 0.0),
                         DB2_FLS_CAP);
        imageStore(out_image, ivec2(0),
                   vec4(db2_floor, db2_dfl, db2_dshift, 1.0));
    }
}

//!HOOK MAIN
//!BIND DB2_RANGE_H
//!BIND DB2_GRID_RAW
//!BIND DB2_GRIDH
//!BIND DB2_RES_S
//!SAVE DB2_RANGE
//!WIDTH DB2_RANGE_H.w
//!HEIGHT DB2_RANGE_H.h
//!COMPUTE 16 16
//!DESC Debandit: Flatness Range V + Grid Env H

// PASS 12 — V half of the range/noise reduction (identical to v1.11):
// min/max over +-8; MAD max-dilates over +-2; structure field means over
// +-2. Reads the fused H Gathers pass's RANGE_H save texture.
//
// v3.4: GRID ENV H (v2.2) fuses in — an unrelated walk sharing the
// dispatch (the v2.1 H-Gathers idiom): row sums of the grid raw stats
// over +-8 texels, written to GRIDH storage. Plain sums (accumulated
// fp32, stored fp16 — magnitudes <= ~1 in linear units, ratio-classifier
// precision is ample). Out-of-frame and beyond-envelope taps are
// SKIPPED, not clamped: the Tensor V stage reconstructs the exact
// per-axis sample counts from gid arithmetic, and a clamped tap would
// double-count its edge texel. Sample counts are NOT stored — they are
// implied by which texels t in the window satisfy t % 4 == 0.
//
// v3.5 OUTPUT LAYOUT: x = range (hi - lo), y = WIDE FIT RESIDUAL luma
// (copied from the fused Wide Fuse's DB2_RES_S — the Wide Residual pass
// retired), z = dilated MAD, w = structure. The Apply only ever used
// hi - lo, so the range is formed here from the fp16-ROUNDED extrema
// (db2_h16): an exact Sterbenz difference for any range the flatness gate
// can see, i.e. the same value the Apply used to subtract. Beyond the
// storage envelope (> 4096 wide) the residual reads as a huge value —
// fail closed: the wide scale is condemned and the short fit carries it.

float db2_h16(float x) {
    return unpackHalf2x16(packHalf2x16(vec2(x, 0.0))).x;
}

void hook() {
    ivec2 gid = ivec2(gl_GlobalInvocationID.xy);
    ivec2 sz = ivec2(DB2_RANGE_H_size);
    if (gid.x >= sz.x || gid.y >= sz.y) return;
    vec2 pt = DB2_RANGE_H_pt;
    vec2 pos = (vec2(gid) + 0.5) * pt;

    vec4 mm = DB2_RANGE_H_tex(pos);

    for (int i = 1; i <= 8; i++) {
        vec4 sp = DB2_RANGE_H_tex(pos + vec2(0.0, float(i) * pt.y));
        vec4 sn = DB2_RANGE_H_tex(pos - vec2(0.0, float(i) * pt.y));
        mm.x = min(mm.x, min(sp.x, sn.x));
        mm.y = max(mm.y, max(sp.y, sn.y));
        if (i <= 2) {
            mm.z = max(mm.z, max(sp.z, sn.z));
            mm.w += sp.w + sn.w;
        }
    }
    mm.w *= (1.0 / 5.0);
    float res_y = all(lessThan(gid, imageSize(DB2_RES_S)))
                ? imageLoad(DB2_RES_S, gid).x : 1.0;
    imageStore(out_image, gid,
               vec4(db2_h16(mm.y) - db2_h16(mm.x), res_y, mm.z, mm.w));

    ivec2 bnd = min(sz, ivec2(imageSize(DB2_GRID_RAW)));
    vec4 acc = vec4(0.0);
    if (db_block > 0.0) {   // v3.5 uniform skip (see H Gathers)
        for (int i = -8; i <= 8; i++) {
            int x = gid.x + i;
            if (x < 0 || x >= bnd.x || gid.y >= bnd.y) continue;
            acc += imageLoad(DB2_GRID_RAW, ivec2(x, gid.y));
        }
    }
    if (all(lessThan(gid, imageSize(DB2_GRIDH))))
        imageStore(DB2_GRIDH, gid, acc);
}

//!HOOK MAIN
//!BIND DB2_DS
//!BIND DB2_MED
//!BIND DB2_RANGEC_H
//!BIND DB2_TENS_H
//!BIND DB2_CORR
//!BIND DB2_SCORR
//!BIND DB2_ENV_H
//!SAVE DB2_RANGEC
//!WIDTH DB2_MED.w
//!HEIGHT DB2_MED.h
//!COMPUTE 16 16
//!DESC Debandit: Chroma Range V + Contamination Env H

// =============================================================================
// PASS 13: CHROMA FLATNESS RANGE + CHROMA NOISE, V half (identical to v1.11)
// =============================================================================
// Reduces the fused H pass's opponent min/max (raw, v3.5) to ranges and
// adds the opponent block MAD (Y-plane
// dither cancels exactly in the projection) + the chroma structure/
// coherence field. v2.1: RANGEC_H is a storage texture -> compute pass,
// imageLoad; the sampler reads are v2.0 verbatim.
//
// v3.4: CONTAMINATION ENV H fuses in — an unrelated +-8 row walk sharing
// the dispatch (the v2.1 idiom), written to ENV_H storage for the Env V
// pass. Identical machinery to v1.11 (remote-bias insurance): x/y = wide
// luma/chroma, z/w = short luma/chroma. Each luma seed is the CORR alpha
// verbatim (fp32-exact); each chroma seed is that seed times the bounded
// opponent ratio of the decoded c.

const vec3 DB2_RCV_W709 = vec3(0.2126, 0.7152, 0.0722);

void hook() {
    ivec2 gid = ivec2(gl_GlobalInvocationID.xy);
    ivec2 sz = ivec2(DB2_MED_size);
    if (gid.x >= sz.x || gid.y >= sz.y) return;
    ivec2 bnd = min(sz, imageSize(DB2_RANGEC_H)) - 1;
    vec2 pt = DB2_MED_pt;
    vec2 pos = (vec2(gid) + 0.5) * pt;

    // ---- Contamination Env H (v3.4 fusion; seeds walk, v1.11 verbatim) ----
    vec4 envh = vec4(0.0);
    for (int i = -8; i <= 8; i++) {
        vec2 tp = pos + vec2(float(i) * pt.x, 0.0);
        vec4 tL = DB2_CORR_tex(tp);
        vec4 tS = DB2_SCORR_tex(tp);
        vec3 cL = tL.rgb;
        vec3 cS = tS.rgb;
        vec3 oL = cL - vec3(dot(cL, DB2_RCV_W709));
        vec3 oS = cS - vec3(dot(cS, DB2_RCV_W709));
        float mcL = max(max(abs(cL.r), abs(cL.g)), abs(cL.b));
        float mcS = max(max(abs(cS.r), abs(cS.g)), abs(cS.b));
        float moL = max(max(abs(oL.r), abs(oL.g)), abs(oL.b));
        float moS = max(max(abs(oS.r), abs(oS.g)), abs(oS.b));
        envh.x = max(envh.x, tL.a);
        envh.y = max(envh.y, tL.a * moL / max(mcL, 1e-6));
        envh.z = max(envh.z, tS.a);
        envh.w = max(envh.w, tS.a * moS / max(mcS, 1e-6));
    }
    if (all(lessThan(gid, imageSize(DB2_ENV_H))))
        imageStore(DB2_ENV_H, gid, envh);

    vec4 mm = imageLoad(DB2_RANGEC_H, min(gid, bnd));
    vec2 lo = mm.xz;
    vec2 hi = mm.yw;

    for (int i = 1; i <= 8; i++) {
        vec4 sp = imageLoad(DB2_RANGEC_H, ivec2(min(gid.x, bnd.x), clamp(gid.y + i, 0, bnd.y)));
        vec4 sn = imageLoad(DB2_RANGEC_H, ivec2(min(gid.x, bnd.x), clamp(gid.y - i, 0, bnd.y)));
        lo = min(lo, min(sp.xz, sn.xz));
        hi = max(hi, max(sp.yw, sn.yw));
    }
    vec2 rng = hi - lo;

    vec4 center = DB2_MED_tex(pos);
    // v3.5: opponent block MAD computed in H Gathers (TENS_H .w, same taps,
    // same order — bit-identical). Beyond the storage envelope it reads as
    // huge: chroma snap closes there (fail closed, documented envelope).
    float mad_c = all(lessThan(gid, imageSize(DB2_TENS_H)))
                ? imageLoad(DB2_TENS_H, gid).w : 1.0;

    float y_c = dot(center.rgb, DB2_RCV_W709);
    vec2 o_c = vec2(center.b - y_c, center.r - y_c);
    vec4 d0t = DB2_DS_tex(pos - vec2(0.0, 2.0 * pt.y));
    vec4 d1t = DB2_DS_tex(pos - vec2(0.0, pt.y));
    vec4 d2t = DB2_DS_tex(pos);
    vec4 d3t = DB2_DS_tex(pos + vec2(0.0, pt.y));
    vec4 d4t = DB2_DS_tex(pos + vec2(0.0, 2.0 * pt.y));
    vec2 s0 = vec2(d0t.b - d0t.a, d0t.r - d0t.a) - o_c;
    vec2 s1 = vec2(d1t.b - d1t.a, d1t.r - d1t.a) - o_c;
    vec2 s2 = vec2(d2t.b - d2t.a, d2t.r - d2t.a) - o_c;
    vec2 s3 = vec2(d3t.b - d3t.a, d3t.r - d3t.a) - o_c;
    vec2 s4 = vec2(d4t.b - d4t.a, d4t.r - d4t.a) - o_c;

    vec2 a0 = abs(s0); vec2 a1 = abs(s1); vec2 a2 = abs(s2);
    vec2 a3 = abs(s3); vec2 a4 = abs(s4);
    vec2 f = max(min(a0, a1), min(a3, a4));
    vec2 g = min(max(a0, a1), max(a3, a4));
    vec2 tex = max(min(a2, f), min(max(a2, f), g));

    vec2 fs = max(min(s0, s1), min(s3, s4));
    vec2 gs = min(max(s0, s1), max(s3, s4));
    vec2 coh = abs(max(min(s2, fs), min(max(s2, fs), gs)));

    vec2 sc = max(tex, 2.5 * coh);
    imageStore(out_image, gid, vec4(rng, mad_c, max(sc.x, sc.y)));
}

//!HOOK MAIN
//!BIND DB2_ENV_H
//!BIND DB2_MED
//!SAVE DB2_ENV
//!WIDTH DB2_MED.w
//!HEIGHT DB2_MED.h
//!COMPUTE 16 16
//!DESC Debandit: Contamination Env V

// =============================================================================
// PASS 14: CONTAMINATION ENVELOPE V — separable max of seeds over +-8
// =============================================================================
// V half of the +-8 separable max (the H half rides the Chroma Range V
// pass since v3.4 — see its header). imageLoads ENV_H with clamped
// coordinates, which is exactly the clamp-to-edge sampling the fragment
// version had; max() of the same values in the same order.

void hook() {
    ivec2 gid = ivec2(gl_GlobalInvocationID.xy);
    ivec2 sz = ivec2(DB2_MED_size);
    if (gid.x >= sz.x || gid.y >= sz.y) return;
    ivec2 bnd = min(sz, ivec2(imageSize(DB2_ENV_H))) - 1;
    int gx = min(gid.x, bnd.x);
    vec4 m = imageLoad(DB2_ENV_H, ivec2(gx, min(gid.y, bnd.y)));
    for (int i = 1; i <= 8; i++) {
        m = max(m, imageLoad(DB2_ENV_H, ivec2(gx, clamp(gid.y + i, 0, bnd.y))));
        m = max(m, imageLoad(DB2_ENV_H, ivec2(gx, clamp(gid.y - i, 0, bnd.y))));
    }
    imageStore(out_image, gid, m);
}

//!HOOK MAIN
//!BIND DB2_MED
//!BIND DB2_TENS_H
//!BIND DB2_GRID_RAW
//!BIND DB2_GRIDH
//!BIND DB2_STATE
//!BIND DB2_RES_S
//!SAVE DB2_ANISO
//!WIDTH DB2_MED.w
//!HEIGHT DB2_MED.h
//!COMPUTE 16 16
//!DESC Debandit: Tensor V + Aniso Flag + Grid Evidence

// =============================================================================
// PASS 15: STRUCTURE-TENSOR ANISOTROPY — the re-armed v1.11 experiment
// =============================================================================
// The tensor row sums come from the fused H Gathers pass (TENS_H storage,
// see its header for the discriminator rationale); this pass column-sums
// over +-4 and eigen-analyzes. Banding contours are locally 1-D (all
// gradient energy on one axis, coherence ~1); mid-scale mottle is 2-D.
// What made this UNUSABLE in v1.11 was significance-vs-local-MAD (texture
// inflates MAD and licenses its own absorption — circular); significance
// is now measured against the FRAME NOISE FLOOR from the SSBO. Dither is
// isotropic too but measures ~0.3x floor on the minor axis: under the knee.
// Eigen split: lambda_min = tr/2 - sqrt((dJ/2)^2 + Jxy^2) is the gradient
// energy on the MINOR axis — zero for anything locally 1-D regardless of
// orientation. amp_iso = its rms in code-value units; coherence kappa in
// [0,1]. Flag = significant AND isotropic. The floor is clamped to 0.05
// codes so pristine synthetic content cannot produce a zero knee.
// v2.2 grid evidence (y/z channels). V-sums the Grid Env H rows over +-8
// (window = +-34px full res), reconstructs the exact per-axis sample
// counts from gid arithmetic (boundary texels are t % 4 == 0; GridH
// SKIPPED out-of-frame/beyond-envelope taps, so counts use the same
// bounds), and classifies: CONTRAST = boundary-phase mean / interior mean
// (knee 2.2-3.0 — BD content's own faint MB grid reads ~1.65 on flats,
// max ~3.4; real texture raises the interior equally and self-normalizes
// to ~1), gated by EXCESS significance vs the frame noise floor. The
// stored evidence is db_block-weighted here (DYNAMIC, file-scoped):
// y = confidence, z = confidence-weighted boundary excess (linear units).
// Dead-flat denominators are clamped (a stamp in a dead flat is exactly
// the fire case — the numerator carries it; the clamp only stops 0/0).
#define DB2_AN_AMP_LO  0.35
#define DB2_AN_AMP_HI  0.9
#define DB2_AN_KAP_LO  0.55
#define DB2_AN_KAP_HI  0.95
#define DB2_AN_FLOOR_MIN (0.05 / 255.0)
#define DB2_GRID_CON_LO  2.2
#define DB2_GRID_CON_HI  3.0
#define DB2_GRID_AMP_LO  0.35
#define DB2_GRID_AMP_HI  0.7
#define DB2_GRID_DEN_MIN (0.01 / 255.0)

void hook() {
    ivec2 gid = ivec2(gl_GlobalInvocationID.xy);
    ivec2 sz = ivec2(DB2_MED_size);
    if (gid.x >= sz.x || gid.y >= sz.y) return;
    ivec2 bnd = min(sz, imageSize(DB2_TENS_H)) - 1;
    float fl = max(db2_floor, DB2_AN_FLOOR_MIN);
    // v3.5: db_aniso = 0 / db_block = 0 skip their halves on uniform
    // branches (the Apply multiplies both by their knob — identical out).
    float flag = 0.0;
    if (db_aniso > 0.0) {
    vec3 J = vec3(0.0);
    for (int j = -4; j <= 4; j++) {
        J += imageLoad(DB2_TENS_H, ivec2(min(gid.x, bnd.x), clamp(gid.y + j, 0, bnd.y))).xyz;
    }
    J *= (1.0 / (81.0 * 4096.0));   // H Gathers stores x4096 (see there)

    float tr = J.x + J.y;
    float dq = sqrt(0.25 * (J.x - J.y) * (J.x - J.y) + J.z * J.z);
    float amp_iso = sqrt(max(0.5 * tr - dq, 0.0));
    float kappa = 2.0 * dq / max(tr, 1e-12);

    // v3.1: the ANISO significance reference lifts to the flat-field
    // dither level wherever the snap knees shifted (same statistic,
    // same hinge, same knob). The p10 floor under-reads mixed
    // pristine+dithered frames (t=1176: floor 0.05 vs sky dither 0.9)
    // and the flag fired on the dither ITSELF — leaving mid-flat noise
    // islands once v3.1 cleaned their surroundings. Dither measures
    // ~0.3x its own level on the minor axis, so lifting the reference
    // to db2_dfl preserves the calibrated 0.35 knee ratio; real mottle
    // ABOVE the frame's dither level keeps its protection (the v2
    // promise as worded). min(shift,1) ramps the lift in over the
    // hinge; below it (quiet sources) and at db_fsnap = 0 the lift is
    // exactly +0.0 and this line is inert (bit-exact escapes intact).
    // The GRID branch keeps the raw p10 fl: its contrast term already
    // self-normalizes against dither.
    float fl_an = max(fl, db2_dfl * min(db_fsnap * db2_dshift, 1.0));
    flag = smoothstep(DB2_AN_AMP_LO * fl_an, DB2_AN_AMP_HI * fl_an, amp_iso)
         * (1.0 - smoothstep(DB2_AN_KAP_LO, DB2_AN_KAP_HI, kappa));
    }

    // ---- v2.2 grid evidence ----
    float g_conf = 0.0, g_exc = 0.0;
    if (db_block > 0.0) {
    ivec2 gbnd = min(sz, ivec2(imageSize(DB2_GRID_RAW)));
    vec4 acc = vec4(0.0);
    for (int j = -8; j <= 8; j++) {
        int y = gid.y + j;
        if (y < 0 || y >= gbnd.y || gid.x >= gbnd.x) continue;
        // v3.4: GRIDH is storage (written by the fused Range V pass);
        // same skip-not-clamp guard, same values.
        acc += imageLoad(DB2_GRIDH, ivec2(gid.x, y));
    }
    int lox = max(gid.x - 8, 0), hix = min(gid.x + 8, gbnd.x - 1);
    int loy = max(gid.y - 8, 0), hiy = min(gid.y + 8, gbnd.y - 1);
    int nx = hix - lox + 1, ny = hiy - loy + 1;
    int nbx = max(hix / 4 - (lox + 3) / 4 + 1, 0);
    int nby = max(hiy / 4 - (loy + 3) / 4 + 1, 0);
    float c_bx = float(4 * nbx * ny), c_ix = float((16 * nx - 4 * nbx) * ny);
    float c_by = float(4 * nx * nby), c_iy = float(16 * nx * ny - 4 * nx * nby);
    float Bx = acc.x / max(c_bx, 1.0), Ix = acc.y / max(c_ix, 1.0);
    float By = acc.z / max(c_by, 1.0), Iy = acc.w / max(c_iy, 1.0);

    float den = max(0.25 * fl, DB2_GRID_DEN_MIN);
    float con_x = Bx / max(Ix, den);
    float con_y = By / max(Iy, den);
    float exc_x = max(Bx - Ix, 0.0);
    float exc_y = max(By - Iy, 0.0);
    float amp_lo = DB2_GRID_AMP_LO * fl, amp_hi = DB2_GRID_AMP_HI * fl;
    float cx = smoothstep(DB2_GRID_CON_LO, DB2_GRID_CON_HI, con_x)
             * smoothstep(amp_lo, amp_hi, exc_x);
    float cy = smoothstep(DB2_GRID_CON_LO, DB2_GRID_CON_HI, con_y)
             * smoothstep(amp_lo, amp_hi, exc_y);
    g_conf = db_block * max(cx, cy);
    // Excess clamps to db_thr HERE (audit F1) — blocking never measures
    // past ~db_thr codes of step; beyond that is phase-aligned real
    // structure. Apply must not re-clamp (bit-exactness note there).
    g_exc  = db_block * min(max(cx * exc_x, cy * exc_y),
                            db_thr * (1.0 / 255.0));
    }

    // v3.5: .w carries the wide fit's OPPONENT residual (see Wide Fuse);
    // out-of-envelope fails closed like the luma one in Flatness Range V.
    float res_o = all(lessThan(gid, imageSize(DB2_RES_S)))
                ? imageLoad(DB2_RES_S, gid).y : 1.0;
    imageStore(out_image, gid, vec4(flag, g_conf, g_exc, res_o));
}

//!HOOK MAIN
//!BIND HOOKED
//!BIND DB2_MED
//!BIND DB2_CORR
//!BIND DB2_SCORR
//!BIND DB2_ENV
//!BIND DB2_RANGE
//!BIND DB2_RANGEC
//!BIND DB2_ANISO
//!BIND DB2_LPC
//!BIND DB2_FLOORTEX
//!DESC Debandit: Apply

// =============================================================================
// PASS 16: APPLY (full resolution)
// =============================================================================
// v1.11's apply with the three v2 changes marked inline:
//  (v2-a) per-scale validity = envelope gate x pointwise WIDE fit-residual
//         gate per axis (no dilation on the residual);
//  (v2-b) validity-normalized convex scale blend + blended-validity gate;
//  (v2-c) the anisotropy flag closes the snap regime (LF correction stays).
//  (v3)   correction-SOURCE blend fit -> bounded lowpass, weight
//         db_curve x LP alpha, envelope bypassed in proportion — see the
//         blend note below;
//  (v3.1) luma snap knees shift by the flat-field dither level
//         (db_fsnap — see the knee-shift note at the snap regime).
// Everything else — knees, snap regimes, dark bias, SNR knee, authority,
// opponent decomposition, clip guard — is v1.11 verbatim.

#define DB2_CODE (1.0 / 255.0)

// Snap regime knees on the DILATED MAD, in code values (v1.11 verbatim).
#define DB2_SNAP_MAD_LO  1.3
#define DB2_SNAP_MAD_HI  2.1
#define DB2_SNAP_MAD_LO_D  0.6
#define DB2_SNAP_MAD_HI_D  1.6
#define DB2_DARK_LO      0.10
#define DB2_DARK_HI      0.30
// Structure-field knee + SNR adaptation (v1.11 verbatim; the chroma knee
// is EXEMPT from SNR adaptation — v1.11 audit F2).
#define DB2_TEX_LO       0.45
#define DB2_TEX_SNR      0.4
#define DB2_TEX_LO_MIN   0.18
#define DB2_SNAP_REACH   0.375
#define DB2_CLIP_LO      0.97
#define DB2_CLIP_HI      0.995
#define DB2_CTHR_GAIN    2.11
#define DB2_SNAP_MAD_LO_C  1.2
#define DB2_SNAP_MAD_HI_C  2.0
// v2.2 grid-evidence discount gains. Blocking contaminates each protection
// field by a KNOWN bounded multiple of the boundary excess (med-bias reads
// ~step/2 but the structure field carries a 2.5x coherence term; the block
// MAD ~step/2 doubles under its +-2 max-dilation; the fit residual carries
// ~3/4 of the sawtooth rms). The excess itself is clamped to db_thr where
// it is read (audit F1), so with that cap each knee shifts by AT MOST what
// measured blocking can account for. DB2_GRID_AUTH extends snap reach by
// ~the measured step (excess ~= step at the boundary column), capped at
// db_thr extra codes.
#define DB2_GRID_DTEX  1.25
#define DB2_GRID_DMAD  1.0
#define DB2_GRID_DRES  0.75
#define DB2_GRID_AUTH  2.0
// v3.1 floor-adaptive snap knee shift (codes): precomputed in the
// Noise Floor pass from the flat-field dither level (hinge 0.35 /
// gain 3.5 / cap 1.8 live THERE — single source), consumed here from
// FLOORTEX .z scaled by db_fsnap. Below the hinge the shift is
// EXACTLY +0.0 (Judas-class encodes, quiet 10-bit BDs — the v2
// tower's tuning sources keep today's knees); the cap bounds what a
// film-grain-class floor can license. Luma knees only.
// v3 structural escape (the CelFlare SPATIAL_PUMP_ADDITIVE pattern): 0
// compiles the reconstructor blend OUT and this pass is character-
// identical to v2.2 -> BIT-exact (the LP passes still run, unused).
// Rationale: db_curve = 0 at runtime is 1-ULP-equivalent, not bit-exact
// — every algebraic form of the source blend (mix / additive delta /
// convex / fused / uniform branch) perturbs Metal's scheduling of the
// luma scale-blend chain by 1 ulp on ~1 px/frame (measured; the chroma
// blend alone is clean). That is 1/257 of a code — invisible, fine for
// live A/B — but framemd5-grade regression proofs must flip THIS define
// (mkvariant -D DB2_CURVE_PATH=0), not the knob.
#define DB2_CURVE_PATH 1

vec4 hook() {
    vec4 orig = HOOKED_tex(HOOKED_pos);

    vec4 M = DB2_MED_tex(DB2_MED_pos);

    // 5-tap tent over the range/noise fields (v1.11 verbatim).
    vec2 rpt = DB2_RANGE_pt;
    vec4 mm  = DB2_RANGE_tex(DB2_RANGE_pos);
    vec4 mmL = DB2_RANGE_tex(DB2_RANGE_pos - vec2(2.0 * rpt.x, 0.0));
    vec4 mmR = DB2_RANGE_tex(DB2_RANGE_pos + vec2(2.0 * rpt.x, 0.0));
    vec4 mmU = DB2_RANGE_tex(DB2_RANGE_pos - vec2(0.0, 2.0 * rpt.y));
    vec4 mmD = DB2_RANGE_tex(DB2_RANGE_pos + vec2(0.0, 2.0 * rpt.y));
    float range_soft = mm.x * 0.4 + (mmL.x + mmR.x + mmU.x + mmD.x) * 0.15;
    float mad1 = max(max(DB2_RANGE_tex(DB2_RANGE_pos - vec2(rpt.x, 0.0)).z,
                         DB2_RANGE_tex(DB2_RANGE_pos + vec2(rpt.x, 0.0)).z),
                     max(DB2_RANGE_tex(DB2_RANGE_pos - vec2(0.0, rpt.y)).z,
                         DB2_RANGE_tex(DB2_RANGE_pos + vec2(0.0, rpt.y)).z));
    float mad_codes = max(max(mm.z, mad1),
                          max(max(mmL.z, mmR.z), max(mmU.z, mmD.z))) / DB2_CODE;
    float tex_codes = (mm.w + mmL.w + mmR.w + mmU.w + mmD.w) * 0.2 / DB2_CODE;

    // Chroma range/noise fields, 13-tap MAD max (v1.11 verbatim).
    vec2 cpt = DB2_RANGEC_pt;
    vec4 cc  = DB2_RANGEC_tex(DB2_RANGEC_pos);
    vec4 ccL = DB2_RANGEC_tex(DB2_RANGEC_pos - vec2(2.0 * cpt.x, 0.0));
    vec4 ccR = DB2_RANGEC_tex(DB2_RANGEC_pos + vec2(2.0 * cpt.x, 0.0));
    vec4 ccU = DB2_RANGEC_tex(DB2_RANGEC_pos - vec2(0.0, 2.0 * cpt.y));
    vec4 ccD = DB2_RANGEC_tex(DB2_RANGEC_pos + vec2(0.0, 2.0 * cpt.y));
    vec2 rangec_soft = cc.xy * 0.4
                     + (ccL.xy + ccR.xy + ccU.xy + ccD.xy) * 0.15;
    float madc1 = max(max(DB2_RANGEC_tex(DB2_RANGEC_pos - vec2(cpt.x, 0.0)).z,
                          DB2_RANGEC_tex(DB2_RANGEC_pos + vec2(cpt.x, 0.0)).z),
                      max(DB2_RANGEC_tex(DB2_RANGEC_pos - vec2(0.0, cpt.y)).z,
                          DB2_RANGEC_tex(DB2_RANGEC_pos + vec2(0.0, cpt.y)).z));
    float madc2 = max(max(DB2_RANGEC_tex(DB2_RANGEC_pos + vec2( cpt.x,  cpt.y)).z,
                          DB2_RANGEC_tex(DB2_RANGEC_pos + vec2( cpt.x, -cpt.y)).z),
                      max(DB2_RANGEC_tex(DB2_RANGEC_pos + vec2(-cpt.x,  cpt.y)).z,
                          DB2_RANGEC_tex(DB2_RANGEC_pos + vec2(-cpt.x, -cpt.y)).z));
    float madc_codes = max(max(cc.z, max(madc1, madc2)),
                           max(max(ccL.z, ccR.z), max(ccU.z, ccD.z))) / DB2_CODE;
    float texc_codes = (cc.w + ccL.w + ccR.w + ccU.w + ccD.w) * 0.2 / DB2_CODE;

    // The two plane-fit corrections, precomputed at 1/4 res.
    vec3 c  = DB2_CORR_tex(DB2_CORR_pos).rgb;
    vec3 cs = DB2_SCORR_tex(DB2_SCORR_pos).rgb;

    // (v2-a) Per-scale validities: envelope gate x pointwise residual gate
    // (wide scale only — the short scale's envelope covers its whole
    // contamination reach). Residual knees mirror the envelope knees:
    // half a step is legitimate staircase residual, a full step is not.
    float cthr = db_thr * DB2_CTHR_GAIN;
    vec4 env_codes = DB2_ENV_tex(DB2_ENV_pos) / DB2_CODE;
    vec4 an_t = DB2_ANISO_tex(DB2_ANISO_pos);
    vec2 res_codes = vec2(mm.y, an_t.w) / DB2_CODE;
    // v2.2 grid evidence (bilinear — smooth fields). y = confidence,
    // z = confidence-weighted boundary excess; both already db_block-
    // weighted upstream, so db_block = 0 reduces every discount below to
    // exactly zero and this build is bit-exact v2.1.
    // Discounts enter as KNEE SHIFTS (smoothstep is shift-invariant, so
    // knee+d == field-d) — a field-side subtraction invites the compiler
    // to FMA-contract into the field's own /DB2_CODE scaling and breaks
    // the db_block = 0 bit-exactness by 1 ulp (measured, Metal).
    // The excess arrives CLAMPED to db_thr from the Tensor V store (audit
    // F1: blocking never measures past ~db_thr codes of step; anything
    // beyond is phase-aligned real structure inflating the window mean).
    // The clamp lives UPSTREAM on purpose — a min() in this expression
    // re-fuses the division and costs the db_block = 0 bit-exactness
    // (measured, same class as the knee-shift note above).
    vec3 an_g = an_t.xyz;
    float g_exc_c = an_g.z / DB2_CODE;
    float d_res = DB2_GRID_DRES * g_exc_c;
    float v_Ly = (1.0 - smoothstep(0.5 * db_thr, db_thr, env_codes.x))
               * (1.0 - smoothstep(0.5 * db_thr + d_res, db_thr + d_res,
                                   res_codes.x));
    float v_Sy =  1.0 - smoothstep(0.5 * db_thr, db_thr, env_codes.z);
    float v_Lc = (1.0 - smoothstep(0.5 * cthr, cthr, env_codes.y))
               * (1.0 - smoothstep(0.5 * cthr, cthr, res_codes.y));
    float v_Sc =  1.0 - smoothstep(0.5 * cthr, cthr, env_codes.w);

    const vec3 W709 = vec3(0.2126, 0.7152, 0.0722);
    float cy_L = dot(c,  W709);
    float cy_S = dot(cs, W709);
    vec3 cop_L = c  - vec3(cy_L);
    vec3 cop_S = cs - vec3(cy_S);

    // (v2-b) Validity-normalized convex blend. A condemned plane fit can
    // carry |c| ~ 10 codes near a bulk edge, so at v_L = 0 the estimate
    // must be PURELY the other scale — a linear mix's leftover fraction
    // paints measurable garbage (1.1 codes, colored-square synthetic).
    // At zero total validity the blend resolves to 0 and the gate below
    // kills the apply. db_ms = 0 degenerates to the wide fit alone.
    float wLy = v_Ly;
    float wSy = (1.0 - v_Ly) * v_Sy * db_ms;
    float dy_ = max(wLy + wSy, 1e-6);
    float cy_eff  = (wLy * cy_L + wSy * cy_S) / dy_;
    float w_env   = (wLy * v_Ly + wSy * v_Sy) / dy_;
    float wLc = v_Lc;
    float wSc = (1.0 - v_Lc) * v_Sc * db_ms;
    float dc_ = max(wLc + wSc, 1e-6);
    vec3 cop_eff  = (wLc * cop_L + wSc * cop_S) / dc_;
    float w_env_c = (wLc * v_Lc + wSc * v_Sc) / dc_;

    // (v3) Correction-SOURCE blend: fit -> bounded lowpass, weight =
    // db_curve x the LP's own alpha (trusted mass x support symmetry).
    // NO fit-side validity enters the blend — measured on the t=1312
    // rings, BOTH candidates suppress the reconstructor exactly where it
    // is needed: the fit residual carries the staircase+curvature the
    // reconstructor exists for (residual-shaped share: 11% ripple
    // recovery vs 38% ceiling), and the envelope is seeded by the fit's
    // own |c|, which is honestly 1-2 codes on curved banded fields
    // (env-shaped share measured 0.63 mean / 0 at p10 on the rings).
    // What bounds the LP path instead (re-audit): the LP is a weighted
    // AVERAGE of in-window M — bounded by the window's own min/max, it
    // cannot manufacture an extremum (gradation-safe by construction) —
    // and the AMPLITUDE gate is the honest ceiling: everything the LP
    // does is clamped to sub-db_thr size. Smooth 6-12-code edges DO sit
    // partially inside active windows (the flatness gate only fades
    // there — and raising db_flat admits steeper ones); the fitmask
    // rejects the sharp ones and w_amp eats the high-curvature knees,
    // worst measured class ~1 code of extra softening on soft-glow
    // S-edges. One-sided/starved support is the alpha's job (audit
    // F1/F2 — the blend hands back to the fit, which is exact there).
    // Known trade (re-audit F1): with w_env bypassed below, smooth
    // low-amplitude REAL mid-frequency modulation (soft lighting,
    // gentle undulation) loses the fit-residual protection it had in
    // v2.2 — the LP pulls it toward the local mean, bounded by db_thr,
    // direction always smoothing. On clean high-bit sources lower
    // db_curve; the principled follow-up is a POSITIVE quantization-
    // evidence term in b_rc (staircase signature, fit-independent).
    // Every gate below and the snap target read the BLENDED value.
    // db_curve = 0 is 1-ulp-equivalent to v2.2 (<= 1 px/frame, measured);
    // the BIT-exact fallback is DB2_CURVE_PATH 0 above.
#if DB2_CURVE_PATH == 1
    vec4 lpc = DB2_LPC_tex(DB2_LPC_pos);
    vec3 c_rc = lpc.rgb;
    float cy_R = dot(c_rc, W709);
    vec3 cop_R = c_rc - vec3(cy_R);
    float b_rc = db_curve * lpc.a;
    cy_eff  = mix(cy_eff,  cy_R,  b_rc);
    cop_eff = mix(cop_eff, cop_R, b_rc);
    // The env/residual VALIDITY weight (w_env below) certifies the FIT —
    // weighting an LP-sourced correction by the fit's health is the same
    // anti-correlation as above at the apply stage (measured: it carves
    // the applied correction exactly at the contours, w vs |mid-band|
    // corr -0.29, and returns the whole reconstructor to v2.2 output).
    // Bypass it in proportion to the LP share; where alpha hands back to
    // the fit, the fit's own condemnation machinery returns with it.
    w_env   = mix(w_env,   1.0, b_rc);
    w_env_c = mix(w_env_c, 1.0, b_rc);
#endif
    vec3 c_eff    = vec3(cy_eff) + cop_eff;

    // Gate 1: correction amplitude (v1.11 verbatim).
    float c_codes = max(max(abs(c_eff.r), abs(c_eff.g)), abs(c_eff.b)) / DB2_CODE;
    float w_amp = 1.0 - smoothstep(0.5 * db_thr, db_thr, c_codes);

    // Gate 2: local flatness (v1.11 verbatim).
    float range_codes = range_soft / DB2_CODE;
    float w_flat = 1.0 - smoothstep(db_flat, 2.0 * db_flat, range_codes);

    // Gate 3: structure, SNR-adaptive knee (v1.11 verbatim except the
    // v2.2 grid knee shift — see the d_res note).
    float d_tex = DB2_GRID_DTEX * g_exc_c;
    float tex_lo = mix(DB2_TEX_LO,
                       clamp(DB2_TEX_SNR * mad_codes, DB2_TEX_LO_MIN, DB2_TEX_LO),
                       db_snr);
    float w_tex = 1.0 - smoothstep(tex_lo + d_tex, 2.0 * tex_lo + d_tex,
                                   tex_codes);

    float w = w_amp * w_flat * w_tex * w_env * db_strength;

    // Chroma path (v1.9/v1.11 verbatim except the v2 envelope/blend above).
    vec3 c_op = cop_eff;
    vec3 c_lp = vec3(cy_eff) + c_op * (1.0 - db_chroma);

    float cop_codes = max(max(abs(c_op.r), abs(c_op.g)), abs(c_op.b)) / DB2_CODE;
    float w_amp_c = 1.0 - smoothstep(0.5 * cthr, cthr, cop_codes);
    float rangec_codes = max(rangec_soft.x, rangec_soft.y) / DB2_CODE;
    float w_flat_c = 1.0 - smoothstep(db_flat_c, 2.0 * db_flat_c, rangec_codes);
    float w_texc = 1.0 - smoothstep(DB2_TEX_LO, 2.0 * DB2_TEX_LO, texc_codes);

    float w_c = w_amp_c * w_flat_c * w_tex * w_texc * w_env_c
              * db_strength * db_chroma;

    // Regime blend (v1.11 verbatim) + (v2-c) the anisotropy flag: mottle
    // that is significant against the FRAME noise floor and 2-D closes
    // the snap — the texture rides through; the LF correction stays.
    float lum = dot(M.rgb, vec3(0.2126, 0.7152, 0.0722));
    float dark_w = 1.0 - smoothstep(DB2_DARK_LO, DB2_DARK_HI, lum);
    float mad_lo = mix(DB2_SNAP_MAD_LO, DB2_SNAP_MAD_LO_D, dark_w);
    float mad_hi = mix(DB2_SNAP_MAD_HI, DB2_SNAP_MAD_HI_D, dark_w);
    // v2.2: grid-locked energy is exempted from the anisotropy flag (the
    // one 2-D-texture class that is provably non-signal), the block-MAD
    // knee sees the MAD minus the blocking's own contribution, and the
    // snap reach extends by ~the measured step so mosaic steps past the
    // static authority still land on the plane fit. Luma path only.
    float aniso = an_g.x * db_aniso * (1.0 - an_g.y);
    float d_mad = DB2_GRID_DMAD * g_exc_c;
    // v3.1: knee-shift form (smoothstep is shift-invariant — same
    // fp16/FMA rationale as d_mad above; db_fsnap = 0 or a below-hinge
    // source makes d_fl exactly +0.0).
    float d_fl = db_fsnap * DB2_FLOORTEX_tex(vec2(0.5)).z;
    float snap = (1.0 - smoothstep(mad_lo + d_mad + d_fl,
                                   mad_hi + d_mad + d_fl, mad_codes))
               * (1.0 - aniso) * db_dither;
    float snap_c = (1.0 - smoothstep(DB2_SNAP_MAD_LO_C, DB2_SNAP_MAD_HI_C,
                                     madc_codes)) * db_dither;

    float auth   = (DB2_SNAP_REACH * db_thr
                    + (mad_codes + min(DB2_GRID_AUTH * g_exc_c, db_thr)) * snap)
                 * DB2_CODE;
    float auth_c = (DB2_SNAP_REACH * cthr + madc_codes * snap_c) * DB2_CODE;
    // Snap target reconstructed as c_eff + M (v1.11 verbatim): the stored
    // M's quantization cancels between this M and the -M inside each c.
    vec3 t = c_eff + M.rgb - orig.rgb;
    vec3 t_y  = vec3(dot(t, vec3(0.2126, 0.7152, 0.0722)));
    vec3 t_op = t - t_y;
    vec3 t_lp = t_y + t_op * (1.0 - db_chroma);
    vec3 r_lp = clamp(t_lp, vec3(-auth),   vec3(auth));
    vec3 r_op = clamp(t_op, vec3(-auth_c), vec3(auth_c));
    vec3 corr_l = mix(c_lp, r_lp, snap) * w;
    vec3 corr_c = mix(c_op, r_op, snap_c) * w_c;

    // Clip guard: LUMA-PATH TERM ONLY (v1.9 audit F1, verbatim).
    float clip_w = smoothstep(DB2_CLIP_LO, DB2_CLIP_HI, lum);
    corr_l = mix(corr_l, max(corr_l, vec3(0.0)), clip_w);
    vec3 corr = corr_l + corr_c;

#if db_debug == 1
    return orig;
#elif db_debug == 2
    return vec4(vec3(0.5) + corr * 32.0, 1.0);
#elif db_debug == 3
    return vec4(1.0 - w_flat, w, snap, 1.0);
#elif db_debug == 4
    return vec4(1.0 - w_flat_c, w_c, snap_c, 1.0);
#elif db_debug == 5
    return vec4(res_codes.x * 0.25, res_codes.y * 0.125, aniso, 1.0);
#elif db_debug == 6
    return vec4(an_g.y, min(g_exc_c * 8.0, 1.0), snap, 1.0);
#elif db_debug == 7
#if DB2_CURVE_PATH == 1
    return vec4(b_rc, min(abs(cy_R) / DB2_CODE * 0.25, 1.0), lpc.a, 1.0);
#else
    return vec4(0.0, 0.0, 0.0, 1.0);
#endif
#else
    return vec4(orig.rgb + corr, orig.a);
#endif
}
