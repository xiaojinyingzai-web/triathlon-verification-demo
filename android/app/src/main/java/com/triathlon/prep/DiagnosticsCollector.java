package com.triathlon.prep;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.Size;
import com.triathlon.prep.core.Verification;
import com.triathlon.prep.ui.DiagnosticsView;
import java.util.ArrayList;
import java.util.List;

/** Worker-only observations. No camera open, permissions request, native SDK or persistent writes. */
final class DiagnosticsCollector {
    static DiagnosticsView.Model collect(Context app,Verification.Mode mode) {
        String version;
        try {
            PackageInfo p=app.getPackageManager().getPackageInfo(app.getPackageName(),0);
            long code=Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode;
            version=DiagnosticsPresenter.version(p.packageName,p.versionName,code,mode);
        } catch(Exception ex){version="应用版本不可读取（"+ex.getClass().getSimpleName()+"）\n模式："+(mode==Verification.Mode.TEST?"虚构测试包":"正式未启用包");}
        String abi=Build.SUPPORTED_ABIS==null || Build.SUPPORTED_ABIS.length==0?"未知 / 未读取":String.join("、",Build.SUPPORTED_ABIS);
        List<DiagnosticsView.Camera> cameras=new ArrayList<>();
        String status;
        try {
            boolean granted=app.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED;
            String permission=granted?"CAMERA 已授予；":"CAMERA 未授予；仅查询可读静态字段，受限项保持未知；";
            CameraManager manager=(CameraManager)app.getSystemService(Context.CAMERA_SERVICE);
            if(manager==null)status="CameraManager 不可读取（系统服务缺失）；未打开相机。";
            else {
                String[] ids=manager.getCameraIdList();
                if(ids==null)status=permission+"相机 ID 列表不可读取；未打开相机。";
                else if(ids.length==0)status=permission+"系统当前未列出相机；不等于物理设备没有相机。";
                else {
                    int failed=0;
                    for(String id:ids) {
                        try {
                            CameraCharacteristics c=manager.getCameraCharacteristics(id);
                            StreamConfigurationMap map=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                            Size[] raw=map==null?null:map.getOutputSizes(ImageFormat.YUV_420_888);
                            List<String> sizes=null;
                            if(raw!=null){sizes=new ArrayList<>();for(Size size:raw)sizes.add(size.getWidth()+"×"+size.getHeight());}
                            Boolean zsl=Build.VERSION.SDK_INT>=26?Api26.hasKey(c):null;
                            cameras.add(new DiagnosticsView.Camera(id,DiagnosticsPresenter.direction(c.get(CameraCharacteristics.LENS_FACING),c.get(CameraCharacteristics.SENSOR_ORIENTATION)),
                                    DiagnosticsPresenter.sizes(sizes),DiagnosticsPresenter.zsl(Build.VERSION.SDK_INT,zsl)));
                        }catch(Exception ex){failed++;cameras.add(new DiagnosticsView.Camera(id,"不可读取（"+reason(ex)+"）",null,null));}
                    }
                    status=permission+"读取 "+ids.length+" 个相机的静态声明"+(failed>0?"；其中 "+failed+" 个查询失败":"")+"；未请求权限、未打开相机。";
                }
            }
        }catch(Exception ex){status="相机静态信息不可读取（"+reason(ex)+"）；未请求权限、未打开相机。";}
        return DiagnosticsPresenter.model(mode,false,DiagnosticsPresenter.environment(Build.MODEL),
                "Android "+DiagnosticsPresenter.value(Build.VERSION.RELEASE)+" / API "+Build.VERSION.SDK_INT,
                "厂商："+DiagnosticsPresenter.value(Build.MANUFACTURER)+"\n品牌："+DiagnosticsPresenter.value(Build.BRAND)+"\n型号："+DiagnosticsPresenter.value(Build.MODEL),
                abi,version,status,cameras);
    }
    static DiagnosticsView.Model failed(Verification.Mode mode) {
        return DiagnosticsPresenter.model(mode,false,null,null,null,null,null,"诊断读取异常；静态信息未知，可重新刷新。",new ArrayList<>());
    }
    private static String reason(Exception ex) {
        if(ex instanceof SecurityException)return "系统拒绝权限";
        if(ex instanceof CameraAccessException)return "CameraAccessException / reason="+((CameraAccessException)ex).getReason();
        return ex.getClass().getSimpleName(); // No raw SDK/device strings or unique identifiers.
    }
    private static final class Api26 {
        static Boolean hasKey(CameraCharacteristics c) {
            List<CaptureRequest.Key<?>> keys=c.getAvailableCaptureRequestKeys();
            return keys==null?null:keys.contains(CaptureRequest.CONTROL_ENABLE_ZSL);
        }
    }
}
