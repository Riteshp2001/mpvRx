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
// COMPONENT: Anime4K_Thin_AA_Deblur-Heavy.glsl
// =============================================================================

//!DESC Anime4K-Ultra-Thin-AA-DB-Luma-Sharp
//!HOOK MAIN
//!BIND NATIVE_RES
//!BIND HOOKED
//!SAVE LINELUMA_SHARP
//!WIDTH HOOKED.w
//!HEIGHT HOOKED.h
//!COMPONENTS 1

#define D (0.75 * HOOKED_size.y / NATIVE_RES_size.y)
#define LUMA_SHARP_AMOUNT 1.5

vec4 hook() {
    float c = HOOKED_tex(HOOKED_pos).x;
    float blur = (
        HOOKED_texOff(vec2(-D,-D)).x + HOOKED_texOff(vec2( 0.0,-D)).x + HOOKED_texOff(vec2(D,-D)).x +
        HOOKED_texOff(vec2(-D, 0.0)).x + c + HOOKED_texOff(vec2(D, 0.0)).x +
        HOOKED_texOff(vec2(-D, D)).x + HOOKED_texOff(vec2( 0.0, D)).x + HOOKED_texOff(vec2(D, D)).x
    ) / 9.0;
    return vec4(clamp(c + (c - blur) * LUMA_SHARP_AMOUNT, 0.0, 1.0), 0.0, 0.0, 0.0);
}

//!DESC Anime4K-Ultra-Thin-AA-DB-Sobel-X
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

//!DESC Anime4K-Ultra-Thin-AA-DB-Sobel-Y
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

//!DESC Anime4K-Ultra-Thin-AA-DB-Gaussian-X
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

//!DESC Anime4K-Ultra-Thin-AA-DB-Gaussian-Y
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

//!DESC Anime4K-Ultra-Thin-AA-DB-Kernel-X
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

//!DESC Anime4K-Ultra-Thin-AA-DB-Kernel-Y
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

//!DESC Anime4K-Ultra-Thin-AA-DB-Line-Confidence
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

//!DESC Anime4K-Ultra-Thin-AA-DB-Warp
//!HOOK MAIN
//!BIND NATIVE_RES
//!BIND HOOKED
//!BIND LINESOBEL
//!BIND LINECONF

#define D (0.75 * LINESOBEL_size.y / NATIVE_RES_size.y)
#define THIN_STRENGTH         0.05 // Base displacement step in output pixels per iteration (0.0 to ~0.2)
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

//!DESC Anime4K-Ultra-Thin-AA-DB-PW-Sobel-X
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

//!DESC Anime4K-Ultra-Thin-AA-DB-PW-Sobel-Y
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

//!DESC Anime4K-Ultra-Thin-AA-DB-Dealias-Deblur
//!HOOK MAIN
//!BIND NATIVE_RES
//!BIND HOOKED
//!BIND PWSOBEL
//!BIND LINESOBEL
//!BIND LINECONF

#define D (0.75 * HOOKED_size.y / NATIVE_RES_size.y)
#define DEALIAS_STRENGTH 0.25 // Interpolation strength (0.0 to ~2.0); >1.0 mathematically extrapolates to aggressively force AA
#define DEBLUR_PUSH      0.70 // Strength of luma push toward the detected edge extreme (0.0 to 1.0)
#define DEBLUR_TAPS      3    // Samples along edge normal for min/max luma search (int 1 to ~5)
#define DEBLUR_EDGE_BAND 0.06 // Smoothstep half-width for edge-side classification (0.0 to ~0.2)
#define CONF_LOW         0.02 // Lower bound of the effect mask's smoothstep transition (0.0 to 1.0)
#define CONF_HIGH        0.18 // Upper bound of the effect mask's smoothstep transition (0.0 to 1.0; must be > CONF_LOW)

float get_luma(vec3 rgb) { return dot(vec3(0.299, 0.587, 0.114), rgb); }

vec4 hook() {
    float effect_mask = smoothstep(CONF_LOW, CONF_HIGH, LINECONF_tex(LINECONF_pos).x);
    if (effect_mask < 0.001) return HOOKED_tex(HOOKED_pos);

    vec2 wpos = HOOKED_pos;

    vec4 c = HOOKED_tex(wpos);
    vec2 sd = PWSOBEL_tex(PWSOBEL_pos).xy;
    float mag = length(sd);
    vec2 tang = (mag > 0.01) ? vec2(-sd.y,  sd.x) / mag : vec2(1.0, 0.0); // ⊥ to gradient = along line
    vec2 norm = (mag > 0.01) ? sd / mag : vec2(0.0, 1.0); // ∥ to gradient = across line, pointing toward bright side

    // Blends neighbours along the line. Bilateral weights suppress mixing across
    // luma discontinuities, keeping the blend within the same side of the edge
    vec4 t1 = HOOKED_tex(wpos + tang * HOOKED_pt * D);
    vec4 t2 = HOOKED_tex(wpos - tang * HOOKED_pt * D);
    float lc = get_luma(c.rgb);
    float w1 = exp(-abs(get_luma(t1.rgb) - lc) * 20.0); // 20.0: bilateral sensitivity; large luma diff → weight ≈ 0
    float w2 = exp(-abs(get_luma(t2.rgb) - lc) * 20.0);
    vec4 c_da = mix(c, (c + t1 * w1 + t2 * w2) / (1.0 + w1 + w2),
                     DEALIAS_STRENGTH * effect_mask);

    // Scans DEBLUR_TAPS steps along ±norm to find the luma range bracketing the
    // edge. Since norm points toward the bright side:
    //   -norm taps → ink/dark side  → tracked by darkest
    //   +norm taps → bg/bright side → tracked by brightest
    float darkest = lc, brightest = lc;
    for (int i = 1; i <= DEBLUR_TAPS; i++) {
        float fi = float(i);
        darkest   = min(darkest,   get_luma(HOOKED_tex(wpos - norm * HOOKED_pt * D * fi).rgb));
        brightest = max(brightest, get_luma(HOOKED_tex(wpos + norm * HOOKED_pt * D * fi).rgb));
    }

    float mid = (darkest + brightest) * 0.5;
    float s_lo = max(mid - DEBLUR_EDGE_BAND, darkest);
    float s_hi = min(mid + DEBLUR_EDGE_BAND, brightest);
    float side = (s_hi > s_lo + 1e-5)
        ? smoothstep(s_lo, s_hi, lc)
        : (lc >= mid ? 1.0 : 0.0);

    float target_luma = mix(darkest, brightest, side);

    float lc_da = get_luma(c_da.rgb);
    float luma_scale = (lc_da > 0.001)
        ? mix(lc_da, target_luma, DEBLUR_PUSH * effect_mask) / lc_da
        : 1.0;

    c_da.rgb = clamp(c_da.rgb * luma_scale, 0.0, 1.0);

    return clamp(c_da, 0.0, 1.0);
}

//!DESC Anime4K-Ultra-Thin-AA-DB-Darken
//!HOOK MAIN
//!BIND NATIVE_RES
//!BIND HOOKED
//!BIND PWSOBEL
//!BIND LINECONF

#define D (0.75 * HOOKED_size.y / NATIVE_RES_size.y)
#define DARKEN_STRENGTH         0.60 // Overall darkening scale (0.0 to ~1.0)
#define DARKEN_MAX_FRAC         0.35 // Cap for achromatic (black/gray) ink lines
#define DARKEN_MAX_FRAC_HUE     0.28 // Reduced cap for hue-matched coloured lines (e.g. red lines in red hair)
#define DARKEN_LUMA_FLOOR       0.08 // Luma below which darkening fades out (0.0 to 1.0)
#define DARKEN_LUMA_CEIL        0.88 // Luma above which darkening is fully suppressed (0.0 to 1.0)
#define CONF_LOW                0.02 // Lower bound of the effect mask's smoothstep transition (0.0 to 1.0)
#define CONF_HIGH               0.18 // Upper bound of the effect mask's smoothstep transition (0.0 to 1.0; must be > CONF_LOW)
#define VALLEY_BG_NEAR          1.0  // Near background tap distance in pixels
#define VALLEY_BG_FAR           2.0  // Far background tap distance in pixels
#define VALLEY_WIDTH_GATE       0.06
// Minimum dot product between a tangent tap's gradient direction and the center
// gradient direction for that tap to be allowed to elevate the valley score.
// ~cos(50°) ≈ 0.64. Raise to be stricter about "same line"; lower to tolerate
// more curve/corner variation along the tangent.
#define TANGENT_CONSISTENCY_MIN 0.64
#define HUE_MATCH_SAT_MIN       0.04 // Min chroma vector length to attempt hue comparison (0.0 to ~0.2)
#define HUE_MATCH_DOT_THRESHOLD 0.70 // Chroma dot product above which line is considered hue-matched to bg (~cos(45°))

float get_luma(vec3 rgb) { return dot(vec3(0.299, 0.587, 0.114), rgb); }

// Returns a [0,1] score indicating how much darker p is than its surroundings on both sides
// High score = confident ink valley; low score = flat region or one-sided edge (shadow, gradient)
float valley_at(vec2 p) {
    vec2 gxy = PWSOBEL_tex(p).xy;
    float mag = length(gxy);
    vec2 norm = (mag > 0.01) ? (gxy / mag) : vec2(1.0, 0.0);
    float lc = get_luma(HOOKED_tex(p).rgb);

    float lpos_near = get_luma(HOOKED_tex(p + norm * HOOKED_pt * D * VALLEY_BG_NEAR).rgb);
    float lneg_near = get_luma(HOOKED_tex(p - norm * HOOKED_pt * D * VALLEY_BG_NEAR).rgb);
    // max(near, far) so a line adjacent to a darker region still detects the brighter background further out
    float lpos = max(lpos_near, get_luma(HOOKED_tex(p + norm * HOOKED_pt * D * VALLEY_BG_FAR).rgb));
    float lneg = max(lneg_near, get_luma(HOOKED_tex(p - norm * HOOKED_pt * D * VALLEY_BG_FAR).rgb));

    // How much darker is the center than the dimmer side, normalized by the brighter side
    float raw_valley = clamp((min(lpos, lneg) - lc) * 8.0 / max(max(lpos, lneg), 0.1), 0.0, 1.0);
    float near_bright = max(lpos_near, lneg_near);
    float width_gate = smoothstep(lc, lc + VALLEY_WIDTH_GATE, near_bright);

    return raw_valley * width_gate;
}

vec4 hook() {
    float effect_mask = smoothstep(CONF_LOW, CONF_HIGH, LINECONF_tex(LINECONF_pos).x);
    if (effect_mask < 0.001) return HOOKED_tex(HOOKED_pos);

    vec2 gxy = PWSOBEL_tex(PWSOBEL_pos).xy;
    float mag = length(gxy);
    vec2 norm = (mag > 0.01) ?  gxy / mag                  : vec2(1.0, 0.0);
    vec2 tang = (mag > 0.01) ?  vec2(-gxy.y, gxy.x) / mag  : vec2(1.0, 0.0);

    // Gradient-direction-consistent max pooling
    float v0 = valley_at(HOOKED_pos);
    vec2 p1 = HOOKED_pos + tang * HOOKED_pt * D;
    vec2 p2 = HOOKED_pos - tang * HOOKED_pt * D;

    vec2 g1 = PWSOBEL_tex(p1).xy;
    vec2 g2 = PWSOBEL_tex(p2).xy;
    float m1 = length(g1), m2 = length(g2);

    // abs(dot) measures alignment between the tangent tap's gradient and the center normal
    // High value = tap is on the same line; abs() handles opposite-sign gradients across the line
    float c1 = (m1 > 0.01) ? abs(dot(g1 / m1, norm)) : 0.0;
    float c2 = (m2 > 0.01) ? abs(dot(g2 / m2, norm)) : 0.0;
    float gate1 = smoothstep(TANGENT_CONSISTENCY_MIN, 1.0, c1);
    float gate2 = smoothstep(TANGENT_CONSISTENCY_MIN, 1.0, c2);

    // Only allow tangent taps to raise the score if their gradient direction is consistent with the center (same line, not a crossing edge)
    float valley = max(v0, max(valley_at(p1) * gate1, valley_at(p2) * gate2));

    vec4 c = HOOKED_tex(HOOKED_pos);
    float l = get_luma(c.rgb);

    // Picks the brighter background side to compare against the line's chroma
    vec3 bg_pos = HOOKED_tex(HOOKED_pos + norm * HOOKED_pt * D * VALLEY_BG_FAR).rgb;
    vec3 bg_neg = HOOKED_tex(HOOKED_pos - norm * HOOKED_pt * D * VALLEY_BG_FAR).rgb;
    vec3 bg_rgb = (get_luma(bg_pos) >= get_luma(bg_neg)) ? bg_pos : bg_neg;

    // Chroma vectors: colour minus its own grey (i.e. subtract luma component)
    vec3 line_chroma = c.rgb  - vec3(l);
    vec3 bg_chroma = bg_rgb - vec3(get_luma(bg_rgb));
    float line_clen = length(line_chroma);
    float bg_clen = length(bg_chroma);
    // Dot product of unit chroma vectors: 1 = same hue, 0 = orthogonal, guarded by saturation floor
    float hue_dot = (line_clen > HUE_MATCH_SAT_MIN && bg_clen > HUE_MATCH_SAT_MIN)
                    ? clamp(dot(line_chroma / line_clen, bg_chroma / bg_clen), 0.0, 1.0)
                    : 0.0;
    float hue_match = smoothstep(HUE_MATCH_DOT_THRESHOLD, 1.0, hue_dot);

    float sat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
    float is_saturated = smoothstep(0.08, 0.25, sat);
    float luma_gate = 1.0 - smoothstep(DARKEN_LUMA_FLOOR, DARKEN_LUMA_CEIL, l);
    float sat_suppress = is_saturated * (1.0 - hue_match);
    float darken_gate = luma_gate * (1.0 - sat_suppress);

    float effective_max_frac = mix(DARKEN_MAX_FRAC, DARKEN_MAX_FRAC_HUE, hue_match * is_saturated);
    // Multiplying by l makes the raw delta proportional to current brightness,
    // preventing over-darkening of pixels that are already dim
    float delta = min(effect_mask * valley * DARKEN_STRENGTH * l * darken_gate,
                      l * effective_max_frac);
    c.rgb *= clamp((l - delta) / max(l, 0.001), 0.0, 1.0); // uniform RGB scale preserves hue
    return c;
}
