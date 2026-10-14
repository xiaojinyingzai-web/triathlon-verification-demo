package com.triathlon.prep;

import com.triathlon.prep.core.Verification;
import com.triathlon.prep.ui.SettingsView;

/** Preparation/assembly status never claims hardware, license, service or paper readiness. */
final class SettingsPresenter {
    static boolean canOpen(Verification.Snapshot state) {
        return RecordsPresenter.canOpen(state);
    }
    static SettingsView.Model model(Verification.Mode mode, String packageName,
            String versionName, long versionCode) {
        return new SettingsView.Model(mode == Verification.Mode.TEST,
                "未接入", "未接入", "未接入", "未核实", "未检查",
                packageName, versionName, versionCode);
    }
    static SettingsView.Model withRuntime(SettingsView.Model model,VerificationRuntime runtime){
        if(model==null||model.simulation||runtime==null||runtime.startupNotice().isEmpty())return model;
        String state=runtime.canBegin()?"已装配，待设备验证":"启动未就绪";
        return new SettingsView.Model(false,state,state,state,runtime.startupNotice(),model.storage,
                model.packageName,model.versionName,model.versionCode);
    }
}
