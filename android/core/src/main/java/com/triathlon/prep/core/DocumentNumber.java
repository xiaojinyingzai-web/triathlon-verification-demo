package com.triathlon.prep.core;
import java.util.Locale;
/** Roster key classification only. Passport syntax is not evidence of document authenticity. */
public final class DocumentNumber {
 public enum Type { ID_CARD,PASSPORT }
 private DocumentNumber(){}
 public static String normalize(Type type,String raw){if(type==null||raw==null)throw new IllegalArgumentException("Document type/number required");if(type==Type.ID_CARD)return IdentityNumber.normalize(raw);String value=raw.trim().toUpperCase(Locale.ROOT);if(!value.matches("[A-Z0-9]{1,64}"))throw new IllegalArgumentException("Passport key must be bounded letters/digits");return value;}
 public static String key(Type type,String raw){return type.name()+":"+normalize(type,raw);}
}
