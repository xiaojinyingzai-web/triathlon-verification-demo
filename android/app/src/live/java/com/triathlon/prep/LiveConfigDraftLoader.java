package com.triathlon.prep;
import android.content.Context;import com.triathlon.prep.core.*;import com.triathlon.prep.cefk.face.FaceClient;import java.io.File;
/** Maps only complete public parameters. Execution/credentials/manufacturer confirmation stay absent. */
final class LiveConfigDraftLoader {
 private LiveConfigDraftLoader(){}
 static LiveRuntime.Config load(Context app){if(app==null)return null;try{return map(new FileDeviceConfigStore(new File(app.getFilesDir(),"device-config")).load().current);}catch(Exception error){return null;}}
 static LiveRuntime.Config map(DeviceConfigDraft draft){
  if(draft==null||!draft.review().validForSave||!draft.review().parametersComplete)return null;
  try{
   CameraModule.Config camera=new CameraModule.Config(v(draft,DeviceConfigDraft.Field.CAMERA_ID),i(draft,DeviceConfigDraft.Field.PREVIEW_WIDTH),i(draft,DeviceConfigDraft.Field.PREVIEW_HEIGHT),i(draft,DeviceConfigDraft.Field.JPEG_WIDTH),i(draft,DeviceConfigDraft.Field.JPEG_HEIGHT),i(draft,DeviceConfigDraft.Field.CAMERA_DISPLAY_ROTATION),i(draft,DeviceConfigDraft.Field.CAMERA_DEVICE_ROTATION),v(draft,DeviceConfigDraft.Field.EXTERNAL_ROTATION).isEmpty()?null:i(draft,DeviceConfigDraft.Field.EXTERNAL_ROTATION));
   ExternalPhoto.Config images=new ExternalPhoto.Config(i(draft,DeviceConfigDraft.Field.EXTERNAL_MAX_EDGE),i(draft,DeviceConfigDraft.Field.EXTERNAL_JPEG_QUALITY),i(draft,DeviceConfigDraft.Field.EXTERNAL_MAX_BYTES));
   FaceClient.Config face=new FaceClient.Config(FaceClient.Mode.REAL,v(draft,DeviceConfigDraft.Field.FACE_ENDPOINT),v(draft,DeviceConfigDraft.Field.DEVICE_NUMBER),v(draft,DeviceConfigDraft.Field.DEVICE_NAME),FaceClient.ByteFormat.valueOf(v(draft,DeviceConfigDraft.Field.BYTE_FORMAT)),FaceClient.SignCase.valueOf(v(draft,DeviceConfigDraft.Field.SIGN_CASE)),false,false,false,i(draft,DeviceConfigDraft.Field.FACE_TIMEOUT),i(draft,DeviceConfigDraft.Field.MAX_IMAGE_BYTES),i(draft,DeviceConfigDraft.Field.TOKEN_TTL));
   CefkFaceAdapter.Parameters decision=draft.scoresOnly()?CefkFaceAdapter.Parameters.scoresOnly():new CefkFaceAdapter.Parameters(Double.parseDouble(v(draft,DeviceConfigDraft.Field.LIVE_THRESHOLD)),Verification.ScoreRule.valueOf(v(draft,DeviceConfigDraft.Field.LIVE_RULE)),Double.parseDouble(v(draft,DeviceConfigDraft.Field.FACE_THRESHOLD)),Verification.ScoreRule.valueOf(v(draft,DeviceConfigDraft.Field.FACE_RULE)),false);
   CheckInReceipt.Layout layout=draft.scoresOnly()?null:new CheckInReceipt.Layout(i(draft,DeviceConfigDraft.Field.PRINTER_DOTS),i(draft,DeviceConfigDraft.Field.LATIN_GLYPH_DOTS),i(draft,DeviceConfigDraft.Field.WIDE_GLYPH_DOTS));
   LiveRuntime.PrinterConfig printer=draft.scoresOnly()?null:new LiveRuntime.PrinterConfig(i(draft,DeviceConfigDraft.Field.PRINTER_PRODUCT_ID),i(draft,DeviceConfigDraft.Field.PRINTER_VENDOR_ID),i(draft,DeviceConfigDraft.Field.PRINTER_DOTS),v(draft,DeviceConfigDraft.Field.PRINTER_ENCODING),layout);
   // CshtReaderFactory.Configuration requires a confirmed credentials policy; do not invent empty keys.
   return new LiveRuntime.Config(null,null,face,null,decision,printer,i(draft,DeviceConfigDraft.Field.READER_TIMEOUT),i(draft,DeviceConfigDraft.Field.STAGE_TIMEOUT),draft.scoresOnly()?15000:i(draft,DeviceConfigDraft.Field.PRINT_TIMEOUT),false,false,false,camera,i(draft,DeviceConfigDraft.Field.PREVIEW_STABLE),i(draft,DeviceConfigDraft.Field.PREVIEW_FRESH),images);
  }catch(IllegalArgumentException error){return null;}
 }
 private static String v(DeviceConfigDraft d,DeviceConfigDraft.Field f){return d.value(f).trim();}
 private static int i(DeviceConfigDraft d,DeviceConfigDraft.Field f){return Integer.parseInt(v(d,f));}
}
