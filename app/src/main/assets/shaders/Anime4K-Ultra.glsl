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
// COMPONENT: Anime4K_Thin_AA.glsl
// =============================================================================

//!DESC Anime4K-Ultra-Thin-AA-Luma
//!HOOK MAIN
//!BIND HOOKED
//!SAVE LINELUMA
//!COMPONENTS 1

float get_luma(vec4 rgba) {
    return dot(vec3(0.299, 0.587, 0.114), rgba.rgb);
}

vec4 hook() {
    return vec4(get_luma(HOOKED_tex(HOOKED_pos)), 0.0, 0.0, 0.0);
}

//!DESC Anime4K-Ultra-Thin-AA-Sobel-X
//!HOOK MAIN
//!BIND LINELUMA
//!SAVE LINESOBEL
//!COMPONENTS 2

vec4 hook() {
    float l = LINELUMA_texOff(vec2(-1.0, 0.0)).x;
    float c = LINELUMA_tex(LINELUMA_pos).x;
    float r = LINELUMA_texOff(vec2(1.0, 0.0)).x;
    return vec4(-l + r, l + c + c + r, 0.0, 0.0);
}

//!DESC Anime4K-Ultra-Thin-AA-Sobel-Y
//!HOOK MAIN
//!BIND LINESOBEL
//!SAVE LINESOBEL
//!COMPONENTS 1

vec4 hook() {
    float tx = LINESOBEL_texOff(vec2(0.0, -1.0)).x;
    float cx = LINESOBEL_tex(LINESOBEL_pos).x;
    float bx = LINESOBEL_texOff(vec2(0.0, 1.0)).x;
    float ty = LINESOBEL_texOff(vec2(0.0, -1.0)).y;
    float by = LINESOBEL_texOff(vec2(0.0, 1.0)).y;
    float xgrad = (tx + cx + cx + bx) / 8.0;
    float ygrad = (-ty + by) / 8.0;
    return vec4(pow(sqrt(xgrad * xgrad + ygrad * ygrad), 0.7));
}

//!DESC Anime4K-Ultra-Thin-AA-Gaussian-X
//!HOOK MAIN
//!BIND HOOKED
//!BIND LINESOBEL
//!SAVE LINESOBEL
//!COMPONENTS 1

#define SPATIAL_SIGMA (1.5 * float(HOOKED_size.y) / 1080.0) //Spatial window size, must be a positive real number.
#define KERNELSIZE (max(int(ceil(SPATIAL_SIGMA * 2.0)), 1) * 2 + 1) //Kernel size, must be an positive odd integer.

float gaussian(float x, float s) {
    return exp(-0.5 * (x/s) * (x/s));
}

vec4 hook() {
    float g = 0.0;
    float gn = 0.0;
    for (int i=0; i<KERNELSIZE; i++) {
        float di = float(i - int(KERNELSIZE/2));
        float gf = gaussian(di, SPATIAL_SIGMA);
        g += LINESOBEL_texOff(vec2(di, 0.0)).x * gf;
        gn += gf;
    }
    return vec4(g / gn, 0.0, 0.0, 0.0);
}

//!DESC Anime4K-Ultra-Thin-AA-Gaussian-Y
//!HOOK MAIN
//!BIND HOOKED
//!BIND LINESOBEL
//!SAVE LINESOBEL
//!COMPONENTS 1

#define SPATIAL_SIGMA (1.5 * float(HOOKED_size.y) / 1080.0) //Spatial window size, must be a positive real number.
#define KERNELSIZE (max(int(ceil(SPATIAL_SIGMA * 2.0)), 1) * 2 + 1) //Kernel size, must be an positive odd integer

float gaussian(float x, float s) {
    return exp(-0.5 * (x/s) * (x/s));
}

vec4 hook() {
    float g = 0.0;
    float gn = 0.0;
    for (int i=0; i<KERNELSIZE; i++) {
        float di = float(i - int(KERNELSIZE/2));
        float gf = gaussian(di, SPATIAL_SIGMA);
        g += LINESOBEL_texOff(vec2(0.0, di)).x * gf;
        gn += gf;
    }
    return vec4(g / gn, 0.0, 0.0, 0.0);
}

//!DESC Anime4K-Ultra-Thin-AA-Kernel-X
//!HOOK MAIN
//!BIND LINESOBEL
//!SAVE LINESOBEL
//!COMPONENTS 3

vec4 hook() {
    float l = LINESOBEL_texOff(vec2(-1.0, 0.0)).x;
    float c = LINESOBEL_tex(LINESOBEL_pos).x;
    float r = LINESOBEL_texOff(vec2(1.0, 0.0)).x;
    return vec4(-l + r, l + c + c + r, c, 0.0);
}

//!DESC Anime4K-Ultra-Thin-AA-Kernel-Y
//!HOOK MAIN
//!BIND LINESOBEL
//!SAVE LINESOBEL
//!COMPONENTS 3

vec4 hook() {
    float tx = LINESOBEL_texOff(vec2(0.0, -1.0)).x;
    float cx = LINESOBEL_tex(LINESOBEL_pos).x;
    float bx = LINESOBEL_texOff(vec2(0.0, 1.0)).x;
    float ty = LINESOBEL_texOff(vec2(0.0, -1.0)).y;
    float by = LINESOBEL_texOff(vec2(0.0, 1.0)).y;
    float line_mask = LINESOBEL_tex(LINESOBEL_pos).z;
    return vec4((tx + cx + cx + bx) / 8.0, (-ty + by) / 8.0, line_mask, 0.0);
}

//!DESC Anime4K-Ultra-Thin-AA-Warp-Final
//!HOOK MAIN
//!BIND HOOKED
//!BIND LINESOBEL

#define THIN_STRENGTH 0.12 // Strength of warping for each iteration
#define ITERATIONS 3       // Number of iterations for the forwards solver, decreasing strength and increasing iterations improves quality at the cost of speed
#define DARKEN_STRENGTH 0.7    // [0.0 to 1.0]
#define DEALIAS_STRENGTH 0.5   // [0.0 to 2.0]
#define MIN_EDGE_STRENGTH 0.01 // [0.0 to 1.0] Higher = protects glows more, but might miss very faint lines

vec4 hook() {
    vec2 d = HOOKED_pt;
    float relstr = HOOKED_size.y / 1080.0 * THIN_STRENGTH;
    vec2 pos = HOOKED_pos;
    
    // Thinning / Warping
    for (int i=0; i<ITERATIONS; i++) {
        vec2 dn = LINESOBEL_tex(pos).xy;
        float mag = length(dn);
        if (mag > MIN_EDGE_STRENGTH) {
            vec2 dd = (dn / (mag + 0.01)) * d * relstr;
            pos -= dd;
        } else {
            break;
        }
    }

    vec4 c_final = HOOKED_tex(pos);

    // Dark line detection (5-tap average)
    vec2 d_aa = d * 0.5;
    vec4 c_l = HOOKED_tex(pos - vec2(d_aa.x, 0.0));
    vec4 c_r = HOOKED_tex(pos + vec2(d_aa.x, 0.0));
    vec4 c_t = HOOKED_tex(pos - vec2(0.0, d_aa.y));
    vec4 c_b = HOOKED_tex(pos + vec2(0.0, d_aa.y));
    vec4 c_avg = (c_final + c_l + c_r + c_t + c_b) / 5.0;

    // Inline Luma Calculation (Standard Rec.601)
    float l_center = dot(vec3(0.299, 0.587, 0.114), c_final.rgb);
    float l_avg    = dot(vec3(0.299, 0.587, 0.114), c_avg.rgb);
    float valley_depth = clamp((l_avg - l_center) * 10.0, 0.0, 1.0); // Positive if center is darker than neighbors
    
    float structure = LINESOBEL_tex(pos).z;
    float structure_mask = smoothstep(0.05, 0.15, structure);

    float darken_mask = valley_depth * structure_mask;

    // Apply
    c_final = mix(c_final, c_avg, darken_mask * DEALIAS_STRENGTH);
    c_final.rgb -= c_final.rgb * darken_mask * (1.0 - l_center) * DARKEN_STRENGTH;

    return c_final;
}
