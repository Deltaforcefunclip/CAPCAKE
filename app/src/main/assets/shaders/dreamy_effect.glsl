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

    // Mode 1: Black & White — dipertahankan seperti versi stabil.
    if (uEffectMode > 0.5 && uEffectMode < 1.5) {
        float luminance = dot(original.rgb, vec3(0.2126, 0.7152, 0.0722));
        float monochrome = clamp((luminance - 0.5) * 1.12 + 0.5, 0.0, 1.0);
        gl_FragColor = vec4(mix(original.rgb, vec3(monochrome), clamp(uIntensity, 0.0, 1.0)), original.a);
        return;
    }

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

    float strength = clamp(uIntensity, 0.0, 1.0);
    float focus = clamp(uSoftFocus, 0.0, 1.0);

    // Mode 2: Cahaya Lembut. Diffusion veil yang menyatukan cahaya
    // dengan detail asli, bukan sekadar memberi tint warna.
    if (uEffectMode > 1.5) {
        vec3 softBase = mix(original.rgb, blurred, focus * 0.72);
        vec3 brightSource = max(blurred - vec3(0.28), vec3(0.0));
        brightSource = brightSource * brightSource * 2.0;
        vec3 halo = screenBlend(softBase, brightSource * (0.45 + strength * 1.8));

        // Lapisan cahaya putih lembut, dengan sedikit kendali suhu warna.
        vec3 lightTint = mix(vec3(1.0, 0.91, 0.78), vec3(0.86, 0.93, 1.0), 1.0 - uWarmth);
        vec3 veil = mix(blurred, lightTint, 0.28 + focus * 0.18);
        vec3 result = mix(softBase, halo, 0.25 + strength * 0.48);
        result = screenBlend(result, veil * (0.10 + strength * 0.24));
        result = mix(result, vec3(1.0), strength * focus * 0.055);
        gl_FragColor = vec4(clamp(mix(original.rgb, result, strength), 0.0, 1.0), original.a);
        return;
    }

    // Mode 0: Dreamy lama — tetap tersedia tanpa mengubah karakter B&W.
    vec3 softImage = mix(original.rgb, blurred, focus);
    vec3 highlights = max(blurred - vec3(0.42), vec3(0.0));
    highlights = highlights * highlights * 2.4;
    vec3 bloom = highlights * (0.65 + strength * 2.2);
    vec3 dreamy = screenBlend(softImage, bloom * strength);
    vec3 halation = max(original.rgb - vec3(0.72), vec3(0.0));
    dreamy += halation * vec3(0.10 + 0.16 * uWarmth, 0.07 + 0.08 * uWarmth, 0.10);
    dreamy.r += uWarmth * 0.10 * strength;
    dreamy.g += uWarmth * 0.045 * strength;
    dreamy.b += (1.0 - uWarmth) * 0.035 * strength;
    dreamy = mix(dreamy, vec3(0.52), 0.035 * strength);
    vec2 centered = vTextureCoord - vec2(0.5);
    float vignette = 1.0 - dot(centered, centered) * 0.16 * strength;
    dreamy *= clamp(vignette, 0.86, 1.0);
    gl_FragColor = vec4(clamp(mix(original.rgb, dreamy, strength), 0.0, 1.0), original.a);
}