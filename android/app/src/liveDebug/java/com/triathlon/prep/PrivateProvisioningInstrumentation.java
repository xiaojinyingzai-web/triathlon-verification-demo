package com.triathlon.prep;
import android.app.Instrumentation;import android.os.Bundle;
/** Debug maintenance endpoint, explicitly launched by authorized ADB; never started by application UI. */
public final class PrivateProvisioningInstrumentation extends Instrumentation {
    private String operation="";
    @Override public void onCreate(Bundle arguments){operation=arguments==null?"":arguments.getString("operation","");start();}
    @Override public void onStart(){Bundle result=new Bundle();
        try{
            if("describe".equals(operation)){result.putString("publicDescription",PrivateProvisioningController.describe(getTargetContext()));result.putString("status","DESCRIBED");}
            else if("provision".equals(operation)){result.putString("status",PrivateProvisioningController.consume(getTargetContext()).name());}
            else if("verify".equals(operation)){LiveProductionConfigLoader.Result prepared=LiveProductionConfigLoader.load(getTargetContext());result.putString("status",prepared.ready()?"READY":"REFUSED");result.putString("diagnosis",prepared.notice());}
            else if("describe-face-profile".equals(operation)){result.putString("profileTarget",ProfileMaintenanceController.describe(getTargetContext()));result.putString("status","LOCAL_INSTALLATION_DESCRIBED");}
            else if("register-face-target".equals(operation)){ProfileMaintenanceController.registerTarget(getTargetContext());result.putString("status","TARGET_RECORDED_NOT_OFFICIAL_EVIDENCE");}
            else if("install-face-profile".equals(operation)){ProfileMaintenanceController.install(getTargetContext());result.putString("status","PROFILE_SAVED_AUTHENTICATED_NOT_RUNTIME_READY");}
            else if("verify-face-profile".equals(operation)){result.putString("diagnosis",ProfileMaintenanceController.verify(getTargetContext()));result.putString("status","PROFILE_AUTHENTICATED_NOT_RUNTIME_READY");}
            // PUBLIC_CONFIG_F1: new public-config commands remain disabled pending strict JSON repair/QA.
            else result.putString("status","REFUSED");
        }catch(Exception error){result.putString("status","REFUSED");result.putString("diagnosis","Private maintenance failed; credential values are not returned.");}
        finish(-1,result);
    }
}
