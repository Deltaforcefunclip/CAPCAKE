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

// Dense per-pixel Lucas-Kanade estimate over a 5x5 neighbourhood.
vec2 estimateFlow(vec2 uv, sampler2D previousFrame) {
    float xx=0.0, yy=0.0, xy=0.0, xt=0.0, yt=0.0;
    for (int y=-2; y<=2; y++) {
        for (int x=-2; x<=2; x++) {
            vec2 p=clamp(uv+vec2(float(x),float(y))*uTexelSize,uTexelSize*2.0,vec2(1.0)-uTexelSize*2.0);
            float l=luma(texture2D(sTexture,p-vec2(uTexelSize.x,0.0)).rgb);
            float r=luma(texture2D(sTexture,p+vec2(uTexelSize.x,0.0)).rgb);
            float d=luma(texture2D(sTexture,p-vec2(0.0,uTexelSize.y)).rgb);
            float u=luma(texture2D(sTexture,p+vec2(0.0,uTexelSize.y)).rgb);
            float now=luma(texture2D(sTexture,p).rgb);
            float prev=luma(texture2D(previousFrame,p).rgb);
            float gx=(r-l)*0.5, gy=(u-d)*0.5, gt=now-prev;
            xx+=gx*gx; yy+=gy*gy; xy+=gx*gy; xt+=gx*gt; yt+=gy*gt;
        }
    }
    float lambda=0.000001;
    float a=xx+lambda, c=yy+lambda, det=a*c-xy*xy;
    vec2 flow=vec2(0.0);
    if(det>1.0e-12) {
        flow.x=-(c*xt-xy*yt)/det;
        flow.y=-(-xy*xt+a*yt)/det;
    }
    float flowLen=length(flow);
    if(flowLen>24.0) flow*=24.0/flowLen;
    return flow;
}

void main() {
    vec4 current=texture2D(sTexture,vTextureCoord);
    if(uBlur<=0.001 || uBlend<=0.001 || uHistoryCount<1.0) {
        gl_FragColor=current;
        return;
    }

    // Combine motion estimates over increasingly long temporal baselines.
    vec2 flow=estimateFlow(vTextureCoord,uHistory0);
    float flowWeight=1.0;
    if(uTimes>=2.0 && uHistoryCount>=2.0) {
        flow+=estimateFlow(vTextureCoord,uHistory1)*0.35;
        flowWeight+=0.35;
    }
    if(uTimes>=4.0 && uHistoryCount>=4.0) {
        flow+=estimateFlow(vTextureCoord,uHistory3)*(0.25/3.0);
        flowWeight+=0.25;
    }
    if(uTimes>=6.0 && uHistoryCount>=6.0) {
        flow+=estimateFlow(vTextureCoord,uHistory5)*(0.20/6.0);
        flowWeight+=0.20;
    }
    flow/=flowWeight;

    float flowLen=length(flow);
    float confidence=smoothstep(0.000001,0.00012,flowLen);
    vec2 flowUv=flow*uTexelSize;
    vec2 streak=flowUv*(0.35+4.65*uBlur);
    vec2 axis=flowLen>0.0001 ? normalize(flowUv) : vec2(0.0);

    // Temporal exposure samples are motion-compensated along the estimated vector.
    vec3 accumulated=current.rgb*0.24;
    float totalWeight=0.24;
    for(int i=0;i<6;i++) {
        if(float(i)<uTimes) {
            float t=(float(i)+0.5)/max(uTimes,1.0);
            vec2 offset=axis*(t*length(streak));
            if(uDirection<0.5) {
                vec3 backSample=texture2D(sTexture,clamp(vTextureCoord+offset,0.0,1.0)).rgb;
                vec3 forwardSample=texture2D(sTexture,clamp(vTextureCoord-offset,0.0,1.0)).rgb;
                accumulated+=(backSample+forwardSample)*0.5;
            } else if(uDirection<1.5) {
                accumulated+=texture2D(sTexture,clamp(vTextureCoord+offset,0.0,1.0)).rgb;
            } else {
                accumulated+=texture2D(sTexture,clamp(vTextureCoord-offset,0.0,1.0)).rgb;
            }
            totalWeight+=1.0;
        }
    }
    vec3 temporalAccumulation=accumulated/totalWeight;
    gl_FragColor=vec4(mix(current.rgb,temporalAccumulation,uBlend*confidence),current.a);
}