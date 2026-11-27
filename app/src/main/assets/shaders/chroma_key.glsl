#extension GL_OES_EGL_image_external : require
precision highp float;
varying vec2 vTextureCoord;
uniform sampler2D sTexture;
uniform vec3 uKeyColor;
uniform float uThreshold;
uniform float uSoftness;
uniform float uEnabled;

void main() {
    vec4 source = texture2D(sTexture, vTextureCoord);
    float distanceToKey = distance(source.rgb, uKeyColor);
    float keyed = 1.0 - smoothstep(uThreshold, uThreshold + uSoftness, distanceToKey);
    float alpha = source.a * (1.0 - keyed * uEnabled);

    // Premultiplied alpha is required by Android TextureView composition.
    // Without it, RGB from transparent green pixels leaks into the preview.
    gl_FragColor = vec4(source.rgb * alpha, alpha);
}
