package me.timschneeberger.rootlessjamesdsp.ui.glass

import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi

/** A rounded convex lens over the shared light field. Foreground content is never sampled. */
internal object GlassLensShader {
    fun create(): Shader? = if (Build.VERSION.SDK_INT >= 33) {
        // Devices with a restricted shader implementation retain the Canvas glass renderer.
        runCatching { Api33.create() }.getOrNull()
    } else null

    fun configure(shader: Shader, scene: Shader, rect: RectF, radius: Float, density: Float,
                  night: Boolean, role: GlassThemeUi.Role, settings: Glass3Preferences.Snapshot? = null) {
        if (Build.VERSION.SDK_INT >= 33) {
            Api33.configure(shader as RuntimeShader, scene, rect, radius, density, night, role, settings)
        }
    }

    @RequiresApi(33)
    private object Api33 {
        fun create() = RuntimeShader("""
            uniform shader scene;
            uniform float4 panel;
            uniform float corner;
            uniform float unit;
            uniform float darkness;
            uniform float header;
            uniform float dialog;
            uniform float refractionAmount;
            uniform float highlightAmount;
            uniform float opacityAmount;
            uniform float neutralMaterial;

            half4 main(float2 p) {
                float2 center = (panel.xy + panel.zw) * 0.5;
                float2 halfSize = (panel.zw - panel.xy) * 0.5;
                float2 delta = p - center;
                float2 q = abs(delta) - halfSize + corner;
                float2 outside = max(q, 0.0);
                float outsideLength = length(outside);
                float signedDistance = outsideLength + min(max(q.x, q.y), 0.0) - corner;
                float depth = max(-signedDistance, 0.0);
                float2 normal;
                if (outsideLength > 0.001) {
                    normal = outside / outsideLength * sign(delta);
                } else {
                    normal = q.x > q.y ? float2(sign(delta.x), 0.0) : float2(0.0, sign(delta.y));
                }
                float bevel = min(9.0 * unit, min(halfSize.x, halfSize.y) * 0.75);
                float edge = 1.0 - smoothstep(0.0, max(bevel, 0.5), depth);
                // Curved normals increase displacement near the perimeter, including corners.
                float lens = edge * edge * (1.0 - 0.28 * edge);
                // A restrained effective index bends the background near curved edges.
                float opticalIndex = 1.32;
                float2 optical = p - delta * 0.016 * refractionAmount + normal * bevel * lens * (opticalIndex - 1.0) * 2.25 * refractionAmount;
                float dispersion = 0.32 * unit * lens * refractionAmount;
                half4 sampleColor = scene.eval(optical);
                float3 color = float3(scene.eval(optical + normal * dispersion).r,
                    sampleColor.g, scene.eval(optical - normal * dispersion).b);

                float wash = mix(0.25, 0.24, darkness) + dialog * 0.07 - header * 0.11;
                float3 materialTint = mix(float3(0.985, 0.995, 1.0), float3(0.055, 0.085, 0.14), darkness);
                materialTint = mix(materialTint, mix(float3(1.0), float3(0.09), darkness), neutralMaterial);
                color = mix(color, materialTint, wash);
                float light = max(dot(normal, normalize(float2(-0.52, -0.85))), 0.0);
                float shade = max(dot(normal, normalize(float2(0.55, 0.83))), 0.0);
                color += float3(0.88, 0.95, 1.0) * edge * edge * light * 0.13 * highlightAmount;
                color *= 1.0 - edge * shade * 0.11 * highlightAmount;
                float rim = 1.0 - smoothstep(0.0, 1.15 * unit, depth);
                float caustic = exp(-pow((depth - 2.3 * unit) / (1.0 * unit), 2.0));
                color = mix(color, float3(1.0), clamp(rim * (0.24 + light * 0.43) * highlightAmount, 0.0, 1.0));
                color += float3(0.66, 0.82, 1.0) * caustic * shade * 0.14 * highlightAmount;
                float reflected = exp(-pow((p.y - panel.y - 0.8 * unit) / (2.2 * unit), 2.0));
                color += float3(1.0) * reflected * 0.08 * highlightAmount;
                float opacity = mix(0.88, 0.92, darkness);
                opacity = mix(opacity, mix(0.72, 0.82, darkness), dialog);
                opacity = mix(opacity, mix(0.42, 0.56, darkness), header);
                if (opacityAmount >= 0.0) opacity = opacityAmount * mix(1.0, 0.58, header);
                opacity = mix(opacity, 0.98, clamp(rim * 0.72 * highlightAmount, 0.0, 1.0));
                return half4(clamp(color, 0.0, 1.0) * opacity, opacity);
            }
        """.trimIndent())

        fun configure(shader: RuntimeShader, scene: Shader, rect: RectF, radius: Float,
                      density: Float, night: Boolean, role: GlassThemeUi.Role, settings: Glass3Preferences.Snapshot?) {
            shader.setInputShader("scene", scene)
            shader.setFloatUniform("panel", rect.left, rect.top, rect.right, rect.bottom)
            shader.setFloatUniform("corner", radius)
            shader.setFloatUniform("unit", density)
            shader.setFloatUniform("darkness", if (night) 1f else 0f)
            shader.setFloatUniform("header", if (role == GlassThemeUi.Role.HEADER) 1f else 0f)
            shader.setFloatUniform("dialog", if (role == GlassThemeUi.Role.DIALOG) 1f else 0f)
            shader.setFloatUniform("refractionAmount", settings?.refraction ?: 1f)
            shader.setFloatUniform("highlightAmount", settings?.highlight ?: 1f)
            shader.setFloatUniform("opacityAmount", settings?.opacity ?: -1f)
            shader.setFloatUniform("neutralMaterial", if (settings != null && !settings.colorfulBackground) 1f else 0f)
        }
    }
}
