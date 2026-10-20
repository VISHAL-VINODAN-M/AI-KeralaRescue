package com.keralarescue.backend.ai;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Offline keyword triage in English, Malayalam and common Manglish spellings. It is the safety net that
 * works with no internet and no API key, and it is also combined with the model's answer so the final
 * priority is never lower than what these rules detect.
 */
public final class RuleTriage {
    private RuleTriage() { }

    private static final List<String> LIFE_THREAT = List.of(
            "unconscious", "not breathing", "cannot breathe", "can't breathe", "no pulse", "heart attack", "cardiac",
            "severe bleeding", "bleeding heavily", "drowning", "drowned", "trapped", "buried", "collapsed", "collapse",
            "landslide", "stroke", "seizure", "snake bite", "snakebite", "electrocut", "on fire", "burning", "labour pain",
            "in labour", "gave birth", "delivery pain", "swept away", "water rising",
            "ബോധമില്ല", "ബോധം പോയി", "ശ്വാസം മുട്ട", "ശ്വാസം കിട്ടുന്നില്ല", "ഹൃദയാഘാതം", "രക്തസ്രാവം", "മുങ്ങി", "കുടുങ്ങി",
            "ഉരുൾപൊട്ടൽ", "മണ്ണിടിച്ചിൽ", "തീപിടിത്തം", "തീ പിടിച്ചു", "പാമ്പ്", "പ്രസവ", "ഒലിച്ചു",
            "vellam kayari", "thee pidich", "bodham illa", "shwasam");
    private static final List<String> VULNERABLE = List.of(
            "pregnant", "elderly", "old woman", "old man", "infant", "baby", "newborn", "child", "children", "wheelchair",
            "bedridden", "disabled", "dialysis", "oxygen",
            "ഗർഭിണി", "വൃദ്ധ", "വയോധിക", "കുഞ്ഞ്", "കുട്ടി", "കിടപ്പ്", "ഡയാലിസിസ്", "ഓക്സിജൻ");
    private static final List<String> FIRE = List.of("fire", "smoke", "burning", "തീപിടിത്തം", "തീ പിടിച്ചു", "പുക");
    private static final List<String> FLOOD = List.of("flood", "water level", "water rising", "inundat", "വെള്ളപ്പൊക്കം", "വെള്ളം കയറി", "വെള്ളം പൊങ്ങി", "vellam kayari");
    private static final List<String> LANDSLIDE = List.of("landslide", "mudslide", "ഉരുൾപൊട്ടൽ", "മണ്ണിടിച്ചിൽ");
    private static final List<String> MEDICAL = List.of("heart", "bleeding", "unconscious", "breath", "pregnan", "labour", "stroke", "seizure", "snake", "injur", "medicine", "insulin", "ഹൃദയ", "രക്ത", "ബോധ", "ശ്വാസ", "ഗർഭിണി", "പ്രസവ", "പാമ്പ്", "മരുന്ന്");
    private static final List<String> TRAPPED = List.of("trapped", "stuck", "buried", "cannot get out", "കുടുങ്ങി", "കുടുങ്ങിക്കിടക്കുന്ന");
    private static final List<String> NO_ACCESS = List.of("no food", "no water", "no medicine", "no power", "no network", "stranded", "ഭക്ഷണമില്ല", "വെള്ളമില്ല");

    public static TriageResult analyze(TriageInput in) {
        String text = ((in.description() == null ? "" : in.description()) + " " + (in.conditionDetails() == null ? "" : in.conditionDetails()))
                .toLowerCase(Locale.ROOT);
        boolean life = containsAny(text, LIFE_THREAT);
        boolean vulnerable = containsAny(text, VULNERABLE);
        Set<String> flags = new LinkedHashSet<>();
        if (life) flags.add("LIFE_THREATENING");
        if (vulnerable) flags.add("VULNERABLE_PERSON");
        if (in.injured() > 0) flags.add("INJURED");
        if (in.people() >= 5) flags.add("MANY_PEOPLE");
        if (containsAny(text, NO_ACCESS)) flags.add("STRANDED");

        String type = guessType(in.emergencyType(), text);
        String priority;
        if (life || in.injured() >= 3) priority = "CRITICAL";
        else if (in.injured() > 0 || vulnerable || in.people() >= 5 || "FLOOD".equals(type) || "FIRE".equals(type) || "LANDSLIDE".equals(type) || "PERSON TRAPPED".equals(type)) priority = "HIGH";
        else priority = "MEDIUM";

        StringBuilder summary = new StringBuilder(label(type)).append(": ").append(in.people()).append(in.people() == 1 ? " person" : " people");
        if (in.injured() > 0) summary.append(", ").append(in.injured()).append(" injured");
        String desc = in.description() == null ? "" : in.description().trim().replaceAll("\\s+", " ");
        if (!desc.isEmpty()) summary.append(" — ").append(desc.length() > 140 ? desc.substring(0, 140) + "…" : desc);
        double confidence = life ? 0.7 : 0.45;
        return new TriageResult(priority, type, summary.toString(), null, new ArrayList<>(flags), confidence, "RULES");
    }

    private static String guessType(String submitted, String text) {
        String t = submitted == null ? "OTHER" : submitted.trim().toUpperCase(Locale.ROOT).replace('_', ' ');
        if (!t.equals("OTHER") && !t.equals("CRITICAL SOS") && !t.isBlank()) return t;
        if (containsAny(text, LANDSLIDE)) return "LANDSLIDE";
        if (containsAny(text, FIRE)) return "FIRE";
        if (containsAny(text, FLOOD)) return "FLOOD";
        if (containsAny(text, TRAPPED)) return "PERSON TRAPPED";
        if (containsAny(text, MEDICAL)) return "MEDICAL";
        return t.equals("CRITICAL SOS") ? "CRITICAL SOS" : "OTHER";
    }

    private static String label(String type) {
        String lower = type.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static boolean containsAny(String text, List<String> words) {
        for (String w : words) if (text.contains(w)) return true;
        return false;
    }
}
