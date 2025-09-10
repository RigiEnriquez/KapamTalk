package com.translator.kapamtalk;

import android.content.Context;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class DictionaryUtils {
    public static List<DictionaryEntry> loadDictionaryData(Context context, boolean isEnglish) {
        try {
            // Choose file based on selected language
            String filename = isEnglish ? "dictionary_english.json" : "dictionary_kapampangan.json";
            InputStream is = context.getAssets().open(filename);
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            String json = new String(buffer, "UTF-8");
            Gson gson = new Gson();
            Type listType = new TypeToken<List<DictionaryEntry>>(){}.getType();
            return gson.fromJson(json, listType);
        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }
}
