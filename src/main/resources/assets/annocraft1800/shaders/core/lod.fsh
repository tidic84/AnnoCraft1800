#version 150

in vec4 vertexColor;
in vec3 viewPosition;

uniform vec4 ColorModulator;
uniform vec3 HazeColor;
uniform vec2 HazeRange;
uniform float Grade;
uniform float Dissolve;

out vec4 fragColor;

void main() {
    // A mesh being replaced: the new one keeps the pixels whose noise is under the progress (Dissolve > 0), the old
    // one the others (Dissolve < 0), so the region dissolves from one to the other without popping.
    if (Dissolve != 0.0) {
        float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
        if (Dissolve > 0.0 ? noise >= Dissolve : noise < -Dissolve) discard;
    }
    // The fade band over the real chunks is transparent where they show fully.
    if (vertexColor.a < 0.004) discard;
    vec3 color = vertexColor.rgb;
    // With a shader pack: richer colours and a soft filmic curve, closer to the packs' look than flat vanilla.
    if (Grade > 0.0) {
        float grey = dot(color, vec3(0.299, 0.587, 0.114));
        color = mix(vec3(grey), color, 1.0 + 0.1 * Grade);
        color = color * (1.0 + 0.2 * Grade) / (1.0 + 0.2 * Grade * color);
    }
    // Aerial perspective: the farther, the more of the sky's colour.
    float haze = smoothstep(HazeRange.x, HazeRange.y, length(viewPosition)) * 0.8;
    color = mix(color, HazeColor, haze);
    fragColor = vec4(color, vertexColor.a) * ColorModulator;
}
