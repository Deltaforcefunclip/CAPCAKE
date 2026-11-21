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
    if (uIntensity <= 0.001 || uHasHistory < 0.5) {
        gl_FragColor = current;
        return;
    }

    // Estimasi Lucas-Kanade 5x5 dengan ambang sensitivitas lebih rendah.
    float xx=0.0, yy=0.0, xy=0.0, xt=0.0, yt=0.0;
    for (int y=-2; y<=2; y++) {
        for (int x=-2; x<=2; x++) {
            vec2 p=clamp(vTextureCoord+vec2(float(x),float(y))*uTexelSize,
                         uTexelSize*2.0,vec2(1.0)-uTexelSize*2.0);
            float l=luma(texture2D(sTexture,p-vec2(uTexelSize.x,0.0)).rgb);
            float r=luma(texture2D(sTexture,p+vec2(uTexelSize.x,0.0)).rgb);
            float d=luma(texture2D(sTexture,p-vec2(0.0,uTexelSize.y)).rgb);
            float u=luma(texture2D(sTexture,p+vec2(0.0,uTexelSize.y)).rgb);
            float now=luma(texture2D(sTexture,p).rgb);
            float prev=luma(texture2D(uPreviousTexture,p).rgb);
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
    if(flowLen>20.0) { flow*=20.0/flowLen; flowLen=20.0; }

    float trace=xx+yy;
    float disc=sqrt(max((xx-yy)*(xx-yy)+4.0*xy*xy,0.0));
    float minEigen=0.5*(trace-disc);
    // Threshold lebih rendah agar gerak kecil dan detail bertekstur ikut terdeteksi.
    float textureConfidence=smoothstep(0.0000001,0.00008,minEigen);
    float motionConfidence=smoothstep(0.008,0.10,flowLen);
    float confidence=textureConfidence*motionConfidence;

    // Perkuat panjang sapuan agar efek nyata terlihat, tetap searah optical flow.
    vec2 blurVector=flow*uTexelSize*(2.0+8.0*uIntensity)*uIntensity;
    vec3 blurred=current.rgb*0.16;
    blurred+=texture2D(sTexture,clamp(vTextureCoord-blurVector*0.60,0.0,1.0)).rgb*0.14;
    blurred+=texture2D(sTexture,clamp(vTextureCoord-blurVector*0.40,0.0,1.0)).rgb*0.14;
    blurred+=texture2D(sTexture,clamp(vTextureCoord-blurVector*0.20,0.0,1.0)).rgb*0.14;
    blurred+=texture2D(sTexture,clamp(vTextureCoord+blurVector*0.20,0.0,1.0)).rgb*0.14;
    blurred+=texture2D(sTexture,clamp(vTextureCoord+blurVector*0.40,0.0,1.0)).rgb*0.14;
    blurred+=texture2D(sTexture,clamp(vTextureCoord+blurVector*0.60,0.0,1.0)).rgb*0.14;

    gl_FragColor=vec4(mix(current.rgb,blurred,confidence),current.a);
}