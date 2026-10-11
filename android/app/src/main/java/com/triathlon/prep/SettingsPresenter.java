package com.triathlon.prep;

import com.triathlon.prep.core.Verification;
import com.triathlon.prep.ui.SettingsView;

/** Current adapters have no real device or authorization probes. No storage operations. */
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
}
