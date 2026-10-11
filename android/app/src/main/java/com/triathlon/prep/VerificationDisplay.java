package com.triathlon.prep;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import com.triathlon.prep.ui.VerificationView;
import java.util.Arrays;
import java.util.function.Consumer;

/** Memory-only, optional presentation side channel. It never determines a verdict or changes detector input. */
public final class VerificationDisplay implements AutoCloseable {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Object lock=new Object();
    private DisplayPixels.Mapping mapping;
    private final int width,height;
    private String id;
    private long revision;
    private boolean foreground,closed,chipQueued,sceneQueued,previewReady;
    private boolean accepting;
    private Bitmap chip,scene;
    private VerificationView view;
    private Runnable changed;
    private String boundPreview;
    private PreviewLease<Surface> target;
    private Consumer<PreviewLease<Surface>> waiting;
    private Consumer<Long> frames;
    private SurfaceTexture texture;
    private long previewGeneration;
    private int previewWidth,previewHeight;
    private VerificationView.PreviewCallbacks callbacks(long generation) {return new VerificationView.PreviewCallbacks() {
        public void onAvailable(String sessionId,SurfaceTexture texture,int w,int h) {
            // UI owns texture; only our Surface wrapper is owned here. Never reenter a binding callback.
            if(!current(sessionId)||generation!=previewGeneration)return;
            Surface surface;
            try{texture.setDefaultBufferSize(width,height);surface=new Surface(texture);}
            catch(RuntimeException | OutOfMemoryError ex){
                Consumer<PreviewLease<Surface>> failed;
                synchronized(lock){failed=waiting;waiting=null;}
                if(failed!=null)main.post(()->failed.accept(null));return;
            }
            PreviewLease<Surface> created=new PreviewLease<>(surface,()->{try{surface.release();}catch(RuntimeException ignored){}});
            Consumer<PreviewLease<Surface>> notify;
            synchronized(lock){if(!currentLocked(sessionId)||generation!=previewGeneration){created.invalidate();return;}target=created;VerificationDisplay.this.texture=texture;previewWidth=w;previewHeight=h;notify=waiting;waiting=null;}
            if(notify!=null)main.post(()->notify.accept(created));
            main.post(()->{if(current(sessionId)&&generation==previewGeneration)applyMapping(w,h);});
        }
        public void onSizeChanged(String sessionId,SurfaceTexture texture,int w,int h) {
            main.post(()->{if(current(sessionId)&&generation==previewGeneration){previewWidth=w;previewHeight=h;applyMapping(w,h);}});
        }
        public void onFrameUpdated(String sessionId,SurfaceTexture source,long timestamp) {
            Consumer<Long> notify;
            synchronized(lock){if(!currentLocked(sessionId)||generation!=previewGeneration||source!=texture||target==null||target.value()==null||timestamp<=0)return;notify=frames;}
            if(notify!=null)notify.accept(timestamp);
        }
        public void onUnavailable(String sessionId) {
            PreviewLease<Surface> old;
            Consumer<PreviewLease<Surface>> failed;
            synchronized(lock){if(generation!=previewGeneration)return;old=target;target=null;texture=null;frames=null;failed=waiting;waiting=null;previewReady=false;}
            if(old!=null)old.invalidate(); // Synchronously invalidates requests; camera worker performs actual teardown.
            if(failed!=null)main.post(()->failed.accept(null));
        }
    };}
    VerificationDisplay(DisplayPixels.Mapping mapping,int width,int height) {
        if(width<2 || height<2)throw new IllegalArgumentException("Display source dimensions required");
        this.mapping=mapping;this.width=width;this.height=height;
    }
    public void attach(Runnable changed) {
        checkMain();synchronized(lock){if(closed)return;foreground=true;this.changed=changed;}
    }
    /** Host arms input immediately before an authorized begin; cancellation/next never rearms it. */
    public void arm(){checkMain();clear();synchronized(lock){accepting=foreground && !closed;}}
    public void chip(String sessionId,byte[] encoded) {
        long token;
        synchronized(lock) {
            if(closed || !foreground || !accepting || sessionId==null || encoded==null || encoded.length==0 || encoded.length>8*1024*1024)return;
            if(id!=null && !id.equals(sessionId))return; // Only Host clearing the old session permits replacement.
            if(id==null){id=sessionId;revision++;}if(chipQueued)return;chipQueued=true;token=revision;
        }
        byte[] copy=encoded.clone();main.post(()->decodeImage(sessionId,token,copy,true));
        signal(); // Lets Host bind the Surface while the source waits, even before the bitmap is decoded.
    }
    public void scene(String sessionId,byte[] nv21,int w,int h) {
        long token;
        synchronized(lock){if(!currentLocked(sessionId) || sceneQueued || mapping==null || w!=width || h!=height)return;sceneQueued=true;token=revision;}
        DisplayPixels.Pixels pixels;
        try{pixels=DisplayPixels.thumbnail(nv21,w,h,mapping);}catch(RuntimeException | OutOfMemoryError ignored){return;}
        main.post(()-> {
            Bitmap result=null;
            try {
                synchronized(lock){if(!currentLocked(sessionId) || token!=revision)return;}
                result=Bitmap.createBitmap(pixels.argb,pixels.width,pixels.height,Bitmap.Config.ARGB_8888);
                Bitmap previous=scene;scene=result;result=null;
                if(view!=null)view.bindPhotos(sessionId,chip,scene);
                recycle(previous);signal();
            }catch(RuntimeException | OutOfMemoryError ignored){}finally{Arrays.fill(pixels.argb,0);recycle(result);}
        });
    }
    public void sceneEncoded(String sessionId,byte[] encoded) {
        long token;
        synchronized(lock){if(!currentLocked(sessionId)||sceneQueued||encoded==null||encoded.length<8||encoded.length>8*1024*1024)return;sceneQueued=true;token=revision;}
        byte[] copy=encoded.clone();main.post(()->decodeImage(sessionId,token,copy,false));
    }
    private void decodeImage(String sessionId,long token,byte[] copy,boolean certificate) {
        Bitmap result=null;
        try {
            synchronized(lock){if(!currentLocked(sessionId) || token!=revision)return;}
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(copy,0,copy.length,bounds);
            if(bounds.outWidth<1 || bounds.outHeight<1 || bounds.outWidth>8192 || bounds.outHeight>8192)return;
            BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            options.inSampleSize=1;while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>256)options.inSampleSize*=2;
            result=BitmapFactory.decodeByteArray(copy,0,copy.length,options);
            synchronized(lock){if(!currentLocked(sessionId) || token!=revision)return;}
            Bitmap previous=certificate?chip:scene;if(certificate)chip=result;else scene=result;result=null;
            if(view!=null)view.bindPhotos(sessionId,chip,scene);
            recycle(previous);signal();
        }catch(RuntimeException | OutOfMemoryError ignored){/* Optional display failure cannot turn detection into success/failure. */}
        finally{Arrays.fill(copy,(byte)0);recycle(result);}
    }
    /** Host always calls this AFTER render(Model), since legacy model nulls clear both images. */
    public void render(VerificationView currentView,String sessionId,boolean keepImages,boolean wantPreview) {
        checkMain();view=currentView;
        if(!keepImages){synchronized(lock){if(id==null)return;}clear();return;}
        if(!current(sessionId))return;
        currentView.bindSession(sessionId);currentView.bindPhotos(sessionId,chip,scene);
        if(mapping!=null && wantPreview && !sessionId.equals(boundPreview)) {
            boundPreview=sessionId;currentView.bindPreview(sessionId,callbacks(++previewGeneration));
        }else if(!wantPreview && boundPreview!=null){boundPreview=null;currentView.clearPreview(sessionId);}
        if(mapping!=null && wantPreview)currentView.setPreviewVisible(sessionId,previewReady);
    }
    private void applyMapping(int w,int h) {
        if(view==null || mapping==null || w<1 || h<1)return;
        Matrix transform=new Matrix();transform.setPolyToPoly(new float[]{0,0,w,0,0,h},0,mapping.destination(width,height,w,h),0,3);
        view.setPreviewTransform(id,transform);
    }
    boolean wantsPreview(){return mapping!=null;}
    void awaitPreview(String sessionId,Consumer<PreviewLease<Surface>> ready) {
        PreviewLease<Surface> available;
        synchronized(lock){if(!currentLocked(sessionId))available=null;else{available=target;if(available==null){waiting=ready;return;}}}
        ready.accept(available);
    }
    void watchFrames(String sessionId,PreviewLease<Surface> lease,Consumer<Long> listener) {
        synchronized(lock){if(currentLocked(sessionId)&&target==lease&&lease.value()!=null)frames=listener;}
    }
    void configurePreview(String sessionId,int rotation,boolean mirror) {
        main.post(()->{if(current(sessionId)){mapping=new DisplayPixels.Mapping(rotation,mirror);applyMapping(previewWidth,previewHeight);}});
    }
    void stopPreview(String sessionId) {
        main.post(()->{if(current(sessionId) && view!=null){boundPreview=null;view.clearPreview(sessionId);}});
    }
    void previewFrame(String sessionId) {
        main.post(()->{if(current(sessionId)){previewReady=true;if(view!=null)view.setPreviewVisible(sessionId,true);}});
    }
    public void clear() {
        PreviewLease<Surface> old;
        checkMain();synchronized(lock){accepting=false;id=null;revision++;chipQueued=false;sceneQueued=false;previewReady=false;waiting=null;frames=null;texture=null;old=target;target=null;}
        // Mark intentional loss BEFORE UI unbind invokes onUnavailable. Core cancellation owns the verdict.
        if(old!=null)old.cancel();
        if(view!=null)view.bindSession(null);boundPreview=null;
        recycle(chip);recycle(scene);chip=null;scene=null;
    }
    public void detach() {checkMain();synchronized(lock){foreground=false;changed=null;}clear();view=null;}
    public void close() {main.post(()->{detach();synchronized(lock){closed=true;}});}
    private boolean current(String value){synchronized(lock){return currentLocked(value);}}
    private boolean currentLocked(String value){return !closed && foreground && value!=null && value.equals(id);}
    private void signal(){main.post(()->{Runnable notify;synchronized(lock){notify=foreground?changed:null;}if(notify!=null)notify.run();});}
    private static void recycle(Bitmap value){if(value!=null && !value.isRecycled())value.recycle();}
    private static void checkMain(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Display owner is main thread");}
}
