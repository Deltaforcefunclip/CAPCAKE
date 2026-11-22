precision highp float;
varying vec2 vTextureCoord;
uniform sampler2D sTexture;
uniform sampler2D uHistory0;
uniform sampler2D uHistory1;
uniform sampler2D uHistory2;
uniform sampler2D uHistory3;
uniform sampler2D uHistory4;
uniform sampler2D uHistory5;
uniform vec2 uTexelSize;
uniform float uBlur;
uniform float uBlend;
uniform float uDirection;
uniform float uTimes;
uniform float uHistoryCount;

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

// Estimate the displacement of a moving detail from the previous frame
// into the current frame. The returned vector is in pixel units.
// Lucas-Kanade flow with a texture-structure confidence score.
// Flat areas and single-direction edges are rejected: their motion estimate
// is ambiguous and was the main source of patchy/blotchy trails.
vec3 estimateFlow(vec2 uv, sampler2D previousFrame) {
    float xx=0.0, yy=0.0, xy=0.0, xt=0.0, yt=0.0;
    for (int y=-2; y<=2; y++) {
        for (int x=-2; x<=2; x++) {
            vec2 p=clamp(uv+vec2(float(x),float(y))*uTexelSize,
                         uTexelSize*2.0,vec2(1.0)-uTexelSize*2.0);
            float l=luma(texture2D(sTexture,p-vec2(uTexelSize.x,0.0)).rgb);
            float r=luma(texture2D(sTexture,p+vec2(uTexelSize.x,0.0)).rgb);
            float d=luma(texture2D(sTexture,p-vec2(0.0,uTexelSize.y)).rgb);
            float u=luma(texture2D(sTexture,p+vec2(0.0,uTexelSize.y)).rgb);
            float now=luma(texture2D(sTexture,p).rgb);
            float prev=luma(texture2D(previousFrame,p).rgb);
            float gx=(r-l)*0.5;
            float gy=(u-d)*0.5;
            float gt=now-prev;
            xx+=gx*gx;
            yy+=gy*gy;
            xy+=gx*gy;
            xt+=gx*gt;
            yt+=gy*gt;
        }
    }

    float trace=xx+yy;
    float discriminant=sqrt(max((xx-yy)*(xx-yy)+4.0*xy*xy,0.0));
    float minEigen=0.5*(trace-discriminant);
    float structureConfidence=smoothstep(0.00035,0.006,minEigen);

    float a=xx+0.00001;
    float c=yy+0.00001;
    float det=a*c-xy*xy;
    vec2 flow=vec2(0.0);
    if(det>1.0e-10) {
        flow.x=-(c*xt-xy*yt)/det;
        flow.y=-(-xy*xt+a*yt)/det;
    }
    float magnitude=length(flow);
    if(magnitude>28.0) flow*=28.0/magnitude;

    // Reject vectors that do not actually explain the temporal brightness
    // change at the warped location (occlusions / unrelated texture changes).
    vec2 warped=clamp(uv-flow*uTexelSize,0.0,1.0);
    float nowCenter=luma(texture2D(sTexture,uv).rgb);
    float previousWarped=luma(texture2D(previousFrame,warped).rgb);
    float residual=abs(nowCenter-previousWarped);
    float consistencyConfidence=1.0-smoothstep(0.035,0.18,residual);
    return vec3(flow,structureConfidence*consistencyConfidence);
}
vec4 historySample(float ageIndex, vec2 coord) {
    if(ageIndex<0.5) return texture2D(uHistory0,coord);
    if(ageIndex<1.5) return texture2D(uHistory1,coord);
    if(ageIndex<2.5) return texture2D(uHistory2,coord);
    if(ageIndex<3.5) return texture2D(uHistory3,coord);
    if(ageIndex<4.5) return texture2D(uHistory4,coord);
    return texture2D(uHistory5,coord);
}

void main() {
    vec4 current=texture2D(sTexture,vTextureCoord);
    if(uBlur<=0.001 || uBlend<=0.001 || uHistoryCount<1.0) {
        gl_FragColor=current;
        return;
    }

    // Use one stable adjacent-frame motion estimate. Mixing estimates from
    // different temporal baselines made the trail length inconsistent.
    vec3 flowEstimate=estimateFlow(vTextureCoord,uHistory0);
    vec2 flow=flowEstimate.xy;
    float flowLength=length(flow);
    float confidence=flowEstimate.z*smoothstep(0.025,0.65,flowLength);
    if(confidence<=0.001) {
        gl_FragColor=current;
        return;
    }

    vec2 motionUv=flow*uTexelSize;
    // Fast motion should lose fine detail into a continuous streak rather
    // than retaining a crisp copy of the moving object.
    float fastMotion=smoothstep(0.8,5.0,flowLength);
    float trailScale=uBlur*(2.5+3.5*fastMotion);
    float currentWeight=mix(1.0,0.42,fastMotion);
    vec3 accumulated=current.rgb*currentWeight;
    float totalWeight=currentWeight;

    // Each sample uses the matching real historical frame. Samples are
    // weighted toward the present to avoid a detached, translucent duplicate.
    for(int i=0;i<6;i++) {
        float age=float(i+1);
        if(age<=uTimes && age<=uHistoryCount) {
            float ageWeight=(1.0/(1.0+0.10*float(i)))*(1.0+0.35*fastMotion);
            vec2 backwardCoord=clamp(vTextureCoord-motionUv*age*trailScale,0.0,1.0);
            vec2 forwardCoord=clamp(vTextureCoord+motionUv*age*trailScale,0.0,1.0);
            vec3 sampleColor;

            if(uDirection<0.5) {
                vec3 backColor=historySample(age-1.0,backwardCoord).rgb;
                vec3 frontColor=historySample(age-1.0,forwardCoord).rgb;
                sampleColor=(backColor+frontColor)*0.5;
            } else if(uDirection<1.5) {
                sampleColor=historySample(age-1.0,backwardCoord).rgb;
            } else {
                // A forward-facing streak is synthesized from the previous
                // frame by sampling the opposite side of the motion vector.
                sampleColor=historySample(age-1.0,forwardCoord).rgb;
            }

            accumulated+=sampleColor*ageWeight;
            totalWeight+=ageWeight;
        }
    }

    vec3 temporalBlur=accumulated/totalWeight;
    float strength=clamp(uBlend*confidence*(1.0+0.55*fastMotion),0.0,1.0);
    gl_FragColor=vec4(mix(current.rgb,temporalBlur,strength),current.a);
}
