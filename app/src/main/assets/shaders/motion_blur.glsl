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
    if (uIntensity <= 0.001 || uHasHistory < 0.5) { gl_FragColor = current; return; }
    float xx=0.0, yy=0.0, xy=0.0, xt=0.0, yt=0.0;
    for (int y=-2; y<=2; y++) for (int x=-2; x<=2; x++) {
        vec2 p=clamp(vTextureCoord+vec2(float(x),float(y))*uTexelSize,uTexelSize*2.0,vec2(1.0)-uTexelSize*2.0);
        float left=luma(texture2D(sTexture,p-vec2(uTexelSize.x,0.0)).rgb);
        float right=luma(texture2D(sTexture,p+vec2(uTexelSize.x,0.0)).rgb);
        float down=luma(texture2D(sTexture,p-vec2(0.0,uTexelSize.y)).rgb);
        float up=luma(texture2D(sTexture,p+vec2(0.0,uTexelSize.y)).rgb);
        float now=luma(texture2D(sTexture,p).rgb), prev=luma(texture2D(uPreviousTexture,p).rgb);
        float gx=(right-left)*0.5, gy=(up-down)*0.5, gt=now-prev;
        xx+=gx*gx; yy+=gy*gy; xy+=gx*gy; xt+=gx*gt; yt+=gy*gt;
    }
    float lambda=0.00002, a=xx+lambda, c=yy+lambda, det=a*c-xy*xy;
    vec2 flow=vec2(0.0);
    if(det>1.0e-10) {
        flow.x=-(c*xt-xy*yt)/det;
        flow.y=-(-xy*xt+a*yt)/det;
    }
    float flowLen=length(flow);
    if(flowLen>12.0) { flow*=12.0/flowLen; flowLen=12.0; }
    float trace=xx+yy;
    float disc=sqrt(max((xx-yy)*(xx-yy)+4.0*xy*xy,0.0));
    float minEigen=0.5*(trace-disc);
    float textureConfidence=smoothstep(0.00002,0.0018,minEigen);
    float motionConfidence=smoothstep(0.04,0.30,flowLen);
    float confidence=textureConfidence*motionConfidence;
    vec2 blurVector=flow*uTexelSize*(0.35+1.65*uIntensity)*uIntensity;
    vec3 blurred=current.rgb*0.14;
    blurred+=texture2D(sTexture,clamp(vTextureCoord-blurVector*0.50,0.0,1.0)).rgb*0.12;
    blurred+=texture2D(sTexture,clamp(vTextureCoord-blurVector*0.33,0.0,1.0)).rgb*0.12;
    blurred+=texture2D(sTexture,clamp(vTextureCoord-blurVector*0.16,0.0,1.0)).rgb*0.12;
    blurred+=texture2D(sTexture,clamp(vTextureCoord+blurVector*0.16,0.0,1.0)).rgb*0.12;
    blurred+=texture2D(sTexture,clamp(vTextureCoord+blurVector*0.33,0.0,1.0)).rgb*0.12;
    blurred+=texture2D(sTexture,clamp(vTextureCoord+blurVector*0.50,0.0,1.0)).rgb*0.14;
    gl_FragColor=vec4(mix(current.rgb,blurred,confidence),current.a);
}