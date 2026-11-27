#extension GL_OES_EGL_image_external : require
precision highp float;
varying vec2 vTextureCoord;
uniform sampler2D sTexture;
uniform vec3 uKeyColor;
uniform float uThreshold;
uniform float uSoftness;
uniform float uEnabled;
uniform float uBrightness;
uniform float uContrast;
uniform float uSaturation;

void main() {
    vec4 source = texture2D(sTexture, vTextureCoord);
    float distanceToKey = distance(source.rgb, uKeyColor);
    float keyed = 1.0 - smoothstep(uThreshold, uThreshold + uSoftness, distanceToKey);
    float alpha = source.a * (1.0 - keyed * uEnabled);

    // Color adjustment runs AFTER keying and never modifies the alpha channel.
    // Saturation 0 is neutral grayscale; saturation 1 preserves source color.
    float luminance = dot(source.rgb, vec3(0.213, 0.715, 0.072));
    vec3 adjusted = mix(vec3(luminance), source.rgb, uSaturation);
    adjusted = (adjusted - vec3(0.5)) * uContrast + vec3(0.5 + uBrightness);

    // Android TextureView composition uses premultiplied alpha.
    gl_FragColor = vec4(clamp(adjusted, 0.0, 1.0) * alpha, alpha);
}
