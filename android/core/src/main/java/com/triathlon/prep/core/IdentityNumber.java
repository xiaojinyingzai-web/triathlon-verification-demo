package com.triathlon.prep.core;

import java.util.GregorianCalendar;
import java.util.Calendar;
import java.util.Locale;

/** Exact document key; never guesses lost Excel digits or a name-based match. */
public final class IdentityNumber {
 public enum Reason { INVALID_ID_LENGTH, INVALID_ID_DATE, INVALID_ID_CHECKSUM }
 public static final class Invalid extends IllegalArgumentException {
  public final Reason reason;
  Invalid(Reason reason){super(reason.name());this.reason=reason;}
 }
 private IdentityNumber(){}
 public static String normalize(String raw) {
  if(raw==null)throw new Invalid(Reason.INVALID_ID_LENGTH);
  String id=raw.trim().toUpperCase(Locale.ROOT);
  if(!id.matches("[1-9][0-9]{16}[0-9X]"))throw new Invalid(Reason.INVALID_ID_LENGTH);
  int year=Integer.parseInt(id.substring(6,10)),month=Integer.parseInt(id.substring(10,12)),day=Integer.parseInt(id.substring(12,14));
  GregorianCalendar birth=new GregorianCalendar();birth.clear();birth.setLenient(false);
  try{birth.set(year,month-1,day);if(year<1800||birth.getTimeInMillis()>System.currentTimeMillis())throw new Invalid(Reason.INVALID_ID_DATE);}
  catch(IllegalArgumentException ex){throw new Invalid(Reason.INVALID_ID_DATE);}
  int[] weights={7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2};int sum=0;
  for(int i=0;i<17;i++)sum+=(id.charAt(i)-'0')*weights[i];
  if(id.charAt(17)!="10X98765432".charAt(sum%11))throw new Invalid(Reason.INVALID_ID_CHECKSUM);
  return id;
 }
}
