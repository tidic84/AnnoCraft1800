#version 150

// Distant view of AnnoCraft1800: terrain columns lit by the sun and the sky, fading into the sky's haze.
in vec3 Position;
in vec4 Color;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 SunDir;
uniform vec3 SunColor;
uniform vec3 SkyLight;
uniform float Vanilla;
uniform float Daylight;

out vec4 vertexColor;
out vec3 viewPosition;

void main() {
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * view;
    viewPosition = view.xyz;
    vec3 light;
    if (Vanilla > 0.0) {
        // Without shaders: exactly Minecraft's own shading, so the distant view matches the real blocks next to it:
        // full light on top, 0.8 on north and south faces, 0.6 on east and west, 0.5 underneath, dimmed at night.
        float face = Normal.y > 0.5 ? 1.0 : Normal.y < -0.5 ? 0.5 : abs(Normal.z) > 0.5 ? 0.8 : 0.6;
        light = vec3(face * Daylight);
    } else {
        // Under shaders: sun on the faces turned to it, sky light from above, a little bounce under overhangs.
        float sun = max(dot(Normal, normalize(SunDir)), 0.0);
        float sky = 0.6 + 0.4 * Normal.y;
        light = SunColor * sun * 0.85 + SkyLight * sky + vec3(0.1);
    }
    vertexColor = vec4(Color.rgb * light, Color.a);
}
