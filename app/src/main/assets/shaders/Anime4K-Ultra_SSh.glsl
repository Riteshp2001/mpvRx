// =============================================================================
// Shader: Anime4K-Ultra
// Compiled by: Th-Underscore (2026)
//
// FEATURES:
// - Combines FSR v1.0.2/Anime4K_Upscale_CNN and Custom Anime4K_Thin w/ De-Aliasing + Deblur
// - Designed specifically to preserve film grain and fine textures
// - Significantly lighter performance than Anime4K (Fast)
//
// CREDITS:
//  - bloc97, for creating Anime4K
//  - agilyd, for porting FSR to GLSL
// =============================================================================
// THIRD-PARTY LICENSES:
//
// 1. Anime4K (MIT) - Copyright (c) 2019-2021 bloc97
// 2. FidelityFX FSR (MIT) - Copyright (c) 2021 Advanced Micro Devices, Inc.
// 3. Anime4K-Ultra (MIT) - Copyright (c) 2026 Th-Underscore
// =============================================================================
// MIT License
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.
// =============================================================================


// =============================================================================
// COMPONENT: Save-Native.glsl
// =============================================================================

//!DESC Save-Native-Resolution
//!HOOK MAIN
//!BIND HOOKED
//!SAVE NATIVE_RES
//!COMPONENTS 1
// Hook MAIN (always available) instead of LUMA so this runs under both
// hwdec=mediacodec (direct RGB surface, no LUMA plane) and mediacodec-copy.
// MAIN is RGB; derive native-resolution luma with Rec.601 coefficients.
// SAVE target (NATIVE_RES) differs from MAIN, so MAIN passes through untouched.
vec4 hook() {
    return vec4(dot(vec3(0.299, 0.587, 0.114), HOOKED_tex(HOOKED_pos).rgb), 0.0, 0.0, 0.0);
}


// =============================================================================
// COMPONENT: FSR-Ani.glsl
// =============================================================================

// Derive a native-resolution luma texture from MAIN (RGB) so FSR can run under
// hwdec=mediacodec (direct, no LUMA plane) as well as mediacodec-copy.
//!HOOK MAIN
//!BIND HOOKED
//!SAVE LUMATEX
//!DESC FSR luma source (Rec.601 luma of MAIN)
//!COMPONENTS 1
vec4 hook() {
    return vec4(dot(vec3(0.299, 0.587, 0.114), HOOKED_tex(HOOKED_pos).rgb), 0.0, 0.0, 0.0);
}

//!HOOK MAIN
//!BIND LUMATEX
//!SAVE EASUTEX
//!DESC FidelityFX Super Resolution v1.0.2 (EASU)
//!WHEN OUTPUT.w OUTPUT.h * MAIN.w MAIN.h * / 1.0 >
//!WIDTH OUTPUT.w OUTPUT.w MAIN.w 2 * < * MAIN.w 2 * OUTPUT.w MAIN.w 2 * > * + OUTPUT.w OUTPUT.w MAIN.w 2 * = * +
//!HEIGHT OUTPUT.h OUTPUT.h MAIN.h 2 * < * MAIN.h 2 * OUTPUT.h MAIN.h 2 * > * + OUTPUT.h OUTPUT.h MAIN.h 2 * = * +
//!COMPONENTS 1

// User variables - EASU
#define FSR_PQ 0 // Whether the source content has PQ gamma or not. Needs to be set to the same value for both passes. 0 or 1.
#define FSR_EASU_DERING 1 // If set to 0, disables deringing for a small increase in performance. 0 or 1.
#define FSR_EASU_SIMPLE_ANALYSIS 0 // If set to 1, uses a simpler single-pass direction and length analysis for an increase in performance. 0 or 1.
#define FSR_EASU_QUIT_EARLY 0 // If set to 1, uses bilinear filtering for non-edge pixels and skips EASU on those regions for an increase in performance. 0 or 1.

// Shader code

#ifndef FSR_EASU_DIR_THRESHOLD
	#if (FSR_EASU_QUIT_EARLY == 1)
		#define FSR_EASU_DIR_THRESHOLD 64.0
	#elif (FSR_EASU_QUIT_EARLY == 0)
		#define FSR_EASU_DIR_THRESHOLD 32768.0
	#endif
#endif

float APrxLoRcpF1(float a) {
	return uintBitsToFloat(uint(0x7ef07ebb) - floatBitsToUint(a));
}

float APrxLoRsqF1(float a) {
	return uintBitsToFloat(uint(0x5f347d74) - (floatBitsToUint(a) >> uint(1)));
}

float AMin3F1(float x, float y, float z) {
	return min(x, min(y, z));
}

float AMax3F1(float x, float y, float z) {
	return max(x, max(y, z));
}

#if (FSR_PQ == 1)

float ToGamma2(float a) { 
	return pow(a, 4.0);
}

#endif

 // Filtering for a given tap for the scalar.
 void FsrEasuTap(
	inout float aC,	// Accumulated color, with negative lobe.
	inout float aW, // Accumulated weight.
	vec2 off,       // Pixel offset from resolve position to tap.
	vec2 dir,       // Gradient direction.
	vec2 len,       // Length.
	float lob,      // Negative lobe strength.
	float clp,		// Clipping point.
	float c){		// Tap color.
	// Rotate offset by direction.
	vec2 v;
	v.x = (off.x * ( dir.x)) + (off.y * dir.y);
	v.y = (off.x * (-dir.y)) + (off.y * dir.x);
	// Anisotropy.
	v *= len;
	// Compute distance^2.
	float d2 = v.x * v.x + v.y * v.y;
	// Limit to the window as at corner, 2 taps can easily be outside.
	d2 = min(d2, clp);
	// Approximation of lancos2 without sin() or rcp(), or sqrt() to get x.
	//  (25/16 * (2/5 * x^2 - 1)^2 - (25/16 - 1)) * (1/4 * x^2 - 1)^2
	//  |_______________________________________|   |_______________|
	//                   base                             window
	// The general form of the 'base' is,
	//  (a*(b*x^2-1)^2-(a-1))
	// Where 'a=1/(2*b-b^2)' and 'b' moves around the negative lobe.
	float wB = float(2.0 / 5.0) * d2 + -1.0;
	float wA = lob * d2 + -1.0;
	wB *= wB;
	wA *= wA;
	wB = float(25.0 / 16.0) * wB + float(-(25.0 / 16.0 - 1.0));
	float w = wB * wA;
	// Do weighted average.
	aC += c * w;
	aW += w;
}

// Accumulate direction and length.
void FsrEasuSet(
	inout vec2 dir,
	inout float len,
	vec2 pp,
#if (FSR_EASU_SIMPLE_ANALYSIS == 1)
	float b, float c,
	float i, float j, float f, float e,
	float k, float l, float h, float g,
	float o, float n
#elif (FSR_EASU_SIMPLE_ANALYSIS == 0)
	bool biS, bool biT, bool biU, bool biV,
	float lA, float lB, float lC, float lD, float lE
#endif
	){
	// Compute bilinear weight, branches factor out as predicates are compiler time immediates.
	//  s t
	//  u v
#if (FSR_EASU_SIMPLE_ANALYSIS == 1)
	vec4 w = vec4(0.0);
	w.x = (1.0 - pp.x) * (1.0 - pp.y);
	w.y =        pp.x  * (1.0 - pp.y);
	w.z = (1.0 - pp.x) *        pp.y;
	w.w =        pp.x  *        pp.y;

	float lA = dot(w, vec4(b, c, f, g));
	float lB = dot(w, vec4(e, f, i, j));
	float lC = dot(w, vec4(f, g, j, k));
	float lD = dot(w, vec4(g, h, k, l));
	float lE = dot(w, vec4(j, k, n, o));
#elif (FSR_EASU_SIMPLE_ANALYSIS == 0)
	float w = 0.0;
	if (biS)
		w = (1.0 - pp.x) * (1.0 - pp.y);
	if (biT)
		w =        pp.x  * (1.0 - pp.y);
	if (biU)
		w = (1.0 - pp.x) *        pp.y;
	if (biV)
		w =        pp.x  *        pp.y;
#endif
	// Direction is the '+' diff.
	//    a
	//  b c d
	//    e
	// Then takes magnitude from abs average of both sides of 'c'.
	// Length converts gradient reversal to 0, smoothly to non-reversal at 1, shaped, then adding horz and vert terms.
	float dc = lD - lC;
	float cb = lC - lB;
	float lenX = max(abs(dc), abs(cb));
	lenX = APrxLoRcpF1(lenX);
	float dirX = lD - lB;
	lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);
	lenX *= lenX;
	// Repeat for the y axis.
	float ec = lE - lC;
	float ca = lC - lA;
	float lenY = max(abs(ec), abs(ca));
	lenY = APrxLoRcpF1(lenY);
	float dirY = lE - lA;
	lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);
	lenY *= lenY;
#if (FSR_EASU_SIMPLE_ANALYSIS == 1)
	len = lenX + lenY;
	dir = vec2(dirX, dirY);
#elif (FSR_EASU_SIMPLE_ANALYSIS == 0)
	dir += vec2(dirX, dirY) * w;
	len += dot(vec2(w), vec2(lenX, lenY));
#endif
}

vec4 hook() {
	// Result
	vec4 pix = vec4(0.0, 0.0, 0.0, 1.0);

	//------------------------------------------------------------------------------------------------------------------------------
	//      +---+---+
	//      |   |   |
	//      +--(0)--+
	//      | b | c |
	//  +---F---+---+---+
	//  | e | f | g | h |
	//  +--(1)--+--(2)--+
	//  | i | j | k | l |
	//  +---+---+---+---+
	//      | n | o |
	//      +--(3)--+
	//      |   |   |
	//      +---+---+
	// Get position of 'F'.
	vec2 pp = LUMATEX_pos * LUMATEX_size - vec2(0.5);
	vec2 fp = floor(pp);
	pp -= fp;
	//------------------------------------------------------------------------------------------------------------------------------
	// 12-tap kernel.
	//    b c
	//  e f g h
	//  i j k l
	//    n o
	// Gather 4 ordering.
	//  a b
	//  r g
	// Allowing dead-code removal to remove the 'z's.
#if (defined(LUMATEX_gather) && (__VERSION__ >= 400 || (GL_ES && __VERSION__ >= 310)))
	vec4 bczzL = LUMATEX_gather(vec2((fp + vec2(1.0, -1.0)) * LUMATEX_pt), 0);
	vec4 ijfeL = LUMATEX_gather(vec2((fp + vec2(0.0,  1.0)) * LUMATEX_pt), 0);
	vec4 klhgL = LUMATEX_gather(vec2((fp + vec2(2.0,  1.0)) * LUMATEX_pt), 0);
	vec4 zzonL = LUMATEX_gather(vec2((fp + vec2(1.0,  3.0)) * LUMATEX_pt), 0);
#else
	// pre-OpenGL 4.0 compatibility
	float b = LUMATEX_tex(vec2((fp + vec2(0.5, -0.5)) * LUMATEX_pt)).r;
	float c = LUMATEX_tex(vec2((fp + vec2(1.5, -0.5)) * LUMATEX_pt)).r;

	float e = LUMATEX_tex(vec2((fp + vec2(-0.5, 0.5)) * LUMATEX_pt)).r;
	float f = LUMATEX_tex(vec2((fp + vec2( 0.5, 0.5)) * LUMATEX_pt)).r;
	float g = LUMATEX_tex(vec2((fp + vec2( 1.5, 0.5)) * LUMATEX_pt)).r;
	float h = LUMATEX_tex(vec2((fp + vec2( 2.5, 0.5)) * LUMATEX_pt)).r;

	float i = LUMATEX_tex(vec2((fp + vec2(-0.5, 1.5)) * LUMATEX_pt)).r;
	float j = LUMATEX_tex(vec2((fp + vec2( 0.5, 1.5)) * LUMATEX_pt)).r;
	float k = LUMATEX_tex(vec2((fp + vec2( 1.5, 1.5)) * LUMATEX_pt)).r;
	float l = LUMATEX_tex(vec2((fp + vec2( 2.5, 1.5)) * LUMATEX_pt)).r;

	float n = LUMATEX_tex(vec2((fp + vec2(0.5, 2.5) ) * LUMATEX_pt)).r;
	float o = LUMATEX_tex(vec2((fp + vec2(1.5, 2.5) ) * LUMATEX_pt)).r;

	vec4 bczzL = vec4(b, c, 0.0, 0.0);
	vec4 ijfeL = vec4(i, j, f, e);
	vec4 klhgL = vec4(k, l, h, g);
	vec4 zzonL = vec4(0.0, 0.0, o, n);
#endif
	//------------------------------------------------------------------------------------------------------------------------------
	// Rename.
	float bL = bczzL.x;
	float cL = bczzL.y;
	float iL = ijfeL.x;
	float jL = ijfeL.y;
	float fL = ijfeL.z;
	float eL = ijfeL.w;
	float kL = klhgL.x;
	float lL = klhgL.y;
	float hL = klhgL.z;
	float gL = klhgL.w;
	float oL = zzonL.z;
	float nL = zzonL.w;

#if (FSR_PQ == 1)
	// Not the most performance-friendly solution, but should work until mpv adds proper gamma transformation functions for shaders
	bL = ToGamma2(bL);
	cL = ToGamma2(cL);
	iL = ToGamma2(iL);
	jL = ToGamma2(jL);
	fL = ToGamma2(fL);
	eL = ToGamma2(eL);
	kL = ToGamma2(kL);
	lL = ToGamma2(lL);
	hL = ToGamma2(hL);
	gL = ToGamma2(gL);
	oL = ToGamma2(oL);
	nL = ToGamma2(nL);
#endif

	// Accumulate for bilinear interpolation.
	vec2 dir = vec2(0.0);
	float len = 0.0;
#if (FSR_EASU_SIMPLE_ANALYSIS == 1)
	FsrEasuSet(dir, len, pp, bL, cL, iL, jL, fL, eL, kL, lL, hL, gL, oL, nL);
#elif (FSR_EASU_SIMPLE_ANALYSIS == 0)
	FsrEasuSet(dir, len, pp, true, false, false, false, bL, eL, fL, gL, jL);
	FsrEasuSet(dir, len, pp, false, true, false, false, cL, fL, gL, hL, kL);
	FsrEasuSet(dir, len, pp, false, false, true, false, fL, iL, jL, kL, nL);
	FsrEasuSet(dir, len, pp, false, false, false, true, gL, jL, kL, lL, oL);
#endif
	//------------------------------------------------------------------------------------------------------------------------------
	// Normalize with approximation, and cleanup close to zero.
	vec2 dir2 = dir * dir;
	float dirR = dir2.x + dir2.y;
	bool zro = dirR < float(1.0 / FSR_EASU_DIR_THRESHOLD);
	dirR = APrxLoRsqF1(dirR);
#if (FSR_EASU_QUIT_EARLY == 1)
	if (zro) {
		vec4 w = vec4(0.0);
		w.x = (1.0 - pp.x) * (1.0 - pp.y);
		w.y =        pp.x  * (1.0 - pp.y);
		w.z = (1.0 - pp.x) *        pp.y;
		w.w =        pp.x  *        pp.y;

		pix.r = clamp(dot(w, vec4(fL, gL, jL, kL)), 0.0, 1.0);
		return pix;
	}
#elif (FSR_EASU_QUIT_EARLY == 0)
	dirR = zro ? 1.0 : dirR;
	dir.x = zro ? 1.0 : dir.x;
#endif
	dir *= vec2(dirR);
	// Transform from {0 to 2} to {0 to 1} range, and shape with square.
	len = len * 0.5;
	len *= len;
	// Stretch kernel {1.0 vert|horz, to sqrt(2.0) on diagonal}.
	float stretch = (dir.x * dir.x + dir.y * dir.y) * APrxLoRcpF1(max(abs(dir.x), abs(dir.y)));
	// Anisotropic length after rotation,
	//  x := 1.0 lerp to 'stretch' on edges
	//  y := 1.0 lerp to 2x on edges
	vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 + -0.5 * len);
	// Based on the amount of 'edge',
	// the window shifts from +/-{sqrt(2.0) to slightly beyond 2.0}.
	float lob = 0.5 + float((1.0 / 4.0 - 0.04) - 0.5) * len;
	// Set distance^2 clipping point to the end of the adjustable window.
	float clp = APrxLoRcpF1(lob);
	//------------------------------------------------------------------------------------------------------------------------------
	// Accumulation
	//    b c
	//  e f g h
	//  i j k l
	//    n o
	float aC = 0.0;
	float aW = 0.0;
	FsrEasuTap(aC, aW, vec2( 0.0,-1.0) - pp, dir, len2, lob, clp, bL); // b
	FsrEasuTap(aC, aW, vec2( 1.0,-1.0) - pp, dir, len2, lob, clp, cL); // c
	FsrEasuTap(aC, aW, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, iL); // i
	FsrEasuTap(aC, aW, vec2( 0.0, 1.0) - pp, dir, len2, lob, clp, jL); // j
	FsrEasuTap(aC, aW, vec2( 0.0, 0.0) - pp, dir, len2, lob, clp, fL); // f
	FsrEasuTap(aC, aW, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, eL); // e
	FsrEasuTap(aC, aW, vec2( 1.0, 1.0) - pp, dir, len2, lob, clp, kL); // k
	FsrEasuTap(aC, aW, vec2( 2.0, 1.0) - pp, dir, len2, lob, clp, lL); // l
	FsrEasuTap(aC, aW, vec2( 2.0, 0.0) - pp, dir, len2, lob, clp, hL); // h
	FsrEasuTap(aC, aW, vec2( 1.0, 0.0) - pp, dir, len2, lob, clp, gL); // g
	FsrEasuTap(aC, aW, vec2( 1.0, 2.0) - pp, dir, len2, lob, clp, oL); // o
	FsrEasuTap(aC, aW, vec2( 0.0, 2.0) - pp, dir, len2, lob, clp, nL); // n
	//------------------------------------------------------------------------------------------------------------------------------
	// Normalize and dering.
	pix.r = aC / aW;
#if (FSR_EASU_DERING == 1)
	float min1 = min(AMin3F1(fL, gL, jL), kL);
	float max1 = max(AMax3F1(fL, gL, jL), kL);
	pix.r = clamp(pix.r, min1, max1);
#endif
	pix.r = clamp(pix.r, 0.0, 1.0);

	return pix;
}

//!HOOK MAIN
//!BIND HOOKED
//!BIND EASUTEX
//!SAVE MAIN
//!DESC FidelityFX Super Resolution v1.0.2 (RCAS)
//!WIDTH EASUTEX.w
//!HEIGHT EASUTEX.h
//!COMPONENTS 4

// User variables - RCAS
#define SHARPNESS 0.8 // Controls the amount of sharpening. The scale is {0.0 := maximum, to N>0, where N is the number of stops (halving) of the reduction of sharpness}. 0.0 to 2.0.
#define FSR_RCAS_DENOISE 1 // If set to 1, lessens the sharpening on noisy areas. Can be disabled for better performance. 0 or 1.
#define FSR_PQ 0 // Whether the source content has PQ gamma or not. Needs to be set to the same value for both passes. 0 or 1.

// Shader code

#define FSR_RCAS_LIMIT (0.25 - (1.0 / 16.0)) // This is set at the limit of providing unnatural results for sharpening.

float APrxMedRcpF1(float a) {
	float b = uintBitsToFloat(uint(0x7ef19fff) - floatBitsToUint(a));
	return b * (-b * a + 2.0);
}

float AMax3F1(float x, float y, float z) {
	return max(x, max(y, z)); 
}

float AMin3F1(float x, float y, float z) {
	return min(x, min(y, z));
}

#if (FSR_PQ == 1)

float FromGamma2(float a) { 
	return sqrt(sqrt(a));
}

#endif

vec4 hook() {
	// Algorithm uses minimal 3x3 pixel neighborhood.
	//    b 
	//  d e f
	//    h
#if (defined(EASUTEX_gather) && (__VERSION__ >= 400 || (GL_ES && __VERSION__ >= 310)))
	vec3 bde = EASUTEX_gather(EASUTEX_pos + EASUTEX_pt * vec2(-0.5), 0).xyz;
	float b = bde.z;
	float d = bde.x;
	float e = bde.y;

	vec2 fh = EASUTEX_gather(EASUTEX_pos + EASUTEX_pt * vec2(0.5), 0).zx;
	float f = fh.x;
	float h = fh.y;
#else
	float b = EASUTEX_texOff(vec2( 0.0, -1.0)).r;
	float d = EASUTEX_texOff(vec2(-1.0,  0.0)).r;
	float e = EASUTEX_tex(EASUTEX_pos).r;
	float f = EASUTEX_texOff(vec2(1.0, 0.0)).r;
	float h = EASUTEX_texOff(vec2(0.0, 1.0)).r;
#endif

	// Min and max of ring.
	float mn1L = min(AMin3F1(b, d, f), h);
	float mx1L = max(AMax3F1(b, d, f), h);

	// Immediate constants for peak range.
	vec2 peakC = vec2(1.0, -1.0 * 4.0);

	// Limiters, these need to be high precision RCPs.
	float hitMinL = min(mn1L, e) / (4.0 * mx1L);
	float hitMaxL = (peakC.x - max(mx1L, e)) / (4.0 * mn1L + peakC.y);
	float lobeL = max(-hitMinL, hitMaxL);
	float lobe = max(float(-FSR_RCAS_LIMIT), min(lobeL, 0.0)) * exp2(-clamp(float(SHARPNESS), 0.0, 2.0));

	// Apply noise removal.
#if (FSR_RCAS_DENOISE == 1)
	// Noise detection.
	float nz = 0.25 * b + 0.25 * d + 0.25 * f + 0.25 * h - e;
	nz = clamp(abs(nz) * APrxMedRcpF1(AMax3F1(AMax3F1(b, d, e), f, h) - AMin3F1(AMin3F1(b, d, e), f, h)), 0.0, 1.0);
	nz = -0.5 * nz + 1.0;
	lobe *= nz;
#endif

	// Resolve, which needs the medium precision rcp approximation to avoid visible tonality changes.
	float rcpL = APrxMedRcpF1(4.0 * lobe + 1.0);
	vec4 pix = vec4(0.0, 0.0, 0.0, 1.0);
	pix.r = float((lobe * b + lobe * d + lobe * h + lobe * f + e) * rcpL);
#if (FSR_PQ == 1)
	pix.r = FromGamma2(pix.r);
#endif

	// Recombine the FSR-sharpened, upscaled luma (pix.r) back into the full RGB MAIN.
	// HOOKED (MAIN) sampled at the output position gives the bilinear-upscaled color;
	// we substitute its luma with the FSR luma, preserving chroma. This reproduces the
	// original luma-only-FSR + chroma-merge result without needing a separate plane.
	vec3 rgb = HOOKED_tex(HOOKED_pos).rgb;
	float lold = dot(vec3(0.299, 0.587, 0.114), rgb);
	return vec4(rgb + (pix.r - lold), 1.0);
}


// =============================================================================
// COMPONENT: Anime4K_Thin_AA_Soften+Sharpen.glsl
// =============================================================================

//!DESC Save-Native-Resolution
//!HOOK MAIN
//!BIND HOOKED
//!SAVE NATIVE_RES
//!WHEN OUTPUT.w MAIN.w >
//!COMPONENTS 1
// Runs after the FSR (RCAS) pass has written the upscaled image into MAIN, so
// HOOKED (MAIN) is the upscaled RGB here; its Rec.601 luma equals the FSR luma.
vec4 hook() {
    return vec4(dot(vec3(0.299, 0.587, 0.114), HOOKED_tex(HOOKED_pos).rgb), 0.0, 0.0, 0.0);
}

//!DESC Anime4K-Ultra-Thin-AA-SH-Luma-Sharp
//!HOOK MAIN
//!BIND NATIVE_RES
//!BIND HOOKED
//!SAVE LINELUMA_SHARP
//!COMPONENTS 1

#define D (0.75 * HOOKED_size.y / NATIVE_RES_size.y) // Dynamically scales sampling radius based on upscale factor
#define LUMA_SHARP_AMOUNT 1.5

float get_luma(vec4 rgba) { return dot(vec3(0.299, 0.587, 0.114), rgba.rgb); }

vec4 hook() {
    float c = get_luma(HOOKED_tex(HOOKED_pos));
    // Box blur (9-tap): used as the low-frequency reference for unsharp masking
    // Pre-sharpening luma before edge detection improves gradient signal quality on soft upscaled sources, making subsequent Sobel passes more responsive to true lines vs. interpolation blur
    float blur = (
        get_luma(HOOKED_texOff(vec2(-D,-D))) + get_luma(HOOKED_texOff(vec2(0.0,-D))) + get_luma(HOOKED_texOff(vec2(D,-D))) +
        get_luma(HOOKED_texOff(vec2(-D, 0.0))) + c + get_luma(HOOKED_texOff(vec2(D, 0.0))) +
        get_luma(HOOKED_texOff(vec2(-D, D))) + get_luma(HOOKED_texOff(vec2(0.0, D))) + get_luma(HOOKED_texOff(vec2(D, D)))
    ) / 9.0;
    // sharpened = c + (c - blur) * amount
    return vec4(clamp(c + (c - blur) * LUMA_SHARP_AMOUNT, 0.0, 1.0), 0.0, 0.0, 0.0);
}

//!DESC Anime4K-Ultra-Thin-AA-SH-Luma-Sharp
//!HOOK MAIN
//!BIND NATIVE_RES
//!BIND EASUTEX
//!SAVE LINELUMA_SHARP
//!WIDTH EASUTEX.w
//!HEIGHT EASUTEX.h
//!COMPONENTS 1
//!WHEN OUTPUT.w MAIN.w >

// Upscaling path: operates on the FSR-upscaled texture instead of HOOKED
#define D (0.75 * EASUTEX_size.y / NATIVE_RES_size.y)
#define LUMA_SHARP_AMOUNT 1.5

vec4 hook() {
    float c = EASUTEX_tex(EASUTEX_pos).x;
    float blur = (
        EASUTEX_texOff(vec2(-D,-D)).x + EASUTEX_texOff(vec2(0.0,-D)).x + EASUTEX_texOff(vec2(D,-D)).x +
        EASUTEX_texOff(vec2(-D, 0.0)).x + c + EASUTEX_texOff(vec2(D, 0.0)).x +
        EASUTEX_texOff(vec2(-D, D)).x + EASUTEX_texOff(vec2(0.0, D)).x + EASUTEX_texOff(vec2(D, D)).x
    ) / 9.0;
    return vec4(clamp(c + (c - blur) * LUMA_SHARP_AMOUNT, 0.0, 1.0), 0.0, 0.0, 0.0);
}

//!DESC Anime4K-Ultra-Thin-AA-SH-Sobel-X
//!HOOK MAIN
//!BIND NATIVE_RES
//!BIND LINELUMA_SHARP
//!SAVE LINESOBEL
//!WIDTH NATIVE_RES.w
//!HEIGHT NATIVE_RES.h
//!COMPONENTS 2

#define D (0.75 * LINELUMA_SHARP_size.y / NATIVE_RES_size.y)

vec4 hook() {
    float l = LINELUMA_SHARP_texOff(vec2(-D, 0.0)).x;
    float c = LINELUMA_SHARP_tex(LINELUMA_SHARP_pos).x;
    float r = LINELUMA_SHARP_texOff(vec2( D, 0.0)).x;
    // Horizontal pass of a separable Sobel operator. Stores partial row products so Sobel-Y can complete both axes without re-fetching this row:
    //   .x = gx_partial = -l + r       (horizontal difference)
    //   .y = row_sum    =  l + 2c + r  (row weight for gy completion)
    return vec4(-l + r, l + c + c + r, 0.0, 0.0);
}

//!DESC Anime4K-Ultra-Thin-AA-SH-Sobel-Y
//!HOOK MAIN
//!BIND NATIVE_RES
//!BIND LINESOBEL
//!SAVE LINESOBEL
//!WIDTH NATIVE_RES.w
//!HEIGHT NATIVE_RES.h
//!COMPONENTS 1

#define D (0.75 * LINESOBEL_size.y / NATIVE_RES_size.y)

vec4 hook() {
    float tx = LINESOBEL_texOff(vec2(0.0,-D)).x;
    float cx = LINESOBEL_tex(LINESOBEL_pos).x;
    float bx = LINESOBEL_texOff(vec2(0.0, D)).x;
    float ty = LINESOBEL_texOff(vec2(0.0,-D)).y;
    float by = LINESOBEL_texOff(vec2(0.0, D)).y;
    // Vertical pass completing the separable 3x3 Sobel:
    //   Gx kernel          Gy kernel
    //   -1  0  +1          -1  -2  -1
    //   -2  0  +2           0   0   0
    //   -1  0  +1          +1  +2  +1
    //
    //   gx = (top.y + 2·mid.y + bot.y) / 8  (completes column weighting)
    //   gy = (-top.x + bot.x) / 8           (vertical difference of row sums)
    float gx = (tx + cx + cx + bx) / 8.0;
    float gy = (-ty + by) / 8.0;
    // Magnitude raised to 0.7 to compress dynamic range: weaker edges get
    // a stronger mask presence without blowing out strong ones
    return vec4(pow(sqrt(gx * gx + gy * gy), 0.7));
}

//!DESC Anime4K-Ultra-Thin-AA-SH-Gaussian-X
//!HOOK MAIN
//!BIND NATIVE_RES
//!BIND LINESOBEL
//!SAVE LINESOBEL
//!WIDTH NATIVE_RES.w
//!HEIGHT NATIVE_RES.h
//!COMPONENTS 1

// Separable Gaussian smoothing of the Sobel magnitude map. Blurring before the second gradient pass (Kernel-X/Y) suppresses isolated noise spikes so the direction vectors used for warping reflect true edge orientation
#define SPATIAL_SIGMA (1.5 * LINESOBEL_size.y / NATIVE_RES_size.y)  // Base blur radius for edge detection (~0.5 to 3.0 by modifying the '1.5' multiplier)
#define KERNELSIZE (max(int(ceil(SPATIAL_SIGMA * 2.0)), 1) * 2 + 1) // Auto-calculates kernel footprint size; do not modify manually

float gaussian(float x, float s) { return exp(-0.5 * (x/s) * (x/s)); }

vec4 hook() {
    float g = 0.0, gn = 0.0;
    for (int i = 0; i < KERNELSIZE; i++) {
        float di = float(i - KERNELSIZE / 2);
        float gf = gaussian(di, SPATIAL_SIGMA);
        g += LINESOBEL_texOff(vec2(di, 0.0)).x * gf;
        gn += gf;
    }
    return vec4(g / gn, 0.0, 0.0, 0.0);
}

//!DESC Anime4K-Ultra-Thin-AA-SH-Gaussian-Y
//!HOOK MAIN
//!BIND NATIVE_RES
//!BIND LINESOBEL
//!SAVE LINESOBEL
//!WIDTH NATIVE_RES.w
//!HEIGHT NATIVE_RES.h
//!COMPONENTS 1

#define SPATIAL_SIGMA (1.5 * LINESOBEL_size.y / NATIVE_RES_size.y)  // Base blur radius for edge detection (~0.5 to 3.0 by modifying the '1.5' multiplier)
#define KERNELSIZE (max(int(ceil(SPATIAL_SIGMA * 2.0)), 1) * 2 + 1) // Auto-calculates kernel footprint size; do not modify manually

float gaussian(float x, float s) { return exp(-0.5 * (x/s) * (x/s)); }

vec4 hook() {
    float g = 0.0, gn = 0.0;
    for (int i = 0; i < KERNELSIZE; i++) {
        float di = float(i - KERNELSIZE / 2);
        float gf = gaussian(di, SPATIAL_SIGMA);
        g += LINESOBEL_texOff(vec2(0.0, di)).x * gf;
        gn += gf;
    }
    return vec4(g / gn, 0.0, 0.0, 0.0);
}

//!DESC Anime4K-Ultra-Thin-AA-SH-Kernel-X
//!HOOK MAIN
//!BIND NATIVE_RES
//!BIND LINESOBEL
//!SAVE LINESOBEL
//!WIDTH NATIVE_RES.w
//!HEIGHT NATIVE_RES.h
//!COMPONENTS 3

#define D (0.75 * LINESOBEL_size.y / NATIVE_RES_size.y)

vec4 hook() {
    float l = LINESOBEL_texOff(vec2(-D, 0.0)).x;
    float c = LINESOBEL_tex(LINESOBEL_pos).x;
    float r = LINESOBEL_texOff(vec2( D, 0.0)).x;
    // Second separable Sobel, horizontal pass, on the Gaussian-smoothed
    // magnitude map. Packs partial products alongside M for Kernel-Y:
    //   .x = gx_partial  =  -l + r
    //   .y = row_sum     =   l + 2c + r
    //   .z = M           =  smoothed Sobel magnitude (passed through)
    return vec4(-l + r, l + c + c + r, c, 0.0);
}

//!DESC Anime4K-Ultra-Thin-AA-SH-Kernel-Y
//!HOOK MAIN
//!BIND NATIVE_RES
//!BIND LINESOBEL
//!SAVE LINESOBEL
//!WIDTH NATIVE_RES.w
//!HEIGHT NATIVE_RES.h
//!COMPONENTS 3

#define D (0.75 * LINESOBEL_size.y / NATIVE_RES_size.y)

vec4 hook() {
    float tx = LINESOBEL_texOff(vec2(0.0,-D)).x;
    float cx = LINESOBEL_tex(LINESOBEL_pos).x;
    float bx = LINESOBEL_texOff(vec2(0.0, D)).x;
    float ty = LINESOBEL_texOff(vec2(0.0,-D)).y;
    float by = LINESOBEL_texOff(vec2(0.0, D)).y;
    float mask = LINESOBEL_tex(LINESOBEL_pos).z;
    // Completes the second Sobel pass. Final LINESOBEL layout:
    //   .x = gx   gradient x-component  (Sobel of smoothed M, /8)
    //   .y = gy   gradient y-component  (Sobel of smoothed M, /8)
    //   .z = M    Gaussian-smoothed Sobel magnitude (from Kernel-X .z)
    return vec4((tx + cx + cx + bx) / 8.0, (-ty + by) / 8.0, mask, 0.0);
}

//!DESC Anime4K-Ultra-Thin-AA-SH-Line-Confidence
//!HOOK MAIN
//!BIND NATIVE_RES
//!BIND LINESOBEL
//!SAVE LINECONF
//!WIDTH NATIVE_RES.w
//!HEIGHT NATIVE_RES.h
//!COMPONENTS 1

#define D (0.75 * LINESOBEL_size.y / NATIVE_RES_size.y)
#define TANGENT_TAPS  5   // Samples along line tangent (int 1 to 10); higher bridges wider gaps but costs performance
#define TANGENT_SIGMA 2.0 // Gaussian falloff for tangent tap weights (0.1 to ~5.0)

float gaussian(float x, float s) { return exp(-0.5 * (x/s) * (x/s)); }

vec4 hook() {
    vec3 sd = LINESOBEL_tex(LINESOBEL_pos).xyz;
    float mag = length(sd.xy);
    // Tangent direction: perpendicular to ∇M, so we walk along the line rather than across it
    vec2 tang = (mag > 0.001) ? vec2(-sd.y, sd.x) / mag : vec2(1.0, 0.0);

    // Accumulate smoothed magnitude (.z) along the tangent. Isolated noise spikes
    // score low; connected line segments score high
    //   <---[-N]-···-[-1]-[c]-[+1]-···-[+N]--->
    //          \___________TANGENT_TAPS_________/
    // Gaussian-weighted so central taps matter more than distant ones
    float csum = sd.z, cwsum = 1.0;
    for (int i = 1; i <= TANGENT_TAPS; i++) {
        float fi = float(i);
        float w = gaussian(fi, TANGENT_SIGMA);
        csum += (LINESOBEL_texOff( tang * (fi * D)).z + LINESOBEL_texOff(-tang * (fi * D)).z) * w;
        cwsum += 2.0 * w;
    }
    return vec4(csum / cwsum, 0.0, 0.0, 0.0);
}

//!DESC Anime4K-Ultra-Thin-AA-SH-Line-Absorb
//!HOOK MAIN
//!BIND NATIVE_RES
//!BIND HOOKED
//!BIND LINESOBEL
//!BIND LINECONF

#define ABSORB_STRENGTH 0.70  // Blend weight toward the line for halo pixels (0.0-1.0)
#define ABSORB_CONF_LOW 0.05  // Match CONF_LOW in the Warp pass
#define MAG_SENSITIVITY 1.3   // Raise for low-contrast lines (e.g. 0xbbb0bb / 0xf5f5f5)

// Degree-5 polynomial - same as before, peaks near M ≈ 0.45
#define P5 ( 11.68129591)
#define P4 (-42.46906057)
#define P3 ( 60.28286266)
#define P2 (-41.84451327)
#define P1 ( 14.05517353)
#define P0 ( -1.08152193)

float refine_curve(float x) {
    float x2 = x*x, x3 = x2*x, x4 = x2*x2, x5 = x2*x3;
    return P5*x5 + P4*x4 + P3*x3 + P2*x2 + P1*x + P0;
}

float get_luma(vec4 c) { return dot(vec3(0.299, 0.587, 0.114), c.rgb); }

vec4 hook() {
    float conf = LINECONF_tex(LINECONF_pos).x;
    if (conf < ABSORB_CONF_LOW)
        return HOOKED_tex(HOOKED_pos);

    // LINESOBEL.xy = ∇M (gradient of blurred Sobel magnitude, /8)
    // LINESOBEL.z  = M  (Gaussian-blurred Sobel magnitude)
    vec3 sd   = LINESOBEL_tex(LINESOBEL_pos).xyz;
    float mag = clamp(sd.z * MAG_SENSITIVITY, 0.0, 1.0);
    float dval = clamp(refine_curve(mag) * ABSORB_STRENGTH, 0.0, 1.0);
    if (dval < 0.001) return HOOKED_tex(HOOKED_pos);

    vec2 dc = sd.xy;
    float dclen = length(dc);
    if (dclen <= 0.001) return HOOKED_tex(HOOKED_pos);
    dc /= dclen;

    vec2 d   = HOOKED_pt;
    vec4 c   = HOOKED_tex(HOOKED_pos);
    float lc = get_luma(c);

    // +∇M = toward increasing edge magnitude = toward the line for halo/bg pixels.
    // Cardinal sampling weighted by edge angle, same pattern as before.
    vec4 xval = HOOKED_tex(HOOKED_pos + vec2(d.x *  sign(dc.x), 0.0));
    vec4 yval = HOOKED_tex(HOOKED_pos + vec2(0.0,  d.y *  sign(dc.y)));
    float xyratio = abs(dc.x) / (abs(dc.x) + abs(dc.y) + 0.0001);
    vec4 line_sample = xyratio * xval + (1.0 - xyratio) * yval;

    // Gate: only absorb when the line-direction sample is darker than the current pixel.
    //   - Halo/background pixel looking toward line:  l_toward < lc  → mask > 0  ✓
    //   - Line-interior pixel (+∇M overshoots to bg): l_toward ≥ lc  → mask = 0  ✓
    // The *8 factor gives a soft but steep ramp over ~0.125 luma units of contrast.
    float absorb_mask = clamp((lc - get_luma(line_sample)) * 8.0, 0.0, 1.0);

    return mix(c, line_sample, dval * absorb_mask);
}

//!DESC Anime4K-Ultra-Thin-AA-SH-Warp
//!HOOK MAIN
//!BIND NATIVE_RES
//!BIND HOOKED
//!BIND LINESOBEL
//!BIND LINECONF

#define D (0.75 * LINESOBEL_size.y / NATIVE_RES_size.y)
#define THIN_STRENGTH         0.08 // Base displacement step in output pixels per iteration (0.0 to ~0.2)
#define ITERATIONS            3    // Number of coordinate warping passes to thin lines (int 0 to ~10)
#define MIN_EDGE_STRENGTH     0.01 // Gradient magnitude threshold to abort warping early (0.0 to 1.0)
#define CONF_LOW              0.05 // Minimum line confidence required to trigger any warping (0.0 to 1.0)
#define BLURRY_DISP_THRESHOLD 0.4  // Max displacement (pixels) before triggering secondary blurry warp passes (0.0 to ~2.0)
#define BLURRY_RELSTR_MULT    1.5  // Multiplier for THIN_STRENGTH during extra blurry iterations (1.0 to ~3.0)
#define BLURRY_EDGE_MULT      0.4  // Multiplier for MIN_EDGE_STRENGTH during extra iterations (0.0 to 1.0)
#define BLURRY_EXTRA_ITERS    2    // Extra loop iterations if blurry threshold is met (int 0 to ~5)

vec4 hook() {
    if (LINECONF_tex(LINECONF_pos).x < CONF_LOW)
        return HOOKED_tex(HOOKED_pos);

    float relstr = D * 1.33 * THIN_STRENGTH;
    vec2 d = LINESOBEL_pt;
    vec2 offset = vec2(0.0);
    // Each iteration nudges the sampling UV toward the edge's luminance peak:
    // offset -= normalize(∇M) · pixel_step · relstr
    // Breaks early once |∇M| < MIN_EDGE_STRENGTH to prevent drift on flat regions
    for (int i = 0; i < ITERATIONS; i++) {
        vec2 dn = LINESOBEL_tex(LINESOBEL_pos + offset).xy;
        float mag = length(dn);
        if (mag > MIN_EDGE_STRENGTH)
            offset -= (dn / (mag + 0.01)) * d * relstr;
        else break;
    }

    // A small primary displacement means the gradient was real but weak (blurry line), not noise. A secondary pass with relaxed thresholds handles these cases
    if (length(offset / HOOKED_pt) < BLURRY_DISP_THRESHOLD) {
        float weak_relstr = relstr * BLURRY_RELSTR_MULT;
        float weak_threshold = MIN_EDGE_STRENGTH * BLURRY_EDGE_MULT;
        for (int i = 0; i < BLURRY_EXTRA_ITERS; i++) {
            vec2 dn = LINESOBEL_tex(LINESOBEL_pos + offset).xy;
            float mag = length(dn);
            if (mag > weak_threshold)
                offset -= (dn / (mag + 0.01)) * d * weak_relstr;
            else break;
        }
    }
    return HOOKED_tex(HOOKED_pos + offset);
}

//!DESC Anime4K-Ultra-Thin-AA-SH-PW-Sobel-X
//!HOOK MAIN
//!BIND NATIVE_RES
//!BIND HOOKED
//!SAVE PWSOBEL
//!COMPONENTS 2

#define D (0.75 * HOOKED_size.y / NATIVE_RES_size.y)

float pw_luma(vec4 c) { return dot(vec3(0.299, 0.587, 0.114), c.rgb); }

vec4 hook() {
    float l = pw_luma(HOOKED_texOff(vec2(-D, 0.0)));
    float c = pw_luma(HOOKED_tex(HOOKED_pos));
    float r = pw_luma(HOOKED_texOff(vec2( D, 0.0)));
    // Post-warp Sobel, horizontal pass. Operates on the warped HOOKED so that Dealias/Darken/ChromaDeblur have gradient vectors aligned to thinned lines
    // Same packing layout as the first Sobel-X:
    //   .x = gx_partial  =  -l + r
    //   .y = row_sum     =   l + 2c + r
    return vec4(-l + r, l + c + c + r, 0.0, 0.0);
}

//!DESC Anime4K-Ultra-Thin-AA-SH-PW-Sobel-Y
//!HOOK MAIN
//!BIND NATIVE_RES
//!BIND HOOKED
//!BIND PWSOBEL
//!SAVE PWSOBEL
//!COMPONENTS 3

#define D (0.75 * PWSOBEL_size.y / NATIVE_RES_size.y)

vec4 hook() {
    float tx = PWSOBEL_texOff(vec2(0.0,-D)).x;
    float cx = PWSOBEL_tex(PWSOBEL_pos).x;
    float bx = PWSOBEL_texOff(vec2(0.0, D)).x;
    float ty = PWSOBEL_texOff(vec2(0.0,-D)).y;
    float by = PWSOBEL_texOff(vec2(0.0, D)).y;
    float gx = (tx + cx + cx + bx) / 8.0;
    float gy = (-ty + by) / 8.0;
    // Completes the post-warp Sobel. Final PWSOBEL layout:
    //   .x = gx    gradient x-component (/8)
    //   .y = gy    gradient y-component (/8)
    //   .z = |∇|   gradient magnitude  (linear, not gamma-compressed)
    return vec4(gx, gy, sqrt(gx * gx + gy * gy), 0.0);
}

//!DESC Anime4K-Ultra-Thin-AA-SH-Dealias-USM
//!HOOK MAIN
//!BIND NATIVE_RES
//!BIND HOOKED
//!BIND PWSOBEL
//!BIND LINECONF

#define D (0.75 * HOOKED_size.y / NATIVE_RES_size.y)
#define DEALIAS_STRENGTH 0.25 // Interpolation strength (0.0 to ~2.0); >1.0 mathematically extrapolates to aggressively force AA
#define USM_AMOUNT       0.5  // Unsharp mask strength specifically over dealiased lines (0.0 to ~2.0)
#define CONF_LOW         0.02 // Lower bound of the effect mask's smoothstep transition (0.0 to 1.0)
#define CONF_HIGH        0.18 // Upper bound of the effect mask's smoothstep transition (0.0 to 1.0; must be > CONF_LOW)

float get_luma(vec3 rgb) { return dot(vec3(0.299, 0.587, 0.114), rgb); }

vec4 hook() {
    float effect_mask = smoothstep(CONF_LOW, CONF_HIGH, LINECONF_tex(LINECONF_pos).x);
    if (effect_mask < 0.001) return HOOKED_tex(HOOKED_pos);

    vec4 c = HOOKED_tex(HOOKED_pos);
    vec2 sd = PWSOBEL_tex(PWSOBEL_pos).xy;
    float mag = length(sd);
    vec2 tang = (mag > 0.01) ? vec2(-sd.y, sd.x) / mag : vec2(1.0, 0.0);

    // Bilateral average of two tangent taps. The high luma-proximity decay (x20) makes weights collapse to ~0 across even modest luma differences, so colour cannot bleed across the edge while the staircase along its length is smoothed:
    //   w = exp(-|luma(tap) - luma(centre)| · 20)
    vec4 t1 = HOOKED_tex(HOOKED_pos + tang * HOOKED_pt * D);
    vec4 t2 = HOOKED_tex(HOOKED_pos - tang * HOOKED_pt * D);
    float lc = get_luma(c.rgb);
    float w1 = exp(-abs(get_luma(t1.rgb) - lc) * 20.0);
    float w2 = exp(-abs(get_luma(t2.rgb) - lc) * 20.0);
    vec4 c_da = mix(c, (c + t1 * w1 + t2 * w2) / (1.0 + w1 + w2), DEALIAS_STRENGTH * effect_mask);

    // Unsharp mask applied to the dealiased result, gated by effect_mask so flat regions receive no sharpening
    vec4 blur = (
        HOOKED_texOff(vec2(-D,-D)) + HOOKED_texOff(vec2(0.0,-D)) + HOOKED_texOff(vec2(D,-D)) +
        HOOKED_texOff(vec2(-D, 0.0)) + c + HOOKED_texOff(vec2(D, 0.0)) +
        HOOKED_texOff(vec2(-D, D)) + HOOKED_texOff(vec2(0.0, D)) + HOOKED_texOff(vec2(D, D))
    ) / 9.0;
    return clamp(c_da + (c - blur) * USM_AMOUNT * effect_mask, 0.0, 1.0);
}

//!DESC Anime4K-Ultra-Thin-AA-SH-Darken
//!HOOK MAIN
//!BIND NATIVE_RES
//!BIND HOOKED
//!BIND PWSOBEL
//!BIND LINECONF

#define D (0.75 * HOOKED_size.y / NATIVE_RES_size.y)
#define DARKEN_STRENGTH   0.30 // Base multiplier for line darkening via local luma valleys (0.0 to ~1.0)
#define DARKEN_MAX_FRAC   0.25 // Max fraction of luma to SUBTRACT (0.0 to 1.0)
#define DARKEN_LUMA_FLOOR 0.08 // Below this luma, full darkening is applied (canonical dark ink lines)
#define DARKEN_LUMA_CEIL  0.82 // Above this luma, darkening is fully suppressed (coloured lines / highlights)
#define CONF_LOW          0.02 // Lower bound of the effect mask's smoothstep transition (0.0 to 1.0)
#define CONF_HIGH         0.18 // Upper bound of the effect mask's smoothstep transition (0.0 to 1.0; must be > CONF_LOW)

float get_luma(vec3 rgb) { return dot(vec3(0.299, 0.587, 0.114), rgb); }

float valley_at(vec2 p) {
    vec2 gxy = PWSOBEL_tex(p).xy;
    float mag = length(gxy);
    vec2 norm = (mag > 0.01) ? (gxy / mag) : vec2(1.0, 0.0);
    float lc = get_luma(HOOKED_tex(p).rgb);
    // Check two steps outward in each direction to handle lines thicker than one pixel, then take the brighter of the two as the background reference:
    //   lpos = max(luma at +D, luma at +2D)  along ∇M
    //   lneg = max(luma at -D, luma at -2D)  along ∇M
    float lpos = max(get_luma(HOOKED_tex(p + norm * HOOKED_pt * D).rgb),
                     get_luma(HOOKED_tex(p + norm * HOOKED_pt * (D * 2.0)).rgb));
    float lneg = max(get_luma(HOOKED_tex(p - norm * HOOKED_pt * D).rgb),
                     get_luma(HOOKED_tex(p - norm * HOOKED_pt * (D * 2.0)).rgb));
    // x8 scaling + normalisation:
    // score -> 1.0 when centre is a clear local minimum (ink line), -> 0 on shallow gradients (shading, textures)
    return clamp((min(lpos, lneg) - lc) * 8.0 / max(max(lpos, lneg), 0.1), 0.0, 1.0);
}

vec4 hook() {
    float effect_mask = smoothstep(CONF_LOW, CONF_HIGH, LINECONF_tex(LINECONF_pos).x);
    if (effect_mask < 0.001) return HOOKED_tex(HOOKED_pos);

    vec2 gxy = PWSOBEL_tex(PWSOBEL_pos).xy;
    float mag = length(gxy);
    vec2 tang = (mag > 0.01) ? vec2(-gxy.y, gxy.x) / mag : vec2(1.0, 0.0);

    // Max-pool valley score across three tangent positions so narrow lines that sit between output pixels still register
    float valley = max(valley_at(HOOKED_pos),
                   max(valley_at(HOOKED_pos + tang * HOOKED_pt * D),
                       valley_at(HOOKED_pos - tang * HOOKED_pt * D)));

    vec4 c = HOOKED_tex(HOOKED_pos);
    float l = get_luma(c.rgb);

    // Suppress darkening on saturated pixels (coloured lines / highlights)
    // luma_gate and sat_suppress are multiplied so either condition alone can fully suppress the effect
	float sat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
	float luma_gate = 1.0 - smoothstep(DARKEN_LUMA_FLOOR, DARKEN_LUMA_CEIL, l);
	float sat_suppress = smoothstep(0.08, 0.25, sat);
	float darken_gate = luma_gate * (1.0 - sat_suppress);

	float delta = min(effect_mask * valley * DARKEN_STRENGTH * l * darken_gate, l * DARKEN_MAX_FRAC);
    // Scale RGB uniformly so hue is preserved while luma drops by delta
    c.rgb *= clamp((l - delta) / max(l, 0.001), 0.0, 1.0);
    return c;
}

//!DESC Anime4K-Ultra-Thin-AA-SH-ChromaDeblur
//!HOOK MAIN
//!BIND NATIVE_RES
//!BIND HOOKED
//!BIND PWSOBEL
//!BIND LINECONF

#define D (0.75 * HOOKED_size.y / NATIVE_RES_size.y)
#define CHROMA_DEBLUR_AMOUNT 2.2   // (0.0 to ~3.0)
#define DARKEN_LUMA_FLOOR    0.08  // Match above
#define DARKEN_LUMA_CEIL     0.72  // Match above
#define CONF_LOW             0.02
#define CONF_HIGH            0.18
#define TANGENT_TAPS         2     // ±N taps along tangent (1 = 3-tap, 2 = 5-tap)
#define TANGENT_SIGMA        1.0   // Gaussian sigma for tangent weights

float get_luma(vec3 rgb) { return dot(vec3(0.299, 0.587, 0.114), rgb); }
float gaussian(float x, float s) { return exp(-0.5 * (x/s) * (x/s)); }

vec4 hook() {
    float effect_mask = smoothstep(CONF_LOW, CONF_HIGH, LINECONF_tex(LINECONF_pos).x);
    if (effect_mask < 0.001) return HOOKED_tex(HOOKED_pos);

    vec4 c = HOOKED_tex(HOOKED_pos);
    float l = get_luma(c.rgb);

    // Inverse of Darken's luma_gate: restricts sharpening to coloured/bright pixels, avoiding double-processing of dark ink lines
    float color_gate = smoothstep(DARKEN_LUMA_FLOOR, DARKEN_LUMA_CEIL, l);
    if (color_gate < 0.001) return c;

    vec2 sd = PWSOBEL_tex(PWSOBEL_pos).xy;
    float mag = length(sd);
    vec2 tang = (mag > 0.01) ? vec2(-sd.y, sd.x) / mag : vec2(1.0, 0.0);

    // Gaussian-weighted blur along the edge tangent, used as the low-frequency reference for the unsharp mask. Tangent-aligned blur avoids softening across the edge while still averaging chroma fringing along its length
    vec4 blur = vec4(0.0);
    float wsum = 0.0;
    for (int i = -TANGENT_TAPS; i <= TANGENT_TAPS; i++) {
        float fi = float(i);
        float w = gaussian(fi, TANGENT_SIGMA);
        blur += HOOKED_texOff(tang * fi * D) * w;
        wsum += w;
    }
    blur /= wsum;

    // sharpened = c + (c - tangent_blur) · strength
    float strength = CHROMA_DEBLUR_AMOUNT * effect_mask * color_gate;
    vec3 sharpened = c.rgb + (c.rgb - blur.rgb) * strength;

    // Clamp to the min/max of the two nearest tangent neighbours to prevent residual overshoot from propagating into flat colour areas
    vec4 t1 = HOOKED_texOff( tang * D);
    vec4 t2 = HOOKED_texOff(-tang * D);
    vec3 mn = min(min(t1.rgb, t2.rgb), c.rgb);
    vec3 mx = max(max(t1.rgb, t2.rgb), c.rgb);
    sharpened = clamp(sharpened, mn, mx);

    return clamp(vec4(sharpened, c.a), 0.0, 1.0);
}
