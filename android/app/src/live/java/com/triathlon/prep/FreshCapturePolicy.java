package com.triathlon.prep;

/** No clock comparison across unknown camera timebases. Require the standard ZSL control. */
final class FreshCapturePolicy {
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
