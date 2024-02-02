package prlprg;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
 *  abstract type SomeOtherPackage.Ptr{T} end
 * </pre>
 *
 * then this defines a new type that is not the same as Base.Ptr.
 *
 * Furthermore, the parser creates TypeNames before it can tell if a name refers
 * to a type or a type variable in an existential.
 *
 * Finally, some expression that occur where a type can be found are program
 * variables that are converted to type constants.
 */
class NameUtils implements Serializable {

    static class TypeName implements Serializable {
        final String pkg; // package name can be ""
        final String nm; // suffix name never ""

        static final HashSet<TypeName> allNames = new HashSet<>(); // all likely type names
        static final HashMap<TypeName, TypeName> toDefined = new HashMap<>(); // map from a type name to a name that has a definition
        static final HashMap<TypeName, List<TypeName>> toLong = new HashMap<>(); // map from a name without a package to all names that match it
        static final HashSet<TypeName> aliases = new HashSet<>(); // all names we know to be aliases
        static final HashSet<TypeName> types = new HashSet<>(); // all names we know to be types
        static boolean frozen = false; // becomes true when we have read all type and alias definitions

        /**
         * This function is called when we are "reasonably" sure that we have a type. It
         * could be either "","Any" or "Core","Any" for example. Typical cases where we
         * get someting else than a type are variables in UnionAll types. Their name is
         * a TypeName -- because, at first we don't know better.
         * 
         * There are other TypeName objects in the system, e.g. for constants. But they
         * are mostly created with a direct constructor and do not go through this
         * function. "Mostly" becauase we have a heuristic to discover those cases. It
         * fails from time to time. E.g. for now the var#"asd" names are still treated
         * as types.
         */
        static TypeName mk(String pk, String nm) {
            if (nm.equals("")) throw new RuntimeException("Empty name");
            var tn = new TypeName(pk, nm);
            if (allNames.contains(tn)) return tn;
            allNames.add(tn);
            if (!tn.isShort()) {
                var snm = new TypeName("", nm);
                var list = new ArrayList<TypeName>();
                list.add(tn);
                toLong.put(snm, list);
            }
            if (frozen) {
                findDefinition(tn);
            }
            return tn;
        }

        /**
         * Create a short name.
         */
        static TypeName mk(String nm) {
            return mk("", nm);
        }

        /**
         * When we have seen all alias definitions and all type definition, build the
         * toDefined mapping so that we can make queries involving soft imports and
         * partial names.
         */
        static void freeze() {
            var seen = new HashSet<TypeName>();
            for (var a : GenDB.it.aliases.all()) {
                toDefined.put(a.nm, a.nm);
                seen.add(a.nm);
                aliases.add(a.nm);
            }
            for (var i : GenDB.it.types.all()) {
                toDefined.put(i.nm, i.nm);
                seen.add(i.nm);
                types.add(i.nm);
            }
            // All the names that don't have a definition
            for (var tnm : allNames) {
                if (seen.contains(tnm)) continue; // already handled                
                findDefinition(tnm);
            }
            frozen = true;
        }

        /**
         * Give a type name that does not have a definition, find its definition or
         * complain or even fail.
         * 
         * <pre>
         *    Any ==> Core.Any
         *    Foo.Int32 ==> Core.Int32
         * </pre>
         * 
         * We assume a well-defined program. I.e. all names are meaningful.
         * 
         * If we see a name that does not have a mapping in toDefined, we shorten it to
         * its suffix, dropping the package, and get all definitions that end in that
         * suffix.
         * 
         * Our hope is that there is a single definition for that name, in that case,
         * since the program is well-defined, it must be the one we are looking for.
         * 
         * If there are multiple definitions, then we have to pick one. We hope that
         * there is a single one that belongs to the base / core packages and thus is a
         * soft import.
         * 
         * If no definition is found -- either we are missing a type (Seems to be
         * happening for Colon and DataType) or what we have is a variable and we did
         * not recognize it as such.
         */
        private static void findDefinition(TypeName tnm) {
            // What is the meaning of this name? It does not have a definition
            // in the DB, it is either a soft import or a short names.

            // Let's shorten it...
            var tn = tnm.isShort() ? tnm : new TypeName("", tnm.nm);

            // Let's get all the names that share the same suffix
            var nms = toLong.get(tn);
            if (nms == null || nms.isEmpty()) {
                App.output(tn + " not in toLong, is it really a type? If yes, then it is a bug. The parser somtime generates noise names.");
                return;
            }
            if (nms.size() == 1) {
                var nm = nms.get(0);
                if (!aliases.contains(nm) && !types.contains(nm)) App.print(nm + " lacks a definition, we are missing a type. Oh well.");
                toDefined.put(tn, nm);
            } else {
                var found = false;
                for (var nm : nms)
                    if (nm.isBasic()) {
                        if (found) throw new RuntimeException("Multiple definitions for " + tn);
                        found = true;
                        toDefined.put(tn, nm);
                    }
                if (!found) throw new RuntimeException("Missing definition for " + tn);
            }
        }

        /**
         * Create a type name from a package name and a suffix name. The basic property
         * holds only for names that are defined in Base or Core and not for names of
         * subpakages.
         */
        private TypeName(String pkg, String nm) {
            this.pkg = pkg;
            this.nm = nm;
        }

        // number with optional exponent
        static Pattern pattern = Pattern.compile("[-+]?\\d*\\.?\\d+([eE][-+]?\\d+)?");

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

        boolean isAny() {
            return nm.equals("Any");
        }

        boolean isShort() {
            return pkg.equals("");
        }

        boolean isBasic() {
            if (isShort()) return false;
            if (pkg.startsWith("Base.")) return true;
            if (pkg.startsWith("Core.")) return true;
            if (pkg.equals("Base")) return true;
            if (pkg.equals("Core")) return true;
            return false;
        }

        /**
         * Try to handle the case when we see types from code_warn types. This will
         * break if users choose to define either of these names for themselves.
         */
        boolean isTuple() {
            return nm.equals("Tuple");
        }

        /**
         * Try to handle the case when we see types from code_warn types. This will
         * break if users choose to define either of these names for themselves.
         */
        boolean isUnion() {
            return nm.equals("Union");
        }

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

        /**
         * Return the package name or an empty string if there is no package.
         */
        String packageName() {
            return pkg;
        }

        /**
         * Return the function name without the package prefix. Never empty.
         */
        String operationName() {
            return nm;
        }

        FuncName(String pkg, String nm) {
            this.pkg = pkg == null ? "" : pkg;
            this.nm = nm;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof FuncName t) return pkg.equals(t.pkg) && nm.equals(t.nm);
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

        /**
         * Returns this function name as it could appear in Julia source code. There are
         * two things to do: add quotes for names that were constructed out of
         * conncatenated identifier and string. The second is for names that are symbols
         * such as < surround them with a ":(<)".
         */
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

    /**
     * From a string mae a type name.
     */
    TypeName type(String exp) {
        // the parser can see weird names like typeof(t)
        // We don't call mk() because it would log those names
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
        if (!pre.equals("")) packages.add(pre);
        return TypeName.mk(pre, suf);
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
        return TypeName.mk(nm);
    }

    final HashSet<String> packages = new HashSet<>();

    private String prefix(String s) {
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
