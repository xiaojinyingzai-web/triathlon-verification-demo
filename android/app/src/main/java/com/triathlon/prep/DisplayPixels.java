package com.triathlon.prep;

/** Display-only raw NV21 sampling and explicitly confirmed orientation. No detection input mutation. */
public final class DisplayPixels {
    private DisplayPixels(){}
    public static final class Mapping {
        public final int rotation;
        public final boolean mirror;
        public Mapping(int rotation,boolean mirror) {
            if(rotation!=0 && rotation!=90 && rotation!=180 && rotation!=270)throw new IllegalArgumentException("Explicit display rotation required");
            this.rotation=rotation;this.mirror=mirror;
        }
        private float[] point(float x,float y,int w,int h) {
            float dx=x,dy=y;
            if(rotation==90){dx=h-y;dy=x;}
            else if(rotation==180){dx=w-x;dy=h-y;}
            else if(rotation==270){dx=y;dy=w-x;}
            int rw=rotation%180==0?w:h;
            return new float[]{mirror?rw-dx:dx,dy};
        }
        public float[] destination(int bufferWidth,int bufferHeight,int viewWidth,int viewHeight) {
            if(bufferWidth<1 || bufferHeight<1 || viewWidth<1 || viewHeight<1)throw new IllegalArgumentException("Positive geometry required");
            int rw=rotation%180==0?bufferWidth:bufferHeight,rh=rotation%180==0?bufferHeight:bufferWidth;
            float scale=Math.min((float)viewWidth/rw,(float)viewHeight/rh),left=(viewWidth-rw*scale)/2,top=(viewHeight-rh*scale)/2;
            float[] out=new float[6];float[][] corners={{0,0},{bufferWidth,0},{0,bufferHeight}};
            for(int i=0;i<3;i++){float[] p=point(corners[i][0],corners[i][1],bufferWidth,bufferHeight);out[2*i]=left+p[0]*scale;out[2*i+1]=top+p[1]*scale;}
            return out;
        }
    }
    public static final class Pixels {
        public final int width,height;public final int[] argb;
        Pixels(int w,int h,int[] p){width=w;height=h;argb=p;}
    }
    public static Pixels thumbnail(byte[] nv21,int w,int h,Mapping mapping) {
        if(mapping==null || nv21==null || w<2 || h<2 || (w&1)!=0 || (h&1)!=0 || (long)w*h>4096L*4096 || nv21.length!=(long)w*h*3/2)
            throw new IllegalArgumentException("Explicit mapped NV21 required");
        int sample=Math.max(1,(Math.max(w,h)+255)/256),sw=(w+sample-1)/sample,sh=(h+sample-1)/sample;
        int dw=mapping.rotation%180==0?sw:sh,dh=mapping.rotation%180==0?sh:sw;
        int[] pixels=new int[dw*dh];
        for(int sy=0;sy<sh;sy++)for(int sx=0;sx<sw;sx++) {
            int x=sx*sample,y=sy*sample,uv=w*h+(y/2)*w+(x&~1);
            int yy=Math.max(0,(nv21[y*w+x]&255)-16),v=(nv21[uv]&255)-128,u=(nv21[uv+1]&255)-128;
            int color=0xff000000 | (clamp((298*yy+409*v+128)>>8)<<16) | (clamp((298*yy-100*u-208*v+128)>>8)<<8) | clamp((298*yy+516*u+128)>>8);
            int dx=sx,dy=sy;
            if(mapping.rotation==90){dx=sh-1-sy;dy=sx;}else if(mapping.rotation==180){dx=sw-1-sx;dy=sh-1-sy;}else if(mapping.rotation==270){dx=sy;dy=sw-1-sx;}
            if(mapping.mirror)dx=dw-1-dx;
            pixels[dy*dw+dx]=color;
        }
        return new Pixels(dw,dh,pixels);
    }
    private static int clamp(int v){return Math.max(0,Math.min(255,v));}
}
