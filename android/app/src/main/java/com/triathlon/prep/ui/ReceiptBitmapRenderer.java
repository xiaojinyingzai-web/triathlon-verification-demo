package com.triathlon.prep.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import com.triathlon.prep.core.CheckInReceipt;
import com.triathlon.prep.core.ReceiptPrintController;
import com.triathlon.prep.core.ReceiptTemplate;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** One dot-based layout for preview paper pixels and the frozen printable PNG. No I/O. */
public final class ReceiptBitmapRenderer implements ReceiptPrintController.GraphicRenderer {
    private interface Op { void draw(Canvas canvas); }
    private static final class Page {
        final int width;final float scale,margin,inner;final List<Op> ops=new ArrayList<>();float y;
        Page(int width){this.width=width;scale=width/384f;margin=Math.max(2,16*scale);inner=width-2*margin;
            if(inner<=0)throw new IllegalArgumentException("receipt width too narrow");y=margin;}
        Paint paint(ReceiptTemplate template,ReceiptTemplate.Section section){
            ReceiptTemplate.Style style=template.style(section);float base;
            switch(section){case HEADING:base=27;break;case SUBTITLE:base=25;break;case BIB:base=55;break;case FOOTER:base=22;break;case STEPS:base=21;break;default:base=18;break;}
            float multiplier=style.size==ReceiptTemplate.FontSize.SMALL?.85f:style.size==ReceiptTemplate.FontSize.LARGE?1.2f:1f;
            Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.BLACK);paint.setTextSize(Math.max(1,base*multiplier*scale));
            paint.setTypeface(style.bold?Typeface.create(Typeface.DEFAULT,Typeface.BOLD):Typeface.DEFAULT);return paint;
        }
        float gap(ReceiptTemplate.Style style){return (style.spacing==ReceiptTemplate.Spacing.TIGHT?7:style.spacing==ReceiptTemplate.Spacing.LOOSE?16:11)*scale;}
        float height(Paint paint){Paint.FontMetrics fm=paint.getFontMetrics();return fm.descent-fm.ascent+3*scale;}
        List<String> wrap(String value,Paint paint,float available){
            if(value==null||available<=0)throw new IllegalArgumentException("text/width required");
            List<String> lines=new ArrayList<>();StringBuilder line=new StringBuilder();
            for(int i=0;i<value.length();){int cp=value.codePointAt(i);i+=Character.charCount(cp);
                if(cp=='\n'){lines.add(line.toString());line.setLength(0);continue;}
                String next=new String(Character.toChars(cp));
                if(line.length()>0&&paint.measureText(line.toString()+next)>available){lines.add(line.toString());line.setLength(0);}
                if(paint.measureText(next)>available)throw new IllegalArgumentException("glyph exceeds receipt column");line.append(next);
            }
            if(line.length()>0||lines.isEmpty())lines.add(line.toString());return lines;
        }
        float block(String value,Paint paint,float x,float top,float available,ReceiptTemplate.Align align){
            List<String> lines=wrap(value,paint,available);float lineHeight=height(paint);Paint.FontMetrics fm=paint.getFontMetrics();
            for(int i=0;i<lines.size();i++){final String line=lines.get(i);final float lineX=x+(align==ReceiptTemplate.Align.CENTER?(available-paint.measureText(line))/2:align==ReceiptTemplate.Align.RIGHT?available-paint.measureText(line):0);
                final float baseline=top+i*lineHeight-fm.ascent;final Paint copy=new Paint(paint);ops.add(canvas->canvas.drawText(line,lineX,baseline,copy));}
            return lines.size()*lineHeight;
        }
        void text(String value,ReceiptTemplate template,ReceiptTemplate.Section section){if(value.isEmpty())return;
            Paint paint=paint(template,section);ReceiptTemplate.Style style=template.style(section);
            y+=block(value,paint,margin,y,inner,style.align)+gap(style);bound();}
        void divider(){y+=10*scale;final float lineY=y;Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.BLACK);paint.setStrokeWidth(Math.max(1,scale));paint.setPathEffect(new DashPathEffect(new float[]{6*scale,4*scale},0));
            ops.add(canvas->canvas.drawLine(margin,lineY,width-margin,lineY,paint));y+=14*scale;bound();}
        void field(String label,String value,ReceiptTemplate template){if(!label.endsWith("：")&&!label.endsWith(":"))label+="：";Paint paint=paint(template,ReceiptTemplate.Section.FIELDS);ReceiptTemplate.Style style=template.style(ReceiptTemplate.Section.FIELDS);
            float labelWidth=inner*.3f;float a=block(label,paint,margin,y,labelWidth-6*scale,ReceiptTemplate.Align.LEFT);
            float b=block(value,paint,margin+labelWidth,y,inner-labelWidth,style.align);y+=Math.max(a,b)+gap(style);bound();}
        void step(String label,boolean checked,ReceiptTemplate template){Paint paint=paint(template,ReceiptTemplate.Section.STEPS);ReceiptTemplate.Style style=template.style(ReceiptTemplate.Section.STEPS);
            final float box=24*scale;float lineHeight=block(label,paint,margin,y,inner-box-12*scale,style.align);
            final float left=width-margin-box,top=y+Math.max(0,(lineHeight-box)/2);final Paint border=new Paint(Paint.ANTI_ALIAS_FLAG);border.setColor(Color.BLACK);border.setStyle(Paint.Style.STROKE);border.setStrokeWidth(Math.max(1,1.6f*scale));
            ops.add(canvas->{canvas.drawRect(left,top,left+box,top+box,border);if(checked){Path path=new Path();path.moveTo(left+box*.18f,top+box*.5f);path.lineTo(left+box*.43f,top+box*.76f);path.lineTo(left+box*.85f,top+box*.2f);canvas.drawPath(path,border);}});
            y+=Math.max(lineHeight,box)+gap(style);bound();}
        void schedule(ReceiptTemplate template){Paint paint=paint(template,ReceiptTemplate.Section.SCHEDULE);ReceiptTemplate.Style style=template.style(ReceiptTemplate.Section.SCHEDULE);
            final float top=y,left=margin+8*scale;float labelWidth=Math.min(inner*.28f,80*scale);float available=inner-20*scale;
            ReceiptTemplate.TextKey[] labels={ReceiptTemplate.TextKey.DATE_LABEL,ReceiptTemplate.TextKey.TIME_LABEL};ReceiptTemplate.TextKey[] values={ReceiptTemplate.TextKey.DATE_VALUE,ReceiptTemplate.TextKey.TIME_VALUE};
            for(int i=0;i<2;i++){float a=block(template.text(labels[i]),paint,left+8*scale,y,labelWidth,ReceiptTemplate.Align.LEFT);
                float b=block(template.text(values[i]),paint,left+8*scale+labelWidth,y,available-labelWidth,style.align);y+=Math.max(a,b)+4*scale;}
            String reminder=template.text(ReceiptTemplate.TextKey.REMINDER);if(!reminder.isEmpty())y+=block(reminder,paint,left+8*scale,y,available,style.align);
            final float bottom=y;Paint stroke=new Paint();stroke.setColor(Color.BLACK);stroke.setStrokeWidth(Math.max(1,scale));ops.add(canvas->canvas.drawLine(left,top,left,bottom,stroke));y+=gap(style);bound();}
        void bound(){if(y>8192||(long)width*(long)Math.ceil(y)>4000000)throw new IllegalArgumentException("receipt exceeds safe bitmap size");}
    }
    @Override public ReceiptPrintController.Raster render(CheckInReceipt.Document document,CheckInReceipt.Layout layout){
        if(document==null||document.template==null||layout==null)throw new IllegalArgumentException("document/template/layout required");
        int width=layout.printableDots;if(width>2048)throw new IllegalArgumentException("receipt width exceeds raster limit");
        Page page=new Page(width);ReceiptTemplate template=document.template;
        page.text(document.eventHeading,template,ReceiptTemplate.Section.HEADING);page.text(document.title,template,ReceiptTemplate.Section.SUBTITLE);page.divider();
        page.field(template.text(ReceiptTemplate.TextKey.NAME_LABEL),document.name,template);
        page.field(template.text(ReceiptTemplate.TextKey.TEAM_LABEL),document.team.isEmpty()?"未提供":document.team,template);
        page.field(template.text(ReceiptTemplate.TextKey.GROUP_LABEL),document.group,template);
        page.y+=10*page.scale;
        Paint bibLabel=page.paint(template,ReceiptTemplate.Section.FIELDS);page.y+=page.block(template.text(ReceiptTemplate.TextKey.BIB_LABEL),bibLabel,page.margin,page.y,page.inner,ReceiptTemplate.Align.CENTER)+8*page.scale;
        page.text(document.bib,template,ReceiptTemplate.Section.BIB);page.divider();int number=1;
        for(CheckInReceipt.Step step:document.steps){if(!step.visible)continue;page.step((number++)+". "+step.label,step.checked,template);
            if(step.item==CheckInReceipt.Item.SWIMMING&&document.showDeepWaterSchedule)page.schedule(template);}
        page.divider();page.text(template.text(ReceiptTemplate.TextKey.WISH),template,ReceiptTemplate.Section.FOOTER);page.y+=page.margin;page.bound();int paperHeight=Math.max(1,(int)Math.ceil(page.y));
        if(document.simulation){page.divider();Paint test=new Paint(Paint.ANTI_ALIAS_FLAG);test.setColor(Color.BLACK);test.setTextSize(18*page.scale);test.setTypeface(Typeface.DEFAULT_BOLD);
            page.y+=page.block("模拟测试 · 非有效凭条",test,page.margin,page.y,page.inner,ReceiptTemplate.Align.CENTER)+page.margin;page.bound();}
        int height=Math.max(paperHeight,(int)Math.ceil(page.y));Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        try{Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.WHITE);for(Op op:page.ops)op.draw(canvas);
            ByteArrayOutputStream output=new ByteArrayOutputStream();if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,output))throw new IllegalStateException("PNG encoding failed");
            return new ReceiptPrintController.Raster(output.toByteArray(),width,height,paperHeight);
        }finally{bitmap.recycle();}
    }
    public static Bitmap paperBitmap(ReceiptPrintController.Raster raster){
        if(raster==null)throw new IllegalArgumentException("raster required");byte[] png=raster.pngBytes();BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(png,0,png.length,bounds);
        if(bounds.outWidth!=raster.width||bounds.outHeight!=raster.height)throw new IllegalArgumentException("PNG dimensions mismatch");
        Bitmap full=BitmapFactory.decodeByteArray(png,0,png.length);if(full==null)throw new IllegalArgumentException("invalid PNG");
        try{Bitmap paper=Bitmap.createBitmap(full,0,0,raster.width,raster.paperHeight);if(paper==full)return full;full.recycle();return paper;}
        catch(RuntimeException error){full.recycle();throw error;}
    }
}
