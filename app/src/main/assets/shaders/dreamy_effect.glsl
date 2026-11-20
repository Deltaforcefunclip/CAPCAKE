precision highp float;

varying vec2 vTextureCoord;
uniform sampler2D sTexture;
uniform float uIntensity;
uniform float uBlurRadius;
uniform float uWarmth;

vec3 screenBlend(vec3 baseColor, vec3 glowColor) {
    return 1.0 - ((1.0 - baseColor) * (1.0 - glowColor));
}

void main() {
    vec4 original = texture2D(sTexture, vTextureCoord);
    // Radius is expressed in normalized texture coordinates, not texel units.
    // Multiplying by texel size here made the blur sub-pixel and practically invisible.
    vec2 stepSize = vec2(max(uBlurRadius, 0.0));

    // Compact 9-tap cross blur. The blur is intentionally bounded so the
    // first GPU integration remains suitable for mobile preview/export.
    vec3 blurColor = original.rgb * 0.20;
    blurColor += texture2D(sTexture, clamp(vTextureCoord + vec2( stepSize.x, 0.0), 0.0, 1.0)).rgb * 0.12;
    blurColor += texture2D(sTexture, clamp(vTextureCoord + vec2(-stepSize.x, 0.0), 0.0, 1.0)).rgb * 0.12;
    blurColor += texture2D(sTexture, clamp(vTextureCoord + vec2(0.0,  stepSize.y), 0.0, 1.0)).rgb * 0.12;
    blurColor += texture2D(sTexture, clamp(vTextureCoord + vec2(0.0, -stepSize.y), 0.0, 1.0)).rgb * 0.12;
    blurColor += texture2D(sTexture, clamp(vTextureCoord + stepSize, 0.0, 1.0)).rgb * 0.075;
    blurColor += texture2D(sTexture, clamp(vTextureCoord - stepSize, 0.0, 1.0)).rgb * 0.075;
    blurColor += texture2D(sTexture, clamp(vTextureCoord + vec2(stepSize.x, -stepSize.y), 0.0, 1.0)).rgb * 0.075;
    blurColor += texture2D(sTexture, clamp(vTextureCoord + vec2(-stepSize.x, stepSize.y), 0.0, 1.0)).rgb * 0.075;

    vec3 highlights = max(blurColor - vec3(0.58), vec3(0.0));
    vec3 bloom = highlights * (1.0 + uIntensity * 1.8);
    vec3 dreamy = screenBlend(original.rgb, bloom * uIntensity);

    // Lift shadows gently and shift the glow between cool and warm tones.
    dreamy = mix(dreamy, vec3(0.5), 0.055 * uIntensity);
    dreamy.r += uWarmth * 0.12 * uIntensity;
    dreamy.g += uWarmth * 0.055 * uIntensity;
    dreamy.b += (1.0 - uWarmth) * 0.025 * uIntensity;

    vec2 centered = vTextureCoord - vec2(0.5);
    float vignette = 1.0 - dot(centered, centered) * 0.22 * uIntensity;
    dreamy *= clamp(vignette, 0.82, 1.0);

    gl_FragColor = vec4(clamp(mix(original.rgb, dreamy, uIntensity), 0.0, 1.0), original.a);
}