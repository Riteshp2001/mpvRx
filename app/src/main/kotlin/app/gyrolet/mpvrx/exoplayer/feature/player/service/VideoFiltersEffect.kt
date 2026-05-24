package app.gyrolet.mpvrx.exoplayer.feature.player.service

import android.content.Context
import android.opengl.GLES20
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

@OptIn(UnstableApi::class)
internal class VideoFiltersEffect(
    private var transition: VideoFilterTransition,
    private val transitionDurationMs: Long,
) : GlEffect {

    fun updateTransition(transition: VideoFilterTransition) {
        this.transition = transition
    }

    override fun toGlShaderProgram(
        context: Context,
        useHdr: Boolean,
    ): GlShaderProgram = VideoFiltersShaderProgram(
        useHdr = useHdr,
        effect = this,
        transitionDurationMs = transitionDurationMs,
    )

    override fun isNoOp(
        inputWidth: Int,
        inputHeight: Int,
    ): Boolean = false

    internal fun getCurrentFilters(currentMs: Long): VideoFilterPreferences = transition.currentFilters(
        currentMs = currentMs,
        durationMs = transitionDurationMs,
    )

    internal fun getTargetFilters(): VideoFilterPreferences = transition.targetFilters

    private class VideoFiltersShaderProgram(
        useHdr: Boolean,
        private val effect: VideoFiltersEffect,
        private val transitionDurationMs: Long,
    ) : BaseGlShaderProgram(useHdr, 1) {

        private val glProgram = createGlProgram()
        private var inputWidth = 1
        private var inputHeight = 1

        init {
            val identityMatrix = GlUtil.create4x4IdentityMatrix()
            glProgram.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                POSITION_COMPONENT_COUNT,
            )
            glProgram.setFloatsUniform("uTransformationMatrix", identityMatrix)
            glProgram.setFloatsUniform("uTexTransformationMatrix", identityMatrix)
        }

        override fun configure(
            inputWidth: Int,
            inputHeight: Int,
        ): Size {
            this.inputWidth = inputWidth
            this.inputHeight = inputHeight
            val filters = effect.getTargetFilters()
            val outputSize = if (filters.isAmbienceModeEnabled && filters.screenAspectRatio > 0f) {
                val inputAspectRatio = inputWidth.toFloat() / inputHeight
                if (filters.screenAspectRatio > inputAspectRatio) {
                    // Screen is wider than video (pillarbox)
                    Size((inputHeight * filters.screenAspectRatio).toInt(), inputHeight)
                } else {
                    // Screen is taller than video (letterbox)
                    Size(inputWidth, (inputWidth / filters.screenAspectRatio).toInt())
                }
            } else {
                Size(inputWidth, inputHeight)
            }

            glProgram.setFloatsUniform(
                "uTexelSize",
                floatArrayOf(
                    1f / inputWidth,
                    1f / inputHeight,
                ),
            )
            return outputSize
        }

        override fun drawFrame(
            inputTexId: Int,
            presentationTimeUs: Long,
        ) {
            try {
                glProgram.use()
                val filters = effect.getCurrentFilters(SystemClock.elapsedRealtime())
                setFilterUniforms(filters)

                if (filters.isAmbienceModeEnabled && filters.screenAspectRatio > 0f) {
                    val inputAr = inputWidth.toFloat() / inputHeight
                    val screenAr = filters.screenAspectRatio
                    
                    val scaleX = if (screenAr > inputAr) screenAr / inputAr else 1.0f
                    val scaleY = if (inputAr > screenAr) inputAr / screenAr else 1.0f
                    
                    glProgram.setFloatUniform("uScaleX", scaleX)
                    glProgram.setFloatUniform("uScaleY", scaleY)
                    glProgram.setFloatUniform("uAmbienceEnabled", 1.0f)
                } else {
                    glProgram.setFloatUniform("uScaleX", 1.0f)
                    glProgram.setFloatUniform("uScaleY", 1.0f)
                    glProgram.setFloatUniform("uAmbienceEnabled", 0.0f)
                }

                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                glProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, VERTEX_COUNT)
                GlUtil.checkGlError()
            } catch (exception: GlUtil.GlException) {
                throw VideoFrameProcessingException(exception, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try {
                glProgram.delete()
            } catch (exception: GlUtil.GlException) {
                throw VideoFrameProcessingException(exception)
            }
        }

        private fun setFilterUniforms(filters: VideoFilterPreferences) {
            glProgram.setFloatUniform("uBrightness", filters.brightness)
            glProgram.setFloatUniform("uContrast", filters.contrast)
            glProgram.setFloatUniform("uSaturation", filters.saturation)
            glProgram.setFloatUniform("uHue", filters.hue)
            glProgram.setFloatUniform("uGamma", filters.gamma)
            val sharpness = kotlin.math.sqrt(filters.sharpening.coerceAtLeast(0f)) * SHARPNESS_SCALE
            glProgram.setFloatUniform("uSharpness", sharpness)
        }

        private fun createGlProgram(): GlProgram = try {
            GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        } catch (exception: GlUtil.GlException) {
            throw VideoFrameProcessingException(exception)
        }
    }

    private companion object {
        private const val POSITION_COMPONENT_COUNT = 4
        private const val VERTEX_COUNT = 4
        private const val SHARPNESS_SCALE = 1.0f

        private const val VERTEX_SHADER = """
            #version 100
            attribute vec4 aFramePosition;
            uniform mat4 uTransformationMatrix;
            uniform mat4 uTexTransformationMatrix;
            varying vec2 vTexSamplingCoord;

            void main() {
              gl_Position = uTransformationMatrix * aFramePosition;
              vec4 texturePosition = vec4(aFramePosition.x * 0.5 + 0.5,
                                          aFramePosition.y * 0.5 + 0.5, 0.0, 1.0);
              vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #version 100
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform vec2 uTexelSize;
            uniform float uBrightness;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uHue;
            uniform float uGamma;
            uniform float uSharpness;
            uniform float uScaleX;
            uniform float uScaleY;
            uniform float uAmbienceEnabled;
            varying vec2 vTexSamplingCoord;

            float hash(vec2 p) {
              return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
            }

            vec3 rotateHue(vec3 color, float hue) {
              float angle = hue * 0.01745329252;
              float sine = sin(angle);
              float cosine = cos(angle);
              vec3 yiq = vec3(
                dot(color, vec3(0.299, 0.587, 0.114)),
                dot(color, vec3(0.596, -0.274, -0.322)),
                dot(color, vec3(0.211, -0.523, 0.312))
              );
              vec2 chroma = vec2(
                yiq.y * cosine - yiq.z * sine,
                yiq.y * sine + yiq.z * cosine
              );
              yiq = vec3(yiq.x, chroma.x, chroma.y);
              return vec3(
                dot(yiq, vec3(1.0, 0.956, 0.621)),
                dot(yiq, vec3(1.0, -0.272, -0.647)),
                dot(yiq, vec3(1.0, -1.106, 1.703))
              );
            }

            vec3 applyColorFilters(vec3 color) {
              float luma;

              if (uBrightness != 0.0) {
                color = clamp(color + vec3(uBrightness), 0.0, 1.0);
              }
              if (uContrast != 0.0) {
                color = clamp((color - vec3(0.5)) * (1.0 + uContrast) + vec3(0.5), 0.0, 1.0);
              }
              if (uHue != 0.0) {
                color = clamp(rotateHue(color, uHue), 0.0, 1.0);
              }
              if (uSaturation != 0.0) {
                luma = dot(color, vec3(0.299, 0.587, 0.114));
                color = clamp(mix(vec3(luma), color, 1.0 + uSaturation / 100.0), 0.0, 1.0);
              }
              if (uGamma != 1.0) {
                color = clamp(pow(color, vec3(1.0 / max(uGamma, 0.1))), 0.0, 1.0);
              }
              return color;
            }

            void main() {
              vec2 video_uv = (vTexSamplingCoord - 0.5) * vec2(uScaleX, uScaleY) + 0.5;
              vec4 color;

              if (uAmbienceEnabled > 0.5 && (video_uv.x < 0.0 || video_uv.x > 1.0 || video_uv.y < 0.0 || video_uv.y > 1.0)) {
                // Ambient region
                vec3 avg_color = vec3(0.0);
                const int samples = 20;
                float base_seed = 42.0;
                
                for (int i = 0; i < samples; i++) {
                  float seed = base_seed + float(i) * 0.618034;
                  float x = hash(vec2(seed, 0.123));
                  float y = hash(vec2(seed, 0.456));
                  avg_color += texture2D(uTexSampler, vec2(x, y)).rgb;
                }
                avg_color /= float(samples);

                float luma = dot(avg_color, vec3(0.2126, 0.7152, 0.0722));
                avg_color = mix(vec3(luma), avg_color, 1.3); // Saturation boost
                avg_color *= 0.30; // Brightness

                vec2 edge_uv = clamp(video_uv, 0.0, 1.0);
                float dist = length(video_uv - edge_uv);
                float fade = exp(-dist * 2.5);
                avg_color *= fade;

                float dither = hash(vTexSamplingCoord * 1000.0) * 0.004 - 0.002;
                color = vec4(clamp(avg_color + dither, 0.0, 1.0), 1.0);
              } else {
                vec2 sample_uv = clamp(video_uv, 0.0, 1.0);
                vec4 center = texture2D(uTexSampler, sample_uv);
                vec3 sourceColor = center.rgb;

                if (uSharpness > 0.0) {
                  vec3 north = texture2D(uTexSampler, sample_uv + vec2(0.0, -uTexelSize.y)).rgb;
                  vec3 south = texture2D(uTexSampler, sample_uv + vec2(0.0, uTexelSize.y)).rgb;
                  vec3 west = texture2D(uTexSampler, sample_uv + vec2(-uTexelSize.x, 0.0)).rgb;
                  vec3 east = texture2D(uTexSampler, sample_uv + vec2(uTexelSize.x, 0.0)).rgb;
                  vec3 northwest = texture2D(uTexSampler, sample_uv + vec2(-uTexelSize.x, -uTexelSize.y)).rgb;
                  vec3 northeast = texture2D(uTexSampler, sample_uv + vec2(uTexelSize.x, -uTexelSize.y)).rgb;
                  vec3 southwest = texture2D(uTexSampler, sample_uv + vec2(-uTexelSize.x, uTexelSize.y)).rgb;
                  vec3 southeast = texture2D(uTexSampler, sample_uv + vec2(uTexelSize.x, uTexelSize.y)).rgb;
                  vec3 blur = center.rgb * 0.25 + (north + south + west + east) * 0.125 + (northwest + northeast + southwest + southeast) * 0.0625;
                  sourceColor = clamp(center.rgb + (center.rgb - blur) * uSharpness, 0.0, 1.0);
                }
                color = vec4(applyColorFilters(sourceColor), center.a);
              }

              gl_FragColor = color;
            }
        """
    }
}
