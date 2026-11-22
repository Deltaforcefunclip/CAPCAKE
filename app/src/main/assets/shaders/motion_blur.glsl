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
// One Lucas-Kanade refinement level. stepPixels controls the spacing
// between gradient samples; initialFlow warps the previous frame before
// solving the residual displacement at this scale.
vec3 estimateFlowLevel(vec2 uv, sampler2D previousFrame, vec2 initialFlow, float stepPixels) {
    float xx=0.0, yy=0.0, xy=0.0, xt=0.0, yt=0.0;
    for (int y=-2; y<=2; y++) {
        for (int x=-2; x<=2; x++) {
            vec2 delta=vec2(float(x),float(y))*stepPixels*uTexelSize;
            vec2 p=clamp(uv+delta,uTexelSize*2.0,vec2(1.0)-uTexelSize*2.0);
            float l=luma(texture2D(sTexture,p-vec2(stepPixels*uTexelSize.x,0.0)).rgb);
            float r=luma(texture2D(sTexture,p+vec2(stepPixels*uTexelSize.x,0.0)).rgb);
            float d=luma(texture2D(sTexture,p-vec2(0.0,stepPixels*uTexelSize.y)).rgb);
            float u=luma(texture2D(sTexture,p+vec2(0.0,stepPixels*uTexelSize.y)).rgb);
            float now=luma(texture2D(sTexture,p).rgb);
            vec2 previousUv=clamp(p-initialFlow*uTexelSize,0.0,1.0);
            float prev=luma(texture2D(previousFrame,previousUv).rgb);
            float invStep=0.5/stepPixels;
            float gx=(r-l)*invStep;
            float gy=(u-d)*invStep;
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
    float structureConfidence=smoothstep(0.00020,0.0035,minEigen);

    float a=xx+0.00001;
    float c=yy+0.00001;
    float det=a*c-xy*xy;
    vec2 correction=vec2(0.0);
    if(det>1.0e-10) {
        correction.x=-(c*xt-xy*yt)/det;
        correction.y=-(-xy*xt+a*yt)/det;
    }
    float correctionLength=length(correction);
    if(correctionLength>20.0) correction*=20.0/correctionLength;
    return vec3(correction,structureConfidence);
}

// Coarse-to-fine flow: large sampling spacing captures fast game motion,
// then progressively smaller levels refine edges and local object detail.
vec3 estimateFlow(vec2 uv, sampler2D previousFrame) {
    vec3 coarse=estimateFlowLevel(uv,previousFrame,vec2(0.0),4.0);
    vec3 medium=estimateFlowLevel(uv,previousFrame,coarse.xy,2.0);
    vec3 fine=estimateFlowLevel(uv,previousFrame,coarse.xy+medium.xy,1.0);
    vec2 flow=coarse.xy+medium.xy+fine.xy;
    float magnitude=length(flow);
    if(magnitude>48.0) flow*=48.0/magnitude;

    vec2 warped=clamp(uv-flow*uTexelSize,0.0,1.0);
    float nowCenter=luma(texture2D(sTexture,uv).rgb);
    float previousWarped=luma(texture2D(previousFrame,warped).rgb);
    float residual=abs(nowCenter-previousWarped);
    float consistencyConfidence=1.0-smoothstep(0.055,0.24,residual);
    float structureConfidence=max(coarse.z,max(medium.z,fine.z));
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

// Fractional temporal sampling removes the separated, chunky appearance
// caused by accumulating only six discrete frame positions.
vec3 smoothHistory(float age, vec2 coord) {
    float baseAge=floor(age);
    float fraction=fract(age);
    vec3 a=historySample(baseAge,coord).rgb;
    vec3 b=historySample(min(baseAge+1.0,5.0),coord).rgb;
    return mix(a,b,fraction);
}
void main() {
    vec4 current=texture2D(sTexture,vTextureCoord);
    if(uBlur<=0.001 || uBlend<=0.001 || uHistoryCount<1.0) {
        gl_FragColor=current;
        return;
    }

    vec3 flowEstimate=estimateFlow(vTextureCoord,uHistory0);
    vec2 flow=flowEstimate.xy;
    float flowLength=length(flow);
    float confidence=flowEstimate.z*smoothstep(0.015,0.35,flowLength);
    if(confidence<=0.001) {
        gl_FragColor=current;
        return;
    }

    float fastMotion=smoothstep(0.7,4.0,flowLength);
    // Let the trail grow more aggressively with measured movement. Blur
    // slider controls the shutter/trail length; speed controls extra spread.
    float trailScale=uBlur*(3.0+7.0*fastMotion);
    float maxAge=min(uTimes,uHistoryCount);
    vec2 motionUv=flow*uTexelSize;

    // Integrate many evenly spaced sub-frame positions over the motion path.
    // This turns discrete frame ghosts into a continuous, smooth smear.
    vec3 accumulated=vec3(0.0);
    float totalWeight=0.0;
    float currentWeight=mix(0.55,0.10,fastMotion);
    accumulated+=current.rgb*currentWeight;
    totalWeight+=currentWeight;

    for(int i=0;i<24;i++) {
        float t=(float(i)+0.5)/24.0;
        float age=t*maxAge;
        float ageWeight=1.0;
        vec2 offset=motionUv*(age*trailScale);
        vec3 sampleColor;

        if(uDirection<0.5) {
            vec3 backColor=smoothHistory(age,clamp(vTextureCoord-offset,0.0,1.0));
            vec3 frontColor=smoothHistory(age,clamp(vTextureCoord+offset,0.0,1.0));
            sampleColor=(backColor+frontColor)*0.5;
        } else if(uDirection<1.5) {
            sampleColor=smoothHistory(age,clamp(vTextureCoord-offset,0.0,1.0));
        } else {
            sampleColor=smoothHistory(age,clamp(vTextureCoord+offset,0.0,1.0));
        }

        // A gentle center-to-edge falloff prevents a hard cut at the end
        // of the trail while keeping the streak dense enough to look fluid.
        float edgeWeight=0.82+0.18*sin(3.14159265*t);
        accumulated+=sampleColor*(ageWeight*edgeWeight);
        totalWeight+=ageWeight*edgeWeight;
    }

    vec3 temporalBlur=accumulated/max(totalWeight,0.0001);
    // Fast-moving detail is intentionally allowed to dissolve into the
    // integrated streak instead of remaining as a crisp duplicate.
    float strength=clamp(uBlend*confidence*(0.85+0.75*fastMotion),0.0,1.0);
    gl_FragColor=vec4(mix(current.rgb,temporalBlur,strength),current.a);
}
