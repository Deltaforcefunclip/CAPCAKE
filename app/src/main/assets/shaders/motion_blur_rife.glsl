precision highp float;
varying vec2 vTextureCoord;
uniform sampler2D sTexture;
uniform sampler2D uRifeBlurTexture;
uniform float uBlend;
uniform float uReady;
void main() {
    vec4 original = texture2D(sTexture, vTextureCoord);
    if (uReady < 0.5 || uBlend <= 0.001) {
        gl_FragColor = original;
        return;
    }
    vec4 temporal = texture2D(uRifeBlurTexture, vTextureCoord);
    gl_FragColor = vec4(mix(original.rgb, temporal.rgb, clamp(uBlend, 0.0, 1.0)), original.a);
}
