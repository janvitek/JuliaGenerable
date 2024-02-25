package prlprg;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

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
 * then this defines a new type that is not the same as Base.Ptr. Furthermore,
 * the parser creates TypeNames before it can tell if a name refers to a type or
 * a type variable in an existential. Finally, some expression that occur where
 * a type can be found are program variables that are converted to type
 * constants.
 * 
 * NameUtils first receives names of definitions and aliases. Once all of them
 * have been seen, it is frozen and the toDefined map is built. This map allows
 * to resolve soft imports. There will be cases where we can't resolve them, and
 * we give up in those.
 */
class NameUtils implements Serializable {

    static class TypeName implements Serializable {
        final String pkg; // package name can be ""
        final String nm; // suffix name never ""

        /** Create a TypeName from a package and a suffixe. */
        private TypeName(String pkg, String nm) {
            this.pkg = pkg;
            this.nm = nm;
        }

        /**
         * Invariants: any name that ...
         * 
         * <pre>
         *  -- ... was in a "type" position will show up in allNames
         *  -- ... has a definition will show up in toDefined
         *  -- ... seen defined as an alias will show up in aliases
         *  -- ... that has a long form will show up in toLong
         * </pre>
         */
        private static final HashSet<TypeName> allNames = new HashSet<>(); // all likely type names
        private static final HashMap<TypeName, TypeName> toDefined = new HashMap<>(); // from a name to a name that has a type definition (abstract or struct)
        private static final HashMap<TypeName, List<TypeName>> toLong = new HashMap<>(); // map from a short name to all names that match it
        private static final HashSet<TypeName> aliases = new HashSet<>(); // all names we know to be aliases
        private static boolean frozen = false; // becomes true when we have read all type and alias definitions

        /**
         * Call when "reasonably" sure that it is a type. Args could be either "","Any"
         * or "Core","Any". Cases where someting else than a type is provided is
         * variables in UnionAll, their come as a TypeName -- because, at first, we
         * don't know better.
         * 
         * There are other TypeName objects in the system, e.g. for constants. But they
         * are mostly created with a direct constructor and do not go through this
         * function. "Mostly" becauase we have a heuristic to discover those cases. It
         * fails from time to time. E.g. for now the var#"asd" names are still treated
         * as types.
         */
        static TypeName mk(String pk, String nm) {
            if (nm.equals("")) throw new RuntimeException("Empty name is not allowed");
            var tn = new TypeName(pk, nm);
            if (allNames.contains(tn)) return tn; // if this exact name is already in the system, return it        
            allNames.add(tn); // otherwise add it to the names 
            if (!frozen) {
                if (!pk.equals("")) {
                    var snm = new TypeName("", nm);
                    var list = toLong.getOrDefault(snm, new ArrayList<>());
                    list.add(tn);
                    toLong.put(snm, list);
                }
            } else
                ensureNameIsDefined(tn);
            return tn;
        }

        /**
         * When we have seen all alias and type definition, build the toDefined mapping
         * to allow queries involving soft imports.
         */
        static void freeze() {
            for (var a : GenDB.it.aliases.all())
                addToSets(a.nm, aliases);
            for (var i : GenDB.it.types.all())
                addToSets(i.nm, aliases);
            for (var tnm : allNames)
                ensureNameIsDefined(tnm);
            frozen = true;
        }

        /** Helper for freeze */
        private static void addToSets(TypeName tn, HashSet<TypeName> set) {
            toDefined.put(tn, tn);
            set.add(tn);
        }

        /**
         * Give a typeName wothout a definition, find its definition or complain.
         * 
         * <pre>
         *    Any ==> Core.Any
         *    Foo.Int32 ==> Core.Int32
         * </pre>
         * 
         * Assume a well-defined program where names refer to some entity. If some name
         * N has no mapping in toDefined, shorten N to SN, dropping the package, and
         * find all definitions for SN, regardless of origin package. If there is a
         * single definition for SN, then it is also the definition for N. If SN has
         * multiple definitions, then pick one. If there is a definition D for SN that
         * belongs to tbe base packages, then D is the definition for N. This is a soft
         * import. If there is no definition in the base packages, then we have an
         * ambibuity. If no definition is found. It can be that we are missing a type
         * (for example Colon and DataType are not found) or we have a bug.
         */
        private static void ensureNameIsDefined(TypeName tnm) {
            // What is the meaning of this name? It does not have a definition
            // in the DB, it is either a soft import or a short names.
            if (toDefined.containsKey(tnm)) return; // already defined

            // If we get past this point it means, we are dealing with a soft import
            var stn = new TypeName("", tnm.nm); // shortened...
            var nms = toLong.get(stn); // all names that share the same suffix
            // If we have no match, then it means that either it is a variable that we thought was perhaps a type
            // or there is a type without a definition.
            if (nms == null || nms.isEmpty()) return;
            var basics = nms.stream().filter(n -> n.isBasic()).toList();
            if (basics.isEmpty()) return; // this does not look like a type that can be soft imported
            var defs = basics.stream().map(n -> toDefined.get(n)).filter(d -> d != null).toList();
            var uniqs = new HashSet<>(defs);
            if (uniqs.size() == 1) toDefined.put(tnm, uniqs.iterator().next());
            // Else we have an ambiguity, can't resolve it
        }

        // number with optional exponent
        private static Pattern pattern = Pattern.compile("[-+]?\\d*\\.?\\d+([eE][-+]?\\d+)?");

        boolean likelyConstant() {
            if (nm.equals("true") || nm.equals("false") || nm.startsWith(":") || nm.startsWith("\"") || nm.startsWith("\'")) return true;
            if (nm.equals("nothing") || nm.equals("missing")) return true;
            if (nm.startsWith("Const(")) return true;
            if (nm.startsWith("Core.Const(")) return true;
            if (nm.contains(")")) return true;
            return nm.startsWith("typeof(") || pattern.matcher(nm).matches() || nm.contains("(");
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
            if (equals(t)) return true;
            if (!frozen) throw new RuntimeException("Not frozen -- call freeze() first");
            var def = toDefined.get(this);
            if (def != null && def.equals(t)) return true;
            def = toDefined.get(t);
            if (def != null && def.equals(this)) return true;
            return false;
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
            if (pkg.startsWith("Base.")) return true;
            if (pkg.startsWith("Core.")) return true;
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
            this.nm = nm;
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

    /**
     * From a string make a type name. This function has three roles: (1) it returns
     * a TypeName after splitting the package part of the input from the name part.
     * (2) It adds the package part to packages so that we can generate the import
     * for that one, (3) it calls TypeName.mk() to register the type name. (If it is
     * not a type name, it will try to avoid calling mk(), but this is only a
     * heuristic, if the name looks credible we will call mk() -- that in turn will
     * try to ignore bogus type names).
     */
    TypeName type(String exp) {
        // the parser can see weird names like typeof(t)
        // We don't call mk() because it would log those names
        if (exp.startsWith("typeof("))
            return new TypeName("", exp);
        else if (exp.startsWith("Symbol(")) return makeShort(exp);
        var suf = suffix(exp);
        var pre = prefix(exp);
        pre = pre == null ? "" : pre;
        var tn = new TypeName(pre, suf);
        if (tn.likelyConstant()) return tn;
        if (!pre.equals("")) packages.add(pre);
        return TypeName.mk(pre, suf);
    }

    /**
     * Return a FuncName after splitting the string into a package and function. The
     * package is added to packages so we can generate an import.
     */
    FuncName function(String exp) {
        var suf = suffix(exp);
        var pre = prefix(exp);
        pre = pre == null ? "" : pre;
        var tn = new FuncName(pre, suf);
        if (!pre.equals("")) packages.add(pre);
        return tn;
    }

    /** Returns a short type name. */
    TypeName makeShort(String nm) {
        return new TypeName("", nm);
    }

    /** All packages that are seen as part of names. */
    final HashSet<String> packages = new HashSet<>();

    /**
     * Return the package part of the name, i.e. the string until the last dot. Null
     * if none.
     */
    private String prefix(String s) {
        var i = s.lastIndexOf(".");
        return i == -1 ? null : s.substring(0, i);
    }

    /** Return the suffix of a name, i.e., the part after the last dot. */
    String suffix(String s) {
        var i = s.lastIndexOf(".");
        return i == -1 ? s : s.substring(i + 1);
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
