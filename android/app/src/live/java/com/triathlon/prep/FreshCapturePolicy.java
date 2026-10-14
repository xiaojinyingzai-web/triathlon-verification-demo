package com.triathlon.prep;

/** Known ZSL control or separately proved sensor watermark; never mix unknown clock domains. */
final class FreshCapturePolicy {
    /** Missing metadata remains blocked. A known absent key requires the separate sensor proof. */
    static boolean controlled(int sdk,Boolean keyAvailable)throws CameraModule.Failure {
        if(sdk<26||keyAvailable==null)throw new CameraModule.Failure(CameraModule.Error.CONFIGURATION_FAILED);
        return keyAvailable;
    }
    interface Control { void set(boolean enabled); Boolean get(); }
    static void requireSupported(int sdk, boolean keyAvailable) throws CameraModule.Failure {
        if (sdk < 26 || !keyAvailable) throw new CameraModule.Failure(CameraModule.Error.CONFIGURATION_FAILED);
    }
    static void disableZsl(int sdk, boolean keyAvailable, Control control) throws CameraModule.Failure {
        requireSupported(sdk, keyAvailable);
        control.set(false);
        if (!Boolean.FALSE.equals(control.get())) throw new CameraModule.Failure(CameraModule.Error.CONFIGURATION_FAILED);
    }
}
