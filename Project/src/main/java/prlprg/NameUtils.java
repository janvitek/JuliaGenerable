package prlprg;

import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;

/**
 * A utility class to deal with names. Type names are tricky because of imports
 * and 'soft' imports. For example,
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
 *  abstract type SomeOtherPackage.Ptr{T} end
 * </pre>
 *
 * then this defines a new type that is not the same as Base.Ptr.
 * 
 */
class NameUtils implements Serializable {

    private final HashSet<TypeName> typeDefs = new HashSet<>();
    private final HashSet<TypeName> aliasDefs = new HashSet<>();
    private final HashMap<String, HashSet<TypeName>> toLongs = new HashMap<>();

    void addTypeDef(TypeName tn) {
        if (tn == null || tn.isShort()) throw new RuntimeException("Need a full name");
        typeDefs.add(tn);
        addToLong(tn.nm, tn);
        packages.add(tn.pkg);
    }

    void addAliasDef(TypeName tn) {
        if (tn == null || tn.isShort()) throw new RuntimeException("Need a full name");
        aliasDefs.add(tn);
        addToLong(tn.nm, tn);
    }

    private void addToLong(String nm, TypeName tn) {
        if (tn.isShort()) throw new RuntimeException("Need a full name");
        var tls = toLongs.get(nm);
        if (tls == null) toLongs.put(nm, tls = new HashSet<TypeName>());
        tls.add(tn);
    }

    /** From a string make a type name. */
    TypeName type(String exp) {
        var pre = prefix(exp);
        return new TypeName(pre == null ? "" : pre, suffix(exp));
    }

    /**
     * Return a FuncName after splitting the string into a package and function. The
     * package is added to packages so we can generate an import.
     */
    FuncName function(String exp) {
        var pre = prefix(exp);
        if (pre != null) packages.add(pre);
        return new FuncName(pre == null ? "" : pre, suffix(exp));
    }

    /** Return the package part of the name. */
    private String prefix(String s) {
        var i = s.lastIndexOf(".");
        return i == -1 ? null : s.substring(0, i);
    }

    /** Return the suffix of a name, i.e., the part after the last dot. */
    private String suffix(String s) {
        var i = s.lastIndexOf(".");
        return i == -1 ? s : s.substring(i + 1);
    }

    TypeName any() {
        return new TypeName("Core", "Any");
    }

    TypeName tuple() {
        return new TypeName("Core", "Tuple");
    }

    TypeName union() {
        return new TypeName("Core", "Union");
    }

    TypeName vararg() {
        return new TypeName("Core", "VarArg");
    }

    TypeName symbol() {
        return new TypeName("Core", "Symbol");
    }

    TypeName nothing() {
        return new TypeName("Core", "Nothing");
    }

    TypeName int64() {
        return new TypeName("Core", "Int64");
    }

    TypeName string() {
        return new TypeName("Core", "String");
    }

    TypeName bool() {
        return new TypeName("Core", "Bool");
    }

    /** All packages that are seen as part of names. */
    final HashSet<String> packages = new HashSet<>();

    class TypeName implements Serializable {
        final String pkg; // package name can be "" if the name is a short name
        final String nm; // suffix name never ""

        /** Create a TypeName from a package and a suffixe. */
        private TypeName(String pkg, String nm) {
            this.pkg = pkg;
            if (nm.equals("")) throw new RuntimeException("Empty name is not allowed");
            this.nm = nm;
        }

        TypeName resolve() {
            var tn = this;
            if (aliasDefs.contains(tn) || typeDefs.contains(tn)) return tn;
            var longs = toLongs.get(tn.nm);
            if (longs == null) return tn;
            var bases = longs.stream().filter(t -> (aliasDefs.contains(t) || typeDefs.contains(t)) && t.isBasic()).toList();
            if (bases.size() == 0)
                return tn;
            else if (bases.size() == 1)
                return bases.getFirst();
            else {
                //    App.print("Ambiguous name " + tn + " could be " + bases);
                // Ambiguous name Pair could be [Core.Pair, Base.Pair]
                return bases.getFirst(); // Randomly pick one ...  
            }
        }

        /** Syntacic equality */
        @Override
        public boolean equals(Object o) {
            return o instanceof TypeName t ? pkg.equals(t.pkg) && nm.equals(t.nm) : false;
        }

        /** Traditional hash */
        @Override
        public int hashCode() {
            return pkg.hashCode() + nm.hashCode();
        }

        /** Is this Any? */
        boolean isAny() {
            return nm.equals("Any");
        }

        /** Is this VarArg? */
        boolean isVararg() {
            return nm.equals("Vararg");
        }

        /** Is this a TypeName without a packages? */
        boolean isShort() {
            return pkg.equals("");
        }

        /**
         * Is this a basic type? One that occurs in the Base or Core packages. One that
         * can be the subject of a soft import.
         */
        boolean isBasic() {
            if (isShort()) return false;
            //  if (pkg.startsWith("Base.")) return true;
            // if (pkg.startsWith("Core.")) return true;
            if (pkg.equals("Base")) return true;
            if (pkg.equals("Core")) return true;
            return false;
        }

        /** Is this a Tuple? */
        boolean isTuple() {
            return nm.equals("Tuple");
        }

        /** Is this a Union? */
        boolean isUnion() {
            return nm.equals("Union");
        }

        /** Return the type name as a string. */
        @Override
        public String toString() {
            return pkg.equals("") ? nm : pkg + "." + nm;
        }
    }

    /**
     * A Julia function name that has a package and function name as suffix.
     */
    class FuncName implements Serializable {
        private final String pkg;
        private final String nm;

        /** Return the package name or an empty string if there isn't one. */
        String packageName() {
            return pkg;
        }

        /** Return the function name without the package prefix. Never empty. */
        String operationName() {
            return nm;
        }

        /** Create a function name from a package and a suffix. */
        FuncName(String pkg, String nm) {
            this.pkg = pkg == null ? "" : pkg;
            if (nm.equals("")) throw new RuntimeException("Name cannot be empty");
            this.nm = nm;
            if (!pkg.equals("")) packages.add(pkg);
        }

        /** Equality of names */
        @Override
        public boolean equals(Object o) {
            if (o instanceof FuncName t) return pkg.equals(t.pkg) && nm.equals(t.nm);
            return false;
        }

        /** Traditional hash */
        @Override
        public int hashCode() {
            return pkg.hashCode() + nm.hashCode();
        }

        @Override
        public String toString() {
            return pkg.equals("") ? nm : pkg + "." + nm;
        }

        /**
         * Returns this function name as it could appear in Julia source code. Names
         * that are symbols such as < surround them with a ":(<)".
         */
        String toJulia() {
            var name = !Character.isLetter(nm.charAt(0)) && nm.charAt(0) != '_' ? (":(" + nm + ")") : nm;
            return pkg.equals("") ? name : pkg + "." + name;
        }

    }

    // The goal is to generate name for type variables that are distinct from user generated
    // names, right now we are not doing that -- a typename could be a greek letter. If
    // this causes trouble, revisit and prefix with an invalid character. (I am trying to
    // keep names short, so I'll try to avoid doing that if possible.)
    private static int gen = 0;

    /** Reset the type variable name generator. Call this between definitions. */
    static void reset() {
        gen = 0;
    }

    /** A few Greek letters used for type variables. */
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
