package com.settle.swift;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tiny regex parser for the mock FIN messages. Not a SWIFT validator. */
public final class LiteParser {
    private static final Pattern ACK_FLAG = Pattern.compile("\\{451:(\\d)}");
    private static final Pattern ERR = Pattern.compile("\\{405:([^}]*)}");
    private static final Pattern REF = Pattern.compile("\\{108:([^}]*)}");
    // :TAG:[:QUAL/[ISSUER]/]VALUE  e.g. :95P::PSET//DTCYUS33, :95R::DEAG/DTCYID/0999, :35B:ISIN ...
    private static final Pattern FIELD = Pattern.compile("^:(\\w{2,3}):(?::(\\w{4})/([A-Z0-9]*)/)?(.*)$", Pattern.MULTILINE);

    private LiteParser() {}

    public static boolean isAckNak(String raw) {
        return ACK_FLAG.matcher(raw).find();
    }

    public static Map<String, Object> parseAckNak(String raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        Matcher m = ACK_FLAG.matcher(raw);
        out.put("ack", m.find() && m.group(1).equals("0"));
        Matcher e = ERR.matcher(raw);
        out.put("errorCode", e.find() ? e.group(1) : null);
        Matcher r = REF.matcher(raw);
        out.put("ref", r.find() ? r.group(1) : null);
        return out;
    }

    public static List<Map<String, String>> parseFields(String raw) {
        List<Map<String, String>> fields = new ArrayList<>();
        Matcher m = FIELD.matcher(raw.replace("\r\n", "\n"));
        while (m.find()) {
            Map<String, String> f = new LinkedHashMap<>();
            f.put("tag", m.group(1));
            f.put("qualifier", m.group(2));
            if (m.group(3) != null && !m.group(3).isEmpty()) f.put("issuer", m.group(3));
            f.put("value", m.group(4));
            fields.add(f);
        }
        return fields;
    }

    /** First value for tag (+ optional qualifier), or null. */
    public static String find(List<Map<String, String>> fields, String tag, String qualifier) {
        return fields.stream()
                .filter(f -> f.get("tag").equals(tag) && (qualifier == null || qualifier.equals(f.get("qualifier"))))
                .map(f -> f.get("value")).findFirst().orElse(null);
    }
}
