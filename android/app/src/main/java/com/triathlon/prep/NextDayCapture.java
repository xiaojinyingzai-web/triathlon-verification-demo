package com.triathlon.prep;
import com.triathlon.prep.core.nextday.CapturePort;import java.io.IOException;
/** Preview preparation is separate from the staff-authorized still capture. */
interface NextDayCapture extends CapturePort {
 void configure()throws IOException;
 void preparePreview(String operationId,long epoch,long surfaceRevision)throws IOException;
 boolean previewReady(String operationId,long epoch,long surfaceRevision);
 int bufferWidth();int bufferHeight();
 void onChanged(Runnable changed);
}
