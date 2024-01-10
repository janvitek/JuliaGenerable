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
 * <pre>
 *  Base.Ptr
 *  Ptr
 *  SomeOtherPackage.Ptr
 * </pre>
 *
 * are all equivalent. On the other hand if we see
 *
 * <pre>
 *  abstract type SomeOhterPackage.Ptr{T} end
 * </pre>
 *
 * then this defines a new type that is not the same as Base.Ptr.
 *
 * Furthermore, some type definitions are built-in and will not be observed.
 *
 * Furthermore, the parser creates TypeNames before it can tell if a name refers
 * to a type or a type variable in an existential.
 *
 * Finally, some expression that occur where a type can be found are program
 * variables that are converted to type constants.
 *
 * And also, when we deal with code_warntype, it will capitalize types that are
 * not concrete. So we need to undo that as well.
 *
 * A previous implementation tried to find cannonical representations for types,
 * but that was tricky and messy. This implementation redefines the meaning of
 * equality for type names. So that we can distinguish between soft imports and
 * defined names. Type variables are handled after parsing. Type constants are
 * heuristically caught.
 *
 */
class NameUtils implements Serializable {

    static class TypeName implements Serializable {
        String pkg; // package name can be ""
        String nm; // suffix name never ""
        boolean basic; // does this type come from Base or Core
        boolean soft; // could this be a soft import a of a base type.

        protected TypeName(String pkg, String nm) {
            this.pkg = pkg;
            this.nm = nm;
            var plow = pkg.toLowerCase();
            this.basic = plow.startsWith("base.") || plow.startsWith("core.") || plow.equals("");
            this.soft = true;
        }

        /**
         * This type name has an associated declaration in the DB. A side effect is that
         * we know that the name cannot be a soft import.
         */
        void seenDeclaration() {
            soft = false;
        }

        // number with optional exponent
        static Pattern pattern = Pattern.compile("[-+]?\\d*\\.?\\d+([eE][-+]?\\d+)?");

        boolean likelyConstant() {
            if (nm.equals("true") || nm.equals("false") || nm.startsWith(":") || nm.startsWith("\"") || nm.startsWith("\'")) return true;
            if (nm.equals("nothing") || nm.equals("missing")) return true;
            return nm.startsWith("typeof(") || pattern.matcher(nm).matches() || nm.contains("(");
        }

        /** Syntacic equality */
        @Override
        public boolean equals(Object o) {
            if (o instanceof TypeName t) {
                return pkg.equals(t.pkg) && nm.equals(t.nm);
            } else
                return false;
        }

        /** Traditional hash */
        @Override
        public int hashCode() {
            return pkg.hashCode() + nm.hashCode();
        }

        /**
         * Is this type name the same as the given type name in Julia? Consider the
         * following cases:
         *
         * <pre>
         *   abstract type Foo.Ptr{T} end
         *
         *   Base.Ptr juliaEq Ptr  // true      1
         *   Base.Ptr juliaEq Foo.Ptr // false  2
         *   Ptr      juliaEq Foo.Ptr // false  3
         *   Base.Ptr juliaEq Bar.Ptr // true   4
         * </pre>
         *
         * Case 1 is true because Ptr is a soft import of Base.Ptr.
         *
         * Case 2 is false because Foo.Ptr is a new type.
         *
         * Case 3 is false because Ptr is a soft import of Base.Ptr.
         *
         * Case 4 is true because Bar.Ptr is a soft import of Base.Ptrp
         */
        boolean juliaEq(TypeName t) {
            if (!nm.equals(t.nm)) return false;
            if (soft && t.basic) return true;
            if (t.soft && basic) return true;
            return pkg.equals(t.pkg);
        }

        boolean isAny() {
            return nm.equals("Any");
        }

        /**
         * Try to handle the case when we see types from code_warn types. This will
         * break if users choose to define either of these names for themselves.
         */
        boolean isTuple() {
            return nm.equals("Tuple") || nm.equals("TUPLE");
        }

        /**
         * Try to handle the case when we see types from code_warn types. This will
         * break if users choose to define either of these names for themselves.
         */
        boolean isUnion() {
            return nm.equals("Union") || nm.equals("UNION");
        }

        @Override
        public String toString() {
            return pkg.equals("") ? nm : pkg + "." + nm;
        }
    }

    class FuncName implements Serializable {
        private final String pkg;
        private final String nm;

        String packageName() {
            return pkg;
        }

        String operationName() {
            return nm;
        }

        FuncName(String pkg, String nm) {
            this.pkg = pkg == null ? "" : pkg;
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
        public int hashCode() {
            return pkg.hashCode() + nm.hashCode();
        }

        @Override
        public String toString() {
            return pkg.equals("") ? nm : pkg + "." + nm;
        }

        String toJulia() {
            // FileWatching.|
            // Pkg.Artifacts.var#verify_artifact#1
            // Base.GMP.MPQ.//
            var name = nm;
            if (name.startsWith("var#")) {
                name = name.substring(0, 3) + "\"" + name.substring(3) + "\"";
            } else if (!Character.isLetter(name.charAt(0)) && name.charAt(0) != '_') {
                name = ":(" + name + ")";
            }
            return pkg.equals("") ? name : pkg + "." + name;
        }
    }

    TypeName type(String exp) {
        // the parser can see weird names like typeof(t)
        if (exp.startsWith("typeof(")) {
            return new TypeName("", exp);
        } else if (exp.startsWith("Symbol(")) {
            return new TypeName("", exp);
        }
        var suf = suffix(exp);
        var pre = prefix(exp);
        pre = pre == null ? "" : pre;
        var tn = new TypeName(pre, suf);
        if (tn.likelyConstant()) return tn;
        if (names.containsKey(tn)) return names.get(tn);
        names.put(tn, tn);
        if (!pre.equals("")) packages.add(pre);
        return tn;
    }

    FuncName function(String exp) {
        var suf = suffix(exp);
        var pre = prefix(exp);
        pre = pre == null ? "" : pre;
        var tn = new FuncName(pre, suf);
        if (!pre.equals("")) packages.add(pre);
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
