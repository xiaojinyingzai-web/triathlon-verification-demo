package com.triathlon.prep;
import android.content.Context;import android.content.SharedPreferences;
import com.triathlon.prep.core.CheckInReceipt;
/** Heading only, separate from roster and print reservation data. Off UI thread; synchronous commit. */
public final class ReceiptNamePreferences implements ReceiptNameSetting.Store {
 public static final String PREFERENCES_NAME="triathlon_receipt_name_v1";
 private static final Object LOCK=new Object();private final SharedPreferences preferences;
 public ReceiptNamePreferences(Context context){preferences=context.getApplicationContext().getSharedPreferences(PREFERENCES_NAME,Context.MODE_PRIVATE);}
 @Override public String load(){synchronized(LOCK){return preferences.getString("heading","");}}
 @Override public boolean save(String value){
  String next=CheckInReceipt.normalizeHeading(value);
  synchronized(LOCK){String previous;
   try{previous=preferences.getString("heading","");}catch(RuntimeException ex){return false;}
   try{if(preferences.edit().putString("heading",next).commit())return true;}catch(RuntimeException ignored){}
   // Failed commit can already have changed Android's memory cache; retain the previous effective value.
   try{preferences.edit().putString("heading",previous).commit();}catch(RuntimeException ignored){}
   return false;
  }
 }
}
