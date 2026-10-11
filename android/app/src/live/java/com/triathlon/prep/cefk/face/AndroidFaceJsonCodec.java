package com.triathlon.prep.cefk.face;

import org.json.*;
import java.util.*;

/** Android platform JSON implementation; no extra runtime dependency. */
public final class AndroidFaceJsonCodec implements FaceHttpTransport.JsonCodec {
    public String encode(Map<String,Object> object) throws JSONException {return new JSONObject(object).toString();}
    public Map<String,Object> decode(String json) throws JSONException {
        JSONTokener parser=new JSONTokener(json);Object value=parser.nextValue();
        if(!(value instanceof JSONObject)||parser.nextClean()!=0)throw new JSONException("INVALID_RESPONSE");
        return object((JSONObject)value);
    }
    private Map<String,Object> object(JSONObject source) throws JSONException {
        Map<String,Object> out=new LinkedHashMap<>();Iterator<String> keys=source.keys();
        while(keys.hasNext()){String key=keys.next();out.put(key,convert(source.get(key)));}return out;
    }
    private Object convert(Object value) throws JSONException {
        if(value==JSONObject.NULL)return null;
        if(value instanceof JSONObject)return object((JSONObject)value);
        if(value instanceof JSONArray){List<Object> out=new ArrayList<>();JSONArray a=(JSONArray)value;
            for(int i=0;i<a.length();i++)out.add(convert(a.get(i)));return out;}
        return value;
    }
}
