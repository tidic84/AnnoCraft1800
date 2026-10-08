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

out vec4 vertexColor;
out vec3 viewPosition;

void main() {
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * view;
    viewPosition = view.xyz;
    // Sun on the faces turned to it, sky light from above, a little bounce under overhangs.
    float sun = max(dot(Normal, normalize(SunDir)), 0.0);
    float sky = 0.6 + 0.4 * Normal.y;
    vec3 light = SunColor * sun * 0.85 + SkyLight * sky + vec3(0.1);
    vertexColor = vec4(Color.rgb * light, Color.a);
}
