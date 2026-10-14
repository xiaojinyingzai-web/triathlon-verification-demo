package com.triathlon.prep;
import android.view.View;import android.app.Activity;

/** Reader-only diagnostic ABI. No SDK, credential, camera, service or record execution. */
public final class ReaderOnlyContract {
 private ReaderOnlyContract(){}
 public enum Phase { NEEDS_PRIVATE_INPUT, SERVICE_REVIEW_PENDING, PREPARED, INITIALIZING,
  WAITING_CARD, READ_OK, MATCHED, NOT_LISTED, NO_ROSTER, FAILED, CLOSING, CLOSED, CLOSE_UNCONFIRMED }
 public interface Actions { void prepare();void readOnce();void cancel();void exit(); }
 public interface Panel { View view();void render(Model model);void clearPersonalResult(); }
 public interface PanelFactory { Panel create(Activity activity,Actions actions); }
 public static final class Model {
  public final Phase phase;
  public final Phase resultPhase;public final long sessionEpoch;
  public final String maskedNumber,cardName,matchedName,bib,batchId,code;
  public Model(Phase phase,String maskedNumber,String cardName,String matchedName,String bib,String batchId,String code){
   this(phase,maskedNumber,cardName,matchedName,bib,batchId,code,phase,0);
  }
  public Model(Phase phase,String maskedNumber,String cardName,String matchedName,String bib,String batchId,String code,Phase resultPhase,long sessionEpoch){
   if(phase==null)throw new IllegalArgumentException("Reader phase required");
   this.phase=phase;
   this.resultPhase=resultPhase==null?phase:resultPhase;this.sessionEpoch=sessionEpoch;
   String number=maskedNumber==null?"":maskedNumber;
   if(!number.isEmpty()&&!number.matches("\\*{14}[0-9X]{4}"))throw new IllegalArgumentException("Masked document only");
   this.maskedNumber=number;this.cardName=text(cardName,128);this.matchedName=text(matchedName,128);
   this.bib=text(bib,64);this.batchId=text(batchId,128);this.code=text(code,64);
  }
  private static String text(String value,int limit){String t=value==null?"":value;if(t.length()>limit||t.matches("(?s).*[\\p{Cntrl}].*"))throw new IllegalArgumentException("Invalid display field");return t;}
 }
}
