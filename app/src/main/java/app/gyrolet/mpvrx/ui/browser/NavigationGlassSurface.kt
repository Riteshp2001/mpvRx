package app.gyrolet.mpvrx.ui.browser

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import app.gyrolet.mpvrx.ui.liquidglass.connectedGlassRim
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

@Composable
internal fun NavigationGlassSurface(
  backdrop: HazeState?,
  surfaceColor: Color,
  glowStrength: Float,
  modifier: Modifier = Modifier,
  shape: Shape = CircleShape,
  connectionProgress: Float = 0f,
) {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
    Box(
      modifier.clip(shape)
        .background(surfaceColor.copy(alpha = 1f))
        .navigationGlassRim(glowStrength, shape, connectionProgress),
    )
    return
  }

  val blurStyle = remember(surfaceColor) {
    HazeBlurStyle {
      blurRadius(24.dp)
      backgroundColor(surfaceColor)
      colorEffects(listOf(HazeColorEffect.tint(Color.Transparent)))
      noiseFactor(0f)
    }
  }
  val backing = if (backdrop != null) Modifier.hazeBlur(input = HazeInput.Sources(backdrop), style = blurStyle) else Modifier
  if (shape == CircleShape && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backdrop != null && glowStrength > 0f) {
    RefractedNavigationGlass(surfaceColor, glowStrength, modifier, backing)
  } else {
    Box(modifier.clip(shape).then(backing).background(surfaceColor.copy(alpha = if (backdrop != null) 0.55f else 0.82f))
      .navigationGlassRim(glowStrength, shape, connectionProgress))
  }
}

internal fun Modifier.navigationGlassRim(
  strength: Float = 1f,
  shape: Shape = CircleShape,
  connectionProgress: Float = 0f,
): Modifier = connectedGlassRim(shape, joinedAtTop = true, strength = strength, connectionProgress = connectionProgress)

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun RefractedNavigationGlass(surfaceColor: Color, glowStrength: Float, modifier: Modifier, backing: Modifier) {
  val shader = remember { RuntimeShader(NavigationGlassShader) }
  Box(modifier
    .layout { measurable, constraints ->
      val outset = 24.dp.roundToPx()
      val placeable = measurable.measure(constraints.offset(outset * 2, outset * 2))
      layout(placeable.width - outset * 2, placeable.height - outset * 2) {
        placeable.place(-outset, -outset)
      }
    }
    .graphicsLayer {
      shader.setFloatUniform("resolution", size.width, size.height)
      shader.setFloatUniform("density", density)
      shader.setFloatUniform("outset", 24.dp.roundToPx().toFloat())
      shader.setFloatUniform("glowStrength", glowStrength)
      shader.setFloatUniform("surfaceTint", surfaceColor.red, surfaceColor.green, surfaceColor.blue)
      renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "backdrop").asComposeRenderEffect()
    }
    .then(backing))
}

private const val NavigationGlassShader = """
uniform shader backdrop;
uniform float2 resolution;
uniform float density;
uniform float outset;
uniform float glowStrength;
uniform float3 surfaceTint;

half3 sampleLight(float2 position, float2 tangent) {
    float2 spread = tangent * density * 10.0;
    return backdrop.eval(position).rgb * 0.5
        + backdrop.eval(position - spread).rgb * 0.25
        + backdrop.eval(position + spread).rgb * 0.25;
}

half4 main(float2 position) {
    float2 halfSize = resolution * 0.5 - outset;
    float radius = halfSize.y;
    float2 local = position - resolution * 0.5;
    float2 capsule = float2(max(abs(local.x) - halfSize.x + radius, 0.0), local.y);
    float distanceToCenter = length(capsule);
    float distanceToEdge = distanceToCenter - radius;
    float coverage = 1.0 - smoothstep(-0.5, 0.5, distanceToEdge);
    if (coverage <= 0.0) return half4(0.0);

    float2 normal = float2(capsule.x * sign(local.x), capsule.y) / max(distanceToCenter, 0.001);
    float2 tangent = float2(-normal.y, normal.x);
    float depth = max(-distanceToEdge, 0.0) / density;
    half3 surface = mix(backdrop.eval(position).rgb, surfaceTint, 0.55);
    if (depth >= 16.0 || glowStrength <= 0.0) return half4(surface * coverage, coverage);

    float rim = exp(-0.0565 * depth - 0.0322 * depth * depth);
    float upperLight = 0.18 + 0.82 * pow(max(-normal.y, 0.0), 0.65);
    float bend = pow(rim, 0.18);
    half3 redLight = sampleLight(position - normal * density * 16.0 * bend, tangent);
    half3 greenLight = sampleLight(position - normal * density * 62.0 * bend, tangent);
    half3 blueLight = sampleLight(position - normal * density * 57.0 * bend, tangent);
    half3 refracted = half3(redLight.r, greenLight.g, blueLight.b);
    half luminance = dot(refracted, half3(0.2126, 0.7152, 0.0722));
    refracted = clamp(mix(half3(luminance), refracted, 1.25), 0.0, 1.0);

    half3 sheen = half3(0.1735, 0.0529, 0.0184) + refracted * half3(0.0953, 0.3152, 0.3822);
    half3 color = surface + sheen * rim * upperLight * glowStrength;
    float highlight = exp(-pow((depth - 0.35) / 0.42, 2.0));
    float highlightLight = 0.12 + 0.88 * sqrt(max((1.0 - normal.y) * 0.5, 0.0));
    color += half3(0.25) * highlight * highlightLight * glowStrength;
    return half4(clamp(color, 0.0, 1.0) * coverage, coverage);
}
"""