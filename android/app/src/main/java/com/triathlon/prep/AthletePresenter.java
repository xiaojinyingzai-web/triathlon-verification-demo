package com.triathlon.prep;

import com.triathlon.prep.core.Verification;
import com.triathlon.prep.ui.AthleteView;

/** Only current public event identity and an assessed outcome cross the participant boundary. */
final class AthletePresenter {
    static AthleteView.Model model(Verification.Snapshot s,Verification.Mode mode,boolean waiting) {
        boolean test=mode==Verification.Mode.TEST;
        if(s==null||s.stage==Verification.Stage.IDLE||s.result!=null&&s.result.verdict==Verification.Verdict.CANCELLED)
            return new AthleteView.Model(test,null,waiting?AthleteView.Phase.WAITING_CARD:AthleteView.Phase.PAUSED,null,null,null,null);
        AthleteView.Phase phase;
        String message=null;
        switch(s.stage){
            case READING:phase=AthleteView.Phase.READING;break;
            case CAPTURING:phase=AthleteView.Phase.ALIGN;break;
            case ANALYZING:phase=AthleteView.Phase.PROCESSING;break;
            default:
                if(s.saveState!=Verification.SaveState.SAVED){phase=AthleteView.Phase.NEED_STAFF;message="本次记录尚未保存，请联系工作人员";}
                else if(s.result==null||s.result.eventVerdict==null)phase=AthleteView.Phase.NEED_STAFF;
                else if(s.result.eventVerdict==Verification.Verdict.PASSED)phase=AthleteView.Phase.PASSED;
                else if(s.result.eventVerdict==Verification.Verdict.REJECTED)phase=AthleteView.Phase.REJECTED;
                else phase=AthleteView.Phase.NEED_STAFF;
        }
        return new AthleteView.Model(test,s.sessionId,phase,s.eventName,
            s.athlete==null?null:s.athlete.name,s.athlete==null?null:s.athlete.bib,message);
    }
}
