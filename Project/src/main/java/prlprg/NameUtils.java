package prlprg;

import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.regex.Pattern;

/**
 * A utility class to deal with names.
 * 
 * Type names are tricky because of imports and 'soft' imports. For example,
 * 
 * <prea> Base.Ptr Ptr SomeOtherPackage.Ptr </ptr>
 * 
 * are all equivalent. On the other hand if we see
 * 
 * <preA> abstract type SomeOhterPackage.Ptr{T} end
 * </pre>
 * 
 * then this defines a new type. Sigh.
 * 
 * Then, when we deal with code_warntype, it will capitalize types that are not
 * concrete. So we need to undo that as well.
 * 
 * A previous implementation tried to find cannonical representations for types,
 * but that was tricky and messy.
 * 
 * This implementation will redefine the meaning of equality for type names.
 * upper case names from code_warntype and to generate fresh names for type
 * variables.
 * 
 * NOTE: this is imprecise for File and FILE - two different classes but if they
 * are returned as abstract then we will get File.
 */
class NameUtils implements Serializable {

    static class TypeName implements Serializable {
        String pkg;
        String nm;
        boolean basic;
        boolean soft;

        protected TypeName(String pkg, String nm) {
            this.pkg = pkg;
            this.nm = nm;
            var plow = pkg.toLowerCase();
            this.basic = plow.startsWith("base.") || plow.startsWith("core.") || plow.startsWith("pkg.") || plow.equals("");
            this.soft = true;
        }

        void seenDeclaration() {
            soft = false;
        }

        // number with optional exponent
        static Pattern pattern = Pattern.compile("[-+]?\\d*\\.?\\d+([eE][-+]?\\d+)?");

        boolean likelyConstant() {
            if (nm.equals("true") || nm.equals("false") || nm.startsWith(":") || nm.startsWith("\"") || nm.startsWith("\'")) return true;
            if (nm.equals("nothing") || nm.equals("missing")) return true;
            if (nm.startsWith("typeof(") || pattern.matcher(nm).matches() || nm.contains("(")) return true;
            return false;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof TypeName t) {
                return pkg.equals(t.pkg) && nm.equals(t.nm);
            } else
                return false;
        }

        @Override
        public int hashCode() {
            return pkg.hashCode() + nm.hashCode();
        }

        boolean juliaEq(TypeName t) {
            if (!nm.equals(t.nm)) return false;
            if (soft && t.basic) return true;
            if (t.soft && basic) return true;
            return pkg.equals(t.pkg);
        }

        boolean isAny() {
            return nm.equals("Any");
        }

        boolean isNothing() {
            return nm.equals("Nothing");
        }

        boolean isTuple() {
            return nm.equals("Tuple") || nm.equals("TUPLE");
        }

        boolean isUnion() {
            return nm.equals("Union") || nm.equals("UNION");
        }

        @Override
        public String toString() {
            return pkg.equals("") ? nm : pkg + "." + nm;
        }
    }

    class FuncName {
        String pkg;
        String nm;

        FuncName() {
        }

        FuncName(String pkg, String nm) {
            this.pkg = pkg;
            this.nm = nm;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof FuncName t) {
                return pkg.equals(t.pkg) && nm.equals(t.nm);
            } else
                return false;
        }

        @Override
        public String toString() {
            return pkg.equals("") ? nm : pkg + "." + nm;
        }
    }

    TypeName type(String exp) {
        // the parser can see weird names like typeof(t)
        var suf = suffix(exp);
        var pre = prefix(exp);
        pre = pre == null ? "" : pre;
        var tn = new TypeName(pre, suf);
        if (tn.likelyConstant()) return tn;
        if (names.containsKey(tn)) return names.get(tn);
        names.put(tn, tn);
        if (!pre.equals("")) packages.add(pre);
        GenDB.types.addMissing(tn); // register the type if not there already
        return tn;
    }

    TypeName getShort(String nm) {
        var tn = new TypeName("", nm);
        if (names.containsKey(tn)) return names.get(tn);
        names.put(tn, tn);
        return tn;
    }

    private final HashMap<TypeName, TypeName> names = new HashMap<>();

    private final HashMap<String, String> upperToLowerNames = new HashMap<>();

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

    static TypeName varAsTypeName(String nm) {
        return new TypeName("", nm);
    }
}
