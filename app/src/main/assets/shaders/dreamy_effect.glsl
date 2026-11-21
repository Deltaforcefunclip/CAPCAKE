precision highp float;

varying vec2 vTextureCoord;
uniform sampler2D sTexture;
uniform float uIntensity;
uniform float uBlurRadius;
uniform float uWarmth;
uniform float uEffectMode;
uniform float uSoftFocus;

vec3 screenBlend(vec3 baseColor, vec3 glowColor) {
    return 1.0 - ((1.0 - baseColor) * (1.0 - glowColor));
}

void main() {
    vec4 original = texture2D(sTexture, vTextureCoord);

    if (uEffectMode > 0.5) {
        float luminance = dot(original.rgb, vec3(0.2126, 0.7152, 0.0722));
        float monochrome = clamp((luminance - 0.5) * 1.12 + 0.5, 0.0, 1.0);
        gl_FragColor = vec4(mix(original.rgb, vec3(monochrome), clamp(uIntensity, 0.0, 1.0)), original.a);
        return;
    }

    // 9-tap Gaussian-like blur. Radius scales with Glow Spread.
    vec2 r = vec2(max(uBlurRadius, 0.0005));
    vec3 blurred = original.rgb * 0.25;
    blurred += texture2D(sTexture, clamp(vTextureCoord + vec2( r.x, 0.0), 0.0, 1.0)).rgb * 0.125;
    blurred += texture2D(sTexture, clamp(vTextureCoord + vec2(-r.x, 0.0), 0.0, 1.0)).rgb * 0.125;
    blurred += texture2D(sTexture, clamp(vTextureCoord + vec2(0.0,  r.y), 0.0, 1.0)).rgb * 0.125;
    blurred += texture2D(sTexture, clamp(vTextureCoord + vec2(0.0, -r.y), 0.0, 1.0)).rgb * 0.125;
    blurred += texture2D(sTexture, clamp(vTextureCoord + r, 0.0, 1.0)).rgb * 0.0625;
    blurred += texture2D(sTexture, clamp(vTextureCoord - r, 0.0, 1.0)).rgb * 0.0625;
    blurred += texture2D(sTexture, clamp(vTextureCoord + vec2(r.x, -r.y), 0.0, 1.0)).rgb * 0.0625;
    blurred += texture2D(sTexture, clamp(vTextureCoord + vec2(-r.x, r.y), 0.0, 1.0)).rgb * 0.0625;

    // Soft-focus is independently adjustable: it blends the blurred image
    // back into the original while preserving the main exposure.
    vec3 softImage = mix(original.rgb, blurred, clamp(uSoftFocus, 0.0, 1.0));

    // Extract bright regions from the blurred image to create a visible bloom.
    vec3 highlights = max(blurred - vec3(0.42), vec3(0.0));
    highlights = highlights * highlights * 2.4;
    vec3 bloom = highlights * (0.65 + uIntensity * 2.2);
    vec3 dreamy = screenBlend(softImage, bloom * uIntensity);

    // Gentle highlight halation and warm/cool tone control.
    vec3 halation = max(original.rgb - vec3(0.72), vec3(0.0));
    dreamy += halation * vec3(0.10 + 0.16 * uWarmth, 0.07 + 0.08 * uWarmth, 0.10);
    dreamy.r += uWarmth * 0.10 * uIntensity;
    dreamy.g += uWarmth * 0.045 * uIntensity;
    dreamy.b += (1.0 - uWarmth) * 0.035 * uIntensity;

    // Soft highlight lift and restrained vignette keep the image cinematic.
    dreamy = mix(dreamy, vec3(0.52), 0.035 * uIntensity);
    vec2 centered = vTextureCoord - vec2(0.5);
    float vignette = 1.0 - dot(centered, centered) * 0.16 * uIntensity;
    dreamy *= clamp(vignette, 0.86, 1.0);

    gl_FragColor = vec4(clamp(mix(original.rgb, dreamy, uIntensity), 0.0, 1.0), original.a);
}
