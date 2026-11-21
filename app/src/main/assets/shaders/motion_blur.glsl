precision highp float;
varying vec2 vTextureCoord;
uniform sampler2D sTexture;
uniform sampler2D uPreviousTexture;
uniform vec2 uTexelSize;
uniform float uIntensity;
uniform float uHasHistory;

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec4 current = texture2D(sTexture, vTextureCoord);
    if (uHasHistory < 0.5 || uIntensity <= 0.001) {
        gl_FragColor = current;
        return;
    }

    // Estimasi Lucas-Kanade lokal dari frame saat ini dan frame sebelumnya.
    float xx = 0.0;
    float yy = 0.0;
    float xy = 0.0;
    float xt = 0.0;
    float yt = 0.0;
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
            xx += gx * gx;
            yy += gy * gy;
            xy += gx * gy;
            xt += gx * gt;
            yt += gy * gt;
        }
    }

    float determinant = xx * yy - xy * xy;
    float trace = xx + yy;
    vec2 flowPixels = vec2(0.0);
    if (determinant > 0.00000001) {
        flowPixels.x = -(yy * xt - xy * yt) / determinant;
        flowPixels.y = -(-xy * xt + xx * yt) / determinant;
    }

    // Koherensi struktur menghindari aperture problem pada tepi lurus,
    // sementara ambang energi yang lebih rendah menjaga gerak nyata tetap terbaca.
    float coherence = determinant / max(trace * trace, 0.00000001);
    float confidence = smoothstep(0.004, 0.075, coherence) *
                       smoothstep(0.00015, 0.012, trace);

    float flowLength = length(flowPixels);
    if (flowLength > 6.0) flowPixels *= 6.0 / flowLength;

    // Lucas-Kanade menghasilkan perpindahan dalam satuan piksel.
    // Konversi ke UV dilakukan sebelum sampling; panjang sapuan setara
    // shutter blur yang dapat ditingkatkan melalui slider intensitas.
    vec2 flowUv = flowPixels * uTexelSize;
    vec2 blurVector = flowUv * (2.0 + 6.0 * uIntensity) * uIntensity;

    vec3 blurred = current.rgb * 0.20;
    blurred += texture2D(sTexture, clamp(vTextureCoord - blurVector * 0.50, 0.0, 1.0)).rgb * 0.20;
    blurred += texture2D(sTexture, clamp(vTextureCoord - blurVector * 0.25, 0.0, 1.0)).rgb * 0.20;
    blurred += texture2D(sTexture, clamp(vTextureCoord + blurVector * 0.25, 0.0, 1.0)).rgb * 0.20;
    blurred += texture2D(sTexture, clamp(vTextureCoord + blurVector * 0.50, 0.0, 1.0)).rgb * 0.20;

    gl_FragColor = vec4(mix(current.rgb, blurred, confidence * uIntensity), current.a);
}
