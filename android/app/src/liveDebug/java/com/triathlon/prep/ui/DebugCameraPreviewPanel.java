package com.triathlon.prep.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.triathlon.prep.DebugCameraPreviewActivity;
import com.triathlon.prep.DebugPrivatePreview;
import com.triathlon.prep.DebugSingleCapture;
import com.triathlon.prep.CameraModule;
import java.util.Arrays;

/** LIVE debug-only local PRIVATE preview; Surface ownership waits for CLOSED. */
public final class DebugCameraPreviewPanel implements DebugCameraPreviewActivity.PanelFactory {
    public DebugCameraPreviewPanel() {}
    @Override public DebugCameraPreviewActivity.Panel create(Activity activity,DebugCameraPreviewActivity.Actions actions) {
        if(activity==null||actions==null)throw new IllegalArgumentException("Preview panel owner required");
        main();return new Content(activity,actions);
    }
    private static void main(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("UI main thread required");}

    static final class CaptureClickGuard {
        private boolean closed=true,single,ready,clicked,cancelled;
        boolean begin(boolean capture){if(!closed)return false;closed=false;single=capture;ready=false;clicked=false;cancelled=false;return true;}
        void ready(boolean value){ready=value&&single&&!closed&&!clicked&&!cancelled;}
        boolean canShoot(){return ready&&single&&!closed&&!clicked&&!cancelled;}
        boolean take(){if(!canShoot())return false;clicked=true;ready=false;return true;}
        boolean acceptsResult(){return single&&clicked&&!cancelled;}
        void cancel(){cancelled=true;ready=false;}
        void released(){closed=true;ready=false;}
    }

    private static final class Content extends ScrollView implements DebugCameraPreviewActivity.Panel,TextureView.SurfaceTextureListener {
        private final DebugCameraPreviewActivity.Actions actions;
        private final TextureView texture;
        private final TextView state;
        private final EditText id,width,height,rotation,jpegWidth,jpegHeight,deviceAngle,externalAngle;
        private final Button start,stop,prepareShot,shoot,clear;
        private final ImageView resultImage;
        private final TextView resultSummary;
        private final CaptureClickGuard clickGuard=new CaptureClickGuard();
        private Bitmap resultBitmap;
        private final Runnable expireResult=this::clearCapture;
        private boolean singleMode,configured,mirrorPreview;
        private int previewRotation;
        private SurfaceTexture available,held;
        private Surface surface;
        private DebugPrivatePreview.Config config;
        private boolean closed=true,stopRequested;

        Content(Activity activity,DebugCameraPreviewActivity.Actions actions) {
            super(activity);this.actions=actions;setSaveEnabled(false);setFillViewport(true);setBackgroundColor(0xfff5f7fa);
            LinearLayout panel=column();panel.setPadding(dp(18),dp(16),dp(18),dp(18));addView(panel,new ScrollView.LayoutParams(-1,-2));
            Button exit=button("← 结束本地预览");exit.setOnClickListener(v->{clearCapture();requestStop();actions.exit();});panel.addView(exit);
            add(panel,text("设备诊断 · 本地相机预览",25,true),14);
            add(panel,text("仅本地诊断；拍照须明确点击。结果仅内存显示，30秒后清除，不写文件、不上传。",15,false),10);
            LinearLayout parameters=card("明确指定预览参数");
            id=input(parameters,"相机 ID（以设备诊断结果为准）",false);
            LinearLayout sizes=column();width=input(sizes,"宽度（像素）",true);height=input(sizes,"高度（像素）",true);add(parameters,sizes,8);
            rotation=input(parameters,"显示旋转角度：0 / 90 / 180 / 270",true);rotation.setText("0");
            jpegWidth=input(parameters,"单次抓拍 JPEG 宽度（像素）",true);jpegHeight=input(parameters,"单次抓拍 JPEG 高度（像素）",true);
            deviceAngle=input(parameters,"抓拍设备顺时针角度：0 / 90 / 180 / 270",true);deviceAngle.setText("0");
            externalAngle=input(parameters,"外接相机安装角度（未知留空；0 / 90 / 180 / 270）",true);
            id.setText("0");width.setText("1280");height.setText("720");jpegWidth.setText("1280");jpegHeight.setText("720");
            add(parameters,text("以上为诊断候选参数，不代表已确认 RGB 相机或 JPEG 抓拍可用。",14,false),10);
            add(parameters,text("使用设备支持的尺寸；纯预览不镜像，抓拍预览按相机配置显示方向与镜像。",14,false),10);add(panel,parameters,16);
            state=text("已关闭 · 填写参数后开始本地预览",16,true);add(panel,state,14);
            FrameLayout preview=new FrameLayout(activity);preview.setBackgroundColor(Color.BLACK);
            texture=new TextureView(activity);texture.setOpaque(true);texture.setSaveEnabled(false);texture.setSurfaceTextureListener(this);
            preview.addView(texture,new FrameLayout.LayoutParams(-1,-1));LinearLayout.LayoutParams frame=new LinearLayout.LayoutParams(-1,dp(360));frame.topMargin=dp(12);panel.addView(preview,frame);
            LinearLayout buttons=new LinearLayout(activity);buttons.setGravity(Gravity.CENTER_VERTICAL);
            start=button("开始本地预览");stop=button("停止预览");buttons.addView(start,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams stopParams=new LinearLayout.LayoutParams(0,-2,1);stopParams.leftMargin=dp(10);buttons.addView(stop,stopParams);add(panel,buttons,14);
            start.setOnClickListener(v->begin());stop.setOnClickListener(v->requestStop());refresh();
            LinearLayout shotActions=new LinearLayout(activity);
            prepareShot=button("准备单次抓拍");shoot=button("拍照一次");clear=button("清除内存结果");
            for(Button button:new Button[]{prepareShot,shoot,clear})shotActions.addView(button,new LinearLayout.LayoutParams(0,-2,1));add(panel,shotActions,12);
            LinearLayout result=card("本次内存抓拍结果");resultSummary=text("暂无结果",14,false);add(result,resultSummary,8);
            resultImage=new ImageView(activity);resultImage.setSaveEnabled(false);resultImage.setScaleType(ImageView.ScaleType.FIT_CENTER);resultImage.setContentDescription("本次临时抓拍画面");resultImage.setVisibility(View.GONE);result.addView(resultImage,new LinearLayout.LayoutParams(-1,dp(300)));add(panel,result,12);
            prepareShot.setOnClickListener(v->beginSingle());shoot.setOnClickListener(v->{if(!stopRequested&&configured&&clickGuard.take()){refresh();state.setText("已请求本次拍照 · 等待结果和资源关闭");actions.shoot();}});
            clear.setOnClickListener(v->clearCapture());refresh();
        }
        @Override public View view(){return this;}
        @Override public void status(DebugPrivatePreview.Status next){
            main();if(next==null)throw new IllegalArgumentException("Preview status required");
            if(next!=DebugPrivatePreview.Status.PREVIEW)clickGuard.ready(false);
            if(next==DebugPrivatePreview.Status.CLOSED){
                closed=true;stopRequested=false;configured=false;clickGuard.released();
                if(surface!=null){surface.release();surface=null;}
                if(held!=null&&held!=available)held.release();held=null;config=null;
            }
            state.setText(label(next));refresh();
        }
        @Override public void configured(CameraModule.PreviewInfo info){main();if(closed||stopRequested||!singleMode||info==null)return;previewRotation=info.previewRotationDegrees;mirrorPreview=info.mirrorPreview;configured=true;transform();state.setText("单次预览已配置 · 旋转"+previewRotation+"°，镜像："+(mirrorPreview?"按相机配置开启":"关闭")+" · 等待就绪后明确拍照");refresh();}
        @Override public void shotReady(boolean ready){main();clickGuard.ready(ready&&configured&&!stopRequested);refresh();}
        @Override public void captured(DebugSingleCapture.Result borrowed){
            main();if(borrowed==null||!clickGuard.acceptsResult()||stopRequested||!isAttachedToWindow()||getWindowVisibility()!=View.VISIBLE)return;
            clearCapture();byte[] png=null;Bitmap decoded=null;
            try{
                png=borrowed.copyPng();if(png==null)throw new IllegalArgumentException("No image");
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(png,0,png.length,bounds);
                if(bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>8192||bounds.outHeight>8192)throw new IllegalArgumentException("Invalid image size");
                BitmapFactory.Options options=new BitmapFactory.Options();options.inMutable=true;options.inScaled=false;options.inSampleSize=1;
                while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>2048)options.inSampleSize*=2;
                decoded=BitmapFactory.decodeByteArray(png,0,png.length,options);if(decoded==null)throw new IllegalArgumentException("Decode failed");
                resultBitmap=decoded;decoded=null;resultImage.setImageBitmap(resultBitmap);resultImage.setVisibility(View.VISIBLE);
                resultSummary.setText("归一化尺寸："+borrowed.width+" × "+borrowed.height+"；JPEG字节数："+borrowed.jpegBytes+"\n传感器时间戳："+borrowed.sensorTimestampNanos+"\n仅内存显示，30秒后清除；不表示人脸识别或核验通过。");
                postDelayed(expireResult,30000);
            }catch(IllegalArgumentException|OutOfMemoryError invalid){clearCapture();resultSummary.setText("内存画面未能显示，请关闭后重新准备抓拍");}
            finally{if(png!=null)Arrays.fill(png,(byte)0);if(decoded!=null)decoded.recycle();refresh();}
        }
        @Override public void clearCapture(){main();removeCallbacks(expireResult);if(resultImage!=null){resultImage.setImageDrawable(null);resultImage.setVisibility(View.GONE);}if(resultBitmap!=null){if(!resultBitmap.isRecycled()){if(resultBitmap.isMutable())resultBitmap.eraseColor(Color.TRANSPARENT);resultBitmap.recycle();}resultBitmap=null;}if(resultSummary!=null)resultSummary.setText("暂无结果");if(clear!=null)clear.setEnabled(false);}
        private void begin(){
            main();if(!closed||available==null||!texture.isAvailable()||!isAttachedToWindow())return;
            DebugPrivatePreview.Config requested;
            try{requested=new DebugPrivatePreview.Config(id.getText().toString().trim(),Integer.parseInt(width.getText().toString().trim()),Integer.parseInt(height.getText().toString().trim()),Integer.parseInt(rotation.getText().toString().trim()));}
            catch(IllegalArgumentException invalid){state.setText("参数无效 · 请填写相机 ID、支持的宽高及0/90/180/270角度");return;}
            if(!clickGuard.begin(false))return;clearCapture();singleMode=false;configured=false;
            available.setDefaultBufferSize(requested.width,requested.height);
            held=available;surface=new Surface(held);config=requested;closed=false;stopRequested=false;
            transform();state.setText("等待权限或相机打开 · 请勿重复开始");refresh();actions.start(requested,surface);
        }
        private void beginSingle(){
            main();if(!closed||available==null||!texture.isAvailable()||!isAttachedToWindow())return;
            CameraModule.Config requested;
            try{String external=externalAngle.getText().toString().trim();requested=new CameraModule.Config(id.getText().toString().trim(),Integer.parseInt(width.getText().toString().trim()),Integer.parseInt(height.getText().toString().trim()),Integer.parseInt(jpegWidth.getText().toString().trim()),Integer.parseInt(jpegHeight.getText().toString().trim()),Integer.parseInt(rotation.getText().toString().trim()),Integer.parseInt(deviceAngle.getText().toString().trim()),external.isEmpty()?null:Integer.valueOf(external));}
            catch(IllegalArgumentException invalid){state.setText("单次抓拍参数无效 · 核对ID、两种尺寸和角度");return;}
            if(!clickGuard.begin(true))return;clearCapture();singleMode=true;configured=false;previewRotation=0;mirrorPreview=false;
            available.setDefaultBufferSize(requested.previewWidth,requested.previewHeight);held=available;surface=new Surface(held);config=new DebugPrivatePreview.Config(requested.cameraId,requested.previewWidth,requested.previewHeight,requested.displayRotationDegrees);closed=false;stopRequested=false;
            state.setText("正在准备单次抓拍 · 就绪后请明确点击拍照一次");refresh();actions.startSingleCapture(requested,surface);
        }
        private void requestStop(){main();if(closed||stopRequested)return;stopRequested=true;clickGuard.cancel();clearCapture();state.setText("正在停止 · 等待相机确认关闭");refresh();actions.stop();}
        private void refresh(){
            boolean canStart=closed&&available!=null&&texture.isAvailable()&&isAttachedToWindow();
            start.setEnabled(canStart);stop.setEnabled(!closed&&!stopRequested);
            for(EditText input:new EditText[]{id,width,height,rotation,jpegWidth,jpegHeight,deviceAngle,externalAngle})if(input!=null)input.setEnabled(closed);
            start.setAlpha(canStart?1f:.45f);stop.setAlpha(stop.isEnabled()?1f:.45f);
            if(prepareShot!=null)prepareShot.setEnabled(canStart);if(shoot!=null)shoot.setEnabled(!stopRequested&&configured&&clickGuard.canShoot());if(clear!=null)clear.setEnabled(resultBitmap!=null);
        }
        @Override public void onSurfaceTextureAvailable(SurfaceTexture incoming,int w,int h){main();available=incoming;transform();refresh();}
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture incoming,int w,int h){main();if(incoming==available)transform();}
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture outgoing){
            main();if(available==outgoing)available=null;
            if(outgoing==held&&!closed)requestStop();else outgoing.release();
            refresh();return false;
        }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture incoming){main();if(singleMode&&!closed&&!stopRequested&&surface!=null&&incoming==held&&incoming==available)actions.previewFrame(surface,incoming.getTimestamp());}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();refresh();}
        @Override protected void onDetachedFromWindow(){clearCapture();requestStop();super.onDetachedFromWindow();}
        @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility!=View.VISIBLE){clearCapture();requestStop();}}
        private void transform(){
            if(config==null||available!=held||texture.getWidth()<1||texture.getHeight()<1)return;
            float vw=texture.getWidth(),vh=texture.getHeight(),cx=vw/2,cy=vh/2;
            if(singleMode&&!configured)return;int angle=singleMode?previewRotation:config.rotationDegrees;
            boolean quarter=angle==90||angle==270;
            float rw=quarter?config.height:config.width,rh=quarter?config.width:config.height;
            float fit=Math.min(vw/rw,vh/rh);
            Matrix matrix=new Matrix();matrix.setScale(config.width/vw,config.height/vh,cx,cy);
            matrix.postRotate(angle,cx,cy);matrix.postScale(fit,fit,cx,cy);if(singleMode&&mirrorPreview)matrix.postScale(-1,1,cx,cy);texture.setTransform(matrix);
        }
        private String label(DebugPrivatePreview.Status status){switch(status){
            case OPENING:return "正在打开指定相机";case PREVIEW:return "本地预览中 · 不保存画面";
            case STOPPING:return "正在停止 · 等待确认关闭";case CLOSED:return "已确认关闭 · 可以重新开始";
            case INVALID_CONFIG:return "参数无效 · 等待关闭确认";case PERMISSION_DENIED:return "未取得相机权限 · 等待关闭确认";
            case BUSY:return "相机或权限请求忙碌 · 保留当前资源，等待关闭确认";
            case NO_CAMERA:return "找不到指定相机 · 等待关闭确认";case UNSUPPORTED_SIZE:return "相机不支持此尺寸 · 等待关闭确认";
            case DEVICE_ERROR:return "相机设备异常 · 等待关闭确认";case CONFIGURATION_FAILED:return "预览配置失败 · 等待关闭确认";
            case TIMEOUT:return "预览或打开超时 · 等待关闭确认";case CLOSE_UNCONFIRMED:return "关闭尚未确认 · 禁止重新开始";
            default:throw new IllegalArgumentException("Unknown preview status");}}
        private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
        private LinearLayout column(){LinearLayout view=new LinearLayout(getContext());view.setOrientation(LinearLayout.VERTICAL);return view;}
        private TextView text(String label,int size,boolean bold){TextView view=new TextView(getContext());view.setText(label);view.setTextSize(size);view.setTextColor(0xff162638);view.setSaveEnabled(false);if(bold)view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return view;}
        private void add(LinearLayout parent,View child,int gap){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(gap);parent.addView(child,params);}
        private GradientDrawable background(){GradientDrawable bg=new GradientDrawable();bg.setColor(Color.WHITE);bg.setCornerRadius(dp(12));bg.setStroke(dp(1),0xffdce4ec);return bg;}
        private LinearLayout card(String title){LinearLayout view=column();view.setPadding(dp(14),dp(16),dp(14),dp(16));view.setBackground(background());view.addView(text(title,20,true));return view;}
        private EditText input(LinearLayout parent,String caption,boolean numeric){add(parent,text(caption,13,false),10);EditText view=new EditText(getContext());view.setSingleLine(true);view.setSaveEnabled(false);view.setInputType(numeric?InputType.TYPE_CLASS_NUMBER:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);add(parent,view,4);return view;}
        private Button button(String caption){Button view=new Button(getContext());view.setText(caption);view.setAllCaps(false);view.setTextSize(16);view.setTextColor(0xff1769b7);view.setMinimumHeight(dp(48));view.setBackground(background());return view;}
    }
}
