package com.triathlon.prep;

/** A single camera borrower keeps a Surface wrapper alive until asynchronous camera close completes. */
final class PreviewLease<T> {
    private final T value;private final Runnable release;
    private java.util.function.Consumer<Boolean> stop;
    private boolean invalid,borrowed,released;
    PreviewLease(T value,Runnable release){if(value==null || release==null)throw new IllegalArgumentException();this.value=value;this.release=release;}
    synchronized T value(){return invalid?null:value;}
    synchronized boolean borrow(Runnable stop){return borrowWithReason(intentional->stop.run());}
    synchronized boolean borrowWithReason(java.util.function.Consumer<Boolean> stop){if(invalid || borrowed) return false;this.stop=stop;borrowed=true;return true;}
    void invalidate() {
        invalidate(false);
    }
    void cancel(){invalidate(true);}
    private void invalidate(boolean intentional) {
        java.util.function.Consumer<Boolean> halt;Runnable dispose;
        synchronized(this){if(invalid)return;invalid=true;halt=stop;dispose=!borrowed?releaseOnce():null;}
        try{if(halt!=null)halt.accept(intentional);}finally{if(dispose!=null)dispose.run();}
    }
    void returned() {
        Runnable dispose;
        synchronized(this){borrowed=false;stop=null;dispose=invalid?releaseOnce():null;}
        if(dispose!=null)dispose.run();
    }
    private Runnable releaseOnce(){if(released)return null;released=true;return release;}
}
