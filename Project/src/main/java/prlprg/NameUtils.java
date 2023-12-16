package prlprg;

import java.util.HashMap;
import java.util.HashSet;

public class NameUtils {

    // Some type names are long, we try to shorten them while ensuring that there is no ambiguity.
    // When there is ambiguity, we just use the full name. To do this, we make a pass over the database
    // and first observe all short names that are unique, and then use them.
    private final static HashMap<String, HashSet<String>> occurences = new HashMap<>();

    static void registerName(String s) {
        var suffix = suffix(s);
        var occs = occurences.getOrDefault(suffix, new HashSet<>());
        occs.add(s);
        occurences.put(suffix, occs);
    }

    static private String suffix(String s) {
        var i = s.lastIndexOf(".");
        return i == -1 ? s : s.substring(i + 1);
    }

    static String shorten(String s) {
        if (App.SHORTEN) {
            var suffix = suffix(s);
            return occurences.get(suffix).size() == 1 ? suffix : s;
        } else {
            return s;
        }
    }

    // The goal is to generate name for type variables that are distinct from user generated
    // names, right now we are not doing that -- a typename could be a greek letter. If
    // this causes trouble, revisit and prefix with an invalid character. (I am trying to
    // keep names short, so I'll try to avoid doing that if possible.)
    private static int gen = 0;

    static void reset() {
        gen = 0;
    }

    final private static char[] varNames = {'α', 'β', 'γ', 'δ', 'ε', 'ζ', 'η', 'θ', 'ι', 'κ', 'λ',
        'μ', 'ν', 'ξ', 'ο', 'π', 'ρ', 'σ', 'τ', 'υ', 'φ',
        'χ', 'ψ', 'ω'};

    static String fresh() {
        var mult = gen / varNames.length;
        var off = gen % varNames.length;
        var ap = "\'";
        var nm = varNames[off] + (mult == 0 ? "" : ap.repeat(mult));
        gen++;
        return nm;
    }
}
