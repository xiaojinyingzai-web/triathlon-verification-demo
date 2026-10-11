package com.triathlon.prep;

import com.triathlon.prep.core.Verification;
import com.triathlon.prep.ui.DiagnosticsView;
import java.util.Collections;
import java.util.List;

/** Formats observations, never infers device/algorithm readiness from packaged libraries. */
final class DiagnosticsPresenter {
    static DiagnosticsView.Model loading(Verification.Mode mode) {
        return model(mode,true,null,null,null,null,null,null,Collections.emptyList());
    }
    static DiagnosticsView.Model model(Verification.Mode mode,boolean refreshing,String environment,
            String system,String brand,String abi,String version,String status,List<DiagnosticsView.Camera> cameras) {
        return new DiagnosticsView.Model(mode==Verification.Mode.TEST,refreshing,environment,system,brand,abi,version,status,cameras);
    }
    static String value(String value){return value==null || value.trim().isEmpty()?"未知 / 未读取":value;}
    static String environment(String model) {
        if(model!=null && (model.contains("Android SDK built for") || model.startsWith("sdk_gphone") || model.contains("Emulator")))
            return "系统型号带模拟器标记；本机 Android 静态信息，非 CEFK 真机实测";
        return "本机 Android 系统报告；实际设备配置待核实，非 CEFK 真机实测";
    }
    static String version(String packageName,String name,Long code,Verification.Mode mode) {
        return "包名："+value(packageName)+"\n版本："+value(name)+"\n编号："+(code==null?"未知 / 未读取":code)
                +"\n模式："+(mode==Verification.Mode.TEST?"虚构测试包":"正式未启用包");
    }
    static String direction(Integer facing,Integer sensor) {
        String side=facing==null?"朝向未知":facing==0?"前置":facing==1?"后置":facing==2?"外接":"朝向未知（系统值 "+facing+"）";
        return side+"；传感器方向："+(sensor!=null && (sensor==0||sensor==90||sensor==180||sensor==270)?sensor+"°":"未知 / 未读取")
                +"（不推断 RGB/IR 或画面旋转/镜像）";
    }
    static String sizes(List<String> sizes) {
        if(sizes==null)return "未知 / 字段不可读取";
        if(sizes.isEmpty())return "系统未声明 YUV_420_888 输出尺寸";
        // Full values remain readable; no silent truncation of supported modes.
        return sizes.size()+" 种（静态声明）\n"+String.join("、",sizes);
    }
    static String zsl(int api,Boolean key) {
        if(api<26)return "API "+api+" < 26；当前抓拍链路不支持";
        if(key==null)return "API "+api+"；ZSL 键列表不可读取，支持情况未知";
        return key?"API "+api+" ≥ 26；ZSL 键已声明（未执行禁用或采集）":"API "+api+"；未声明 ZSL 键，当前抓拍链路不支持";
    }
}
