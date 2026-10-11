package com.triathlon.prep.intake;

import com.triathlon.prep.core.Verification;

/** Persist BEFORE starting a business session. Not a completed-verification or printed assertion. */
public interface CardAdmissionFence {
    enum Result { ADMITTED, SAME_CARD, FAILED }
    Result admit(Verification.Mode mode, String normalizedNumber) throws Exception;
    /** Only a staff action may invoke this; never a timer, card-search failure, or lifecycle restore. */
    boolean explicitReverify(Verification.Mode mode) throws Exception;
}
