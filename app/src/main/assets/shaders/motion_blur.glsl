precision highp float;
varying vec2 vTextureCoord;
uniform sampler2D sTexture;
uniform sampler2D uPreviousTexture;
uniform vec2 uTexelSize;
uniform float uIntensity;
uniform float uHasHistory;

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

void main() {
    vec4 current = texture2D(sTexture, vTextureCoord);
    if (uHasHistory < 0.5 || uIntensity <= 0.001) {
        gl_FragColor = current;
        return;
    }
    float xx = 0.0, yy = 0.0, xy = 0.0, xt = 0.0, yt = 0.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 p = vTextureCoord + vec2(float(x), float(y)) * uTexelSize;
            float left = luma(texture2D(sTexture, p - vec2(uTexelSize.x, 0.0)).rgb);
            float right = luma(texture2D(sTexture, p + vec2(uTexelSize.x, 0.0)).rgb);
            float down = luma(texture2D(sTexture, p - vec2(0.0, uTexelSize.y)).rgb);
            float up = luma(texture2D(sTexture, p + vec2(0.0, uTexelSize.y)).rgb);
            float now = luma(texture2D(sTexture, p).rgb);
            float before = luma(texture2D(uPreviousTexture, p).rgb);
            float gx = (right - left) * 0.5;
            float gy = (up - down) * 0.5;
            float gt = now - before;
            xx += gx * gx; yy += gy * gy; xy += gx * gy;
            xt += gx * gt; yt += gy * gt;
        }
    }
    float determinant = xx * yy - xy * xy;
    vec2 flow = vec2(0.0);
    if (determinant > 0.0000005) {
        flow.x = -(yy * xt - xy * yt) / determinant;
        flow.y = -(-xy * xt + xx * yt) / determinant;
    }
    float confidence = smoothstep(0.0000005, 0.00008, determinant);
    flow *= confidence;
    float flowLength = length(flow);
    if (flowLength > 0.035) flow *= 0.035 / flowLength;
    vec2 blurVector = flow * (0.55 + 1.45 * uIntensity) * uIntensity;
    vec3 blurred = current.rgb * 0.24;
    blurred += texture2D(sTexture, clamp(vTextureCoord - blurVector * 0.50, 0.0, 1.0)).rgb * 0.19;
    blurred += texture2D(sTexture, clamp(vTextureCoord - blurVector * 0.25, 0.0, 1.0)).rgb * 0.19;
    blurred += texture2D(sTexture, clamp(vTextureCoord + blurVector * 0.25, 0.0, 1.0)).rgb * 0.19;
    blurred += texture2D(sTexture, clamp(vTextureCoord + blurVector * 0.50, 0.0, 1.0)).rgb * 0.19;
    gl_FragColor = vec4(mix(current.rgb, blurred, confidence * uIntensity), current.a);
}
