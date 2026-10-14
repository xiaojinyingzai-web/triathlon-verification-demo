package com.triathlon.prep.ui;
/** Local one-shot presentation ticket; never substitutes for Host authorization. */
final class UiActionTicket {
    private String token="";private long revision;private boolean consumed;
    void update(String token,long revision){if(token==null||revision<0)throw new IllegalArgumentException("Invalid UI ticket");if(!this.token.equals(token)||this.revision!=revision)consumed=false;this.token=token;this.revision=revision;}
    boolean available(){return !token.isEmpty()&&!consumed;}
    boolean take(){if(!available())return false;consumed=true;return true;}
    void clear(){consumed=true;}
}
