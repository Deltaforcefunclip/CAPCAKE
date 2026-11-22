precision highp float;
varying vec2 vTextureCoord;
uniform sampler2D sTexture;
uniform sampler2D uAiFlowTexture;
uniform vec2 uTexelSize;
uniform float uBlur;
uniform float uBlend;
uniform float uDirection;
uniform float uTimes;
uniform float uFlowReady;
uniform float uMaxFlowPixels;

void main() {
    vec4 current = texture2D(sTexture, vTextureCoord);
    if (uFlowReady < 0.5 || uBlur <= 0.001 || uBlend <= 0.001) {
        gl_FragColor = current;
        return;
    }

    vec2 encoded = texture2D(uAiFlowTexture, vTextureCoord).rg;
    vec2 flowPixels = (encoded - vec2(0.5)) * 2.0 * uMaxFlowPixels;
    float speed = length(flowPixels);
    if (speed < 0.45) {
        gl_FragColor = current;
        return;
    }

    vec2 direction = flowPixels / max(speed, 0.0001);
    float radius = min(speed * (0.8 + 3.8 * uBlur) * clamp(uTimes, 1.0, 6.0) * 1.35, 150.0);
    vec2 streak = direction * radius * uTexelSize;

    vec3 accumulated = vec3(0.0);
    float weightSum = 0.0;
    const int TAPS = 40;
    for (int i = 0; i < TAPS; i++) {
        float t = (float(i) + 0.5) / float(TAPS);
        float position;
        float weight;
        if (uDirection < 0.5) {
            position = t * 2.0 - 1.0;
            weight = exp(-1.8 * position * position);
        } else if (uDirection < 1.5) {
            position = -t;
            weight = exp(-1.25 * t);
        } else {
            position = t;
            weight = exp(-1.25 * t);
        }
        vec2 sampleUv = clamp(vTextureCoord + streak * position,
                              uTexelSize * 0.5, vec2(1.0) - uTexelSize * 0.5);
        accumulated += texture2D(sTexture, sampleUv).rgb * weight;
        weightSum += weight;
    }
    vec3 smear = accumulated / max(weightSum, 0.0001);
    float fastMotion = smoothstep(1.0, 10.0, speed);
    float detailSuppression = clamp(uBlur * (0.35 + fastMotion * 0.65), 0.0, 1.0);
    float strength = clamp(uBlend * detailSuppression, 0.0, 1.0);
    gl_FragColor = vec4(mix(current.rgb, smear, strength), current.a);
}
