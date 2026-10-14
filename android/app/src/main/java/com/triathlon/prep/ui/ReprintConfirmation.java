package com.triathlon.prep.ui;

import android.app.AlertDialog;
import android.content.Context;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** UI-only, one-shot confirmation. Host must independently validate and consume its token. */
final class ReprintConfirmation {
    static final class Ticket { final long generation;Ticket(long generation){this.generation=generation;} }
    static final class Gate {
        private String identity="",token="";private long revision,generation;
        private boolean allowed,consumed,opened;
        void update(String identity,String token,long revision,boolean allowed){
            identity=identity==null?"":identity;token=token==null?"":token;
            if(!this.identity.equals(identity)||!this.token.equals(token)||this.revision!=revision){close();consumed=false;}
            this.identity=identity;this.token=token;this.revision=revision;
            this.allowed=allowed&&!identity.isEmpty()&&!token.isEmpty()&&revision>=0;
            if(!this.allowed)close();
        }
        boolean available(){return allowed&&!consumed&&!opened;}
        Ticket open(){if(!available())return null;opened=true;return new Ticket(++generation);}
        boolean valid(Ticket ticket){return ticket!=null&&ticket.generation==generation&&allowed&&!consumed&&opened;}
        boolean consume(Ticket ticket){if(!valid(ticket))return false;consumed=true;opened=false;return true;}
        void close(){opened=false;generation++;}
    }
    private final Gate gate=new Gate();private AlertDialog dialog;private Ticket currentTicket;private Runnable onClosed=()->{};
    void setOnClosed(Runnable callback){onClosed=callback;}
    void update(String identity,String token,long revision,boolean allowed){gate.update(identity,token,revision,allowed);if(dialog!=null&&!gate.valid(currentTicket))dismiss();}
    boolean available(){return gate.available();}
    void request(Context context,boolean requiresConfirmation,BooleanSupplier valid,Consumer<Boolean> action){
        Ticket ticket=gate.open();if(ticket==null)return;
        if(!requiresConfirmation){if(valid.getAsBoolean()&&gate.consume(ticket))action.accept(false);else gate.close();return;}
        currentTicket=ticket;
        dialog=new AlertDialog.Builder(context).setTitle("确认补打小票")
                .setMessage("打印机可能已有缓存出纸。请先现场核对，确认仍需补打。")
                .setNegativeButton("取消",(d,w)->gate.close())
                .setPositiveButton("确认补打",(d,w)->{if(valid.getAsBoolean()&&gate.consume(ticket))action.accept(true);})
                .create();
        dialog.setOnDismissListener(d->{if(currentTicket==ticket){dialog=null;currentTicket=null;gate.close();onClosed.run();}});
        dialog.show();
    }
    void dismiss(){gate.close();AlertDialog old=dialog;dialog=null;currentTicket=null;if(old!=null){old.dismiss();onClosed.run();}}
}
