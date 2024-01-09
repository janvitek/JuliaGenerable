package prlprg;

import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;

/**
 * A utility class to deal with names. This is used to shorten names, deal with
 * upper case names from code_warntype and to generate fresh names for type
 * variables.
 * 
 * NOTE: this is imprecise for File and FILE - two different classes but if they
 * are returned as abstract then we will get File.
 */
class NameUtils implements Serializable {

    private final HashMap<String, String> upperToLowerNames = new HashMap<>();

    private final HashMap<String, String> namesFromBase = new HashMap<>();

    final HashSet<String> packages = new HashSet<>();

    /**
     * Add the upper cased version of a name to our database. This is used to deal
     * with the results of code_warntype that force names to be upper case when they
     * are not concrete.
     */
    private void addToUpper(String low) {
        var upper = low.toUpperCase();
        if (upper.equals(low)) return;
        upperToLowerNames.put(upper, low);
    }

    String toLower(String s) {
        if (upperToLowerNames.containsKey(s)) {
            return upperToLowerNames.get(s);
        } else {
            return s;
        }
    }

    /**
     * Normalize a name. This is used to shorten names. This method will strip the
     * names that start with Base.* and Core.* from their prefix. And also will
     * strip names that end with a suffix that occurs in Base.* and Core.* of their
     * prefix.
     * 
     * <pre>
     *    Base.Any => Any    
     *    Foo.Any => Any
     * </pre>
     * 
     * The first is stripped because it starts with Base, the second because it also
     * occura in Base.
     */
    String normalize(String s) {
        addToUpper(s);
        registerPackage(s);
        var suffix = suffix(s);
        addToUpper(suffix);
        if (s.toLowerCase().startsWith("base.") || s.toLowerCase().startsWith("core.")) {
            namesFromBase.put(suffix, s);
            return s;
        } else if (namesFromBase.containsKey(suffix)) {
            return namesFromBase.get(suffix);
        } else {
            return s;
        }
    }

    String prefix(String s) {
        var i = s.lastIndexOf(".");
        return i == -1 ? null : s.substring(0, i);
    }

    /**
     * Return the suffix of a name, i.e., the part after the last dot.
     */
    String suffix(String s) {
        var i = s.lastIndexOf(".");
        return i == -1 ? s : s.substring(i + 1);
    }

    void registerPackage(String p) {
        var prefix = prefix(p);
        if (prefix != null) packages.add(prefix);
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
