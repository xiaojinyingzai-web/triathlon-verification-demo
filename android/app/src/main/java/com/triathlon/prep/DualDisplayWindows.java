package com.triathlon.prep;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

/** Android window owner; content is constructed with each target window's resources. */
final class DualDisplayWindows implements DisplayWindowSession.Port, AutoCloseable {
    interface Content { View create(Context context, DisplayWindowSession.Role role); }
    private final Activity activity;
    private final Content content;
    private final Runnable lost;
    private final DisplayWindowSession session;
    private DisplayWindowSession.Role activityRole;
    DualDisplayWindows(Activity activity, Content content, Runnable lost) {
        this.activity=activity;this.content=content;this.lost=lost;session=new DisplayWindowSession(this);
    }
    boolean sync(DisplayRoutes routes) {
        Display current=activity.getWindowManager().getDefaultDisplay();
        if(routes.staff()==null&&activityRole==DisplayWindowSession.Role.ATHLETE)activity(DisplayWindowSession.Role.SETUP);
        return session.sync(routes,current.getDisplayId());
    }
    @Override public AutoCloseable show(DisplayWindowSession.Role role,int displayId) {
        DisplayManager manager=(DisplayManager)activity.getSystemService(Context.DISPLAY_SERVICE);
        Display target=manager==null?null:manager.getDisplay(displayId);
        if(target==null||!target.isValid()||(target.getFlags()&Display.FLAG_PRESENTATION)==0)
            throw new IllegalStateException("Display does not permit a presentation");
        final boolean[] closing={false};
        Presentation window=new Presentation(activity,target);
        window.setOnDismissListener(dialog->{if(!closing[0])lost.run();});
        if(Adapters.mode()!=com.triathlon.prep.core.Verification.Mode.TEST)
            window.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        try {window.setContentView(content.create(window.getContext(),role));window.show();}
        catch(RuntimeException|LinkageError ex){closing[0]=true;window.dismiss();throw ex;}
        return ()->{closing[0]=true;window.dismiss();};
    }
    @Override public void activity(DisplayWindowSession.Role role) {
        if(activityRole==role)return;
        activity.setContentView(content.create(activity,role));activityRole=role;
    }
    @Override public void close(){session.close();}
}
