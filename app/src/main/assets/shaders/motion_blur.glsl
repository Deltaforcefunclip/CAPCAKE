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
    if (uIntensity <= 0.001 || uHasHistory < 0.5) {
        gl_FragColor = current;
        return;
    }

    // Dense local Lucas-Kanade: kumpulkan gradien dari jendela 5x5
    // agar estimasi arah gerak lebih stabil daripada satu piksel/3x3.
    float xx = 0.0;
    float yy = 0.0;
    float xy = 0.0;
    float xt = 0.0;
    float yt = 0.0;

    for (int y = -2; y <= 2; y++) {
        for (int x = -2; x <= 2; x++) {
            vec2 p = clamp(vTextureCoord + vec2(float(x), float(y)) * uTexelSize,
                           uTexelSize * 2.0, vec2(1.0) - uTexelSize * 2.0);
            float left  = luma(texture2D(sTexture, p - vec2(uTexelSize.x, 0.0)).rgb);
            float right = luma(texture2D(sTexture, p + vec2(uTexelSize.x, 0.0)).rgb);
            float down  = luma(texture2D(sTexture, p - vec2(0.0, uTexelSize.y)).rgb);
            float up    = luma(texture2D(sTexture, p + vec2(0.0, uTexelSize.y)).rgb);
            float now   = luma(texture2D(sTexture, p).rgb);
            float prev  = luma(texture2D(uPreviousTexture, p).rgb);

            float gx = (right - left) * 0.5;
            float gy = (up - down) * 0.5;
            float gt = now - prev;

            xx += gx * gx;
            yy += gy * gy;
            xy += gx * gy;
            xt += gx * gt;
            yt += gy * gt;
        }
    }

    // Regularisasi kecil mencegah pembagian tidak stabil pada area minim tekstur.
    float lambda = 0.00002;
    float a = xx + lambda;
    float c = yy + lambda;
    float determinant = a * c - xy * xy;

    vec2 flowPixels = vec2(0.0);
    if (determinant > 0.0000000001) {
        flowPixels.x = -(c * xt - xy * yt) / determinant;
        flowPixels.y = -(-xy * xt + a * yt) / determinant;
    }

    float flowLength = length(flowPixels);
    if (flowLength > 12.0) {
        flowPixels *= 12.0 / flowLength;
        flowLength = 12.0;
    }

    // Keyakinan berdasarkan detail tekstur lokal dan besarnya perpindahan.
    float trace = xx + yy;
    float discriminant = sqrt(max((xx - yy) * (xx - yy) + 4.0 * xy * xy, 0.0));
    float minEigen = 0.5 * (trace - discriminant);
    float textureConfidence = smoothstep(0.00002, 0.0018, minEigen);
    float motionConfidence = smoothstep(0.04, 0.30, flowLength);
    float confidence = textureConfidence * motionConfidence;

    // Vektor optical-flow dalam piksel diubah menjadi koordinat UV.
    // Sampel hanya dari frame saat ini: tidak menimbulkan ghosting/frame ganda.
    vec2 blurVector = flowPixels * uTexelSize * (0.35 + 1.65 * uIntensity) * uIntensity;
    vec3 blurred = current.rgb * 0.14;
    blurred += texture2D(sTexture, clamp(vTextureCoord - blurVector * 0.50, 0.0, 1.0)).rgb * 0.12;
    blurred += texture2D(sTexture, clamp(vTextureCoord - blurVector * 0.33, 0.0, 1.0)).rgb * 0.12;
    blurred += texture2D(sTexture, clamp(vTextureCoord - blurVector * 0.16, 0.0, 1.0)).rgb * 0.12;
    blurred += texture2D(sTexture, clamp(vTextureCoord + blurVector * 0.16, 0.0, 1.0)).rgb * 0.12;
    blurred += texture2D(sTexture, clamp(vTextureCoord + blurVector * 0.33, 0.0, 1.0)).rgb * 0.12;
    blurred += texture2D(sTexture, clamp(vTextureCoord + blurVector * 0.50, 0.0, 1.0)).rgb * 0.14;

    gl_FragColor = vec4(mix(current.rgb, blurred, confidence), current.a);
}
