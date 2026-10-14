package com.triathlon.prep.core;
/** Issued only from a committed manual selection. Automatic receipt eligibility never accepts it. */
public final class ManualPrintAuthorization {
 public final ManualRecord record;public final Verification.Mode mode;public final String eventName;public final RaceRoster.Athlete athlete;public final boolean reprint;
 private final ManualPassportCheck owner;private final String token;
 ManualPrintAuthorization(ManualPassportCheck owner,String token,ManualRecord record,String event,RaceRoster.Athlete athlete,boolean reprint){this.owner=owner;this.token=token;this.record=record;mode=record.mode;eventName=event;this.athlete=athlete;this.reprint=reprint;}
 public boolean current(){return owner.authorizationCurrent(token,record);}
}
