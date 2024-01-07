package prlprg;

import java.util.HashMap;
import java.util.HashSet;

/**
 * Utility class for generating names for types and type variables. NOTE: This
 * is not thread safe.s
 */
public class NameUtils {

    // Some type names are long, we try to shorten them while ensuring that there is no ambiguity.
    // When there is ambiguity, we just use the full name. To do this, we make a pass over the database
    // and first observe all short names that are unique, and then use them.
    private final static HashMap<String, HashSet<String>> occurences = new HashMap<>();

    /**
     * Register a name in the database of names. This is used to shorten names.
     */
    static void registerName(String s) {
        var suffix = suffix(s);
        var occs = occurences.getOrDefault(suffix, new HashSet<>());
        occs.add(s);
        occurences.put(suffix, occs);
    }

    /**
     * Return the suffix of a name, i.e., the part after the last dot.
     */
    static private String suffix(String s) {
        var i = s.lastIndexOf(".");
        return i == -1 ? s : s.substring(i + 1);
    }

    /**
     * Shorten a name if possible.
     */
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

    /**
     * Reset the type variable name generator. Call this between distinct types.
     */
    static void reset() {
        gen = 0;
    }

    /**
     * A few Greek letters used for type variables.
     */
    final private static char[] varNames = { 'α', 'β', 'γ', 'δ', 'ε', 'ζ', 'η', 'θ', 'ι', 'κ', 'λ', 'μ', 'ν', 'ξ', 'ο', 'π', 'ρ', 'σ', 'τ', 'υ', 'φ', 'χ', 'ψ', 'ω' };

    /**
     * Generate a fresh type variable name as a combination of greek letters and
     * ticks. These variables are scoped to an individual method signature or type
     * declaration. There should not be too mane of them. Even if we were to repeat
     * a variable, it would only harm readability.
     */
    static String fresh() {
        var mult = gen / varNames.length;
        var off = gen % varNames.length;
        var ap = "\'";
        var nm = varNames[off] + (mult == 0 ? "" : ap.repeat(mult));
        gen++;
        return nm;
    }
}
