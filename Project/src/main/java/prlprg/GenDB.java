package prlprg;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import prlprg.NameUtils.FuncName;
import prlprg.NameUtils.TypeName;
import prlprg.Parser.MethodInformation;
import prlprg.Parser.TypeDeclaration;
import prlprg.Parser.MethodInformation.RegAssign;

/**
 * A database containing all the information that we have acquired about types
 * and signatures of methods.
 */
class GenDB implements Serializable {
    /** User defined aliases. */
    class Aliases implements Serializable {

        final private HashMap<TypeName, Alias> db = new HashMap<>(); // maps TypeName to alias
        final private HashMap<String, List<Alias>> shortNames = new HashMap<>(); // maps type without prefix to alias

        /** An alias has a name and a type. */
        class Alias implements Serializable {
            TypeName nm;
            Ty pre_patched;
            Ty patched;
            Type ty;

            Alias(TypeName tn) {
                nm = tn;
            }

            @Override
            public String toString() {
                return "alias " + nm + " = " + ty == null ? pre_patched.toString() : ty.toString();
            }

            void fixUp() {
                try {
                    patched = pre_patched.fixUp(new ArrayList<>());
                } catch (Exception e) {
                    App.print("Error: " + nm + " " + e.getMessage());
                }
                ty = patched.toType(new ArrayList<>());
            }
        }

        Alias get(TypeName tn) {
            var alias = db.get(tn);
            if (alias != null) return alias;
            var aliases = shortNames.get(tn.nm);
            if (aliases == null) return null;
            for (var a : aliases)
                if (tn.equals(a.nm)) return a;
            return null;
        }

        void addParsed(TypeName tn, Ty sig) {
            if (db.get(tn) != null) throw new RuntimeException("Alias already defined");
            var alias = new Alias(tn);
            alias.pre_patched = sig;
            db.put(tn, alias);
            var names = shortNames.get(tn.nm);
            if (names == null) shortNames.put(tn.nm, names = new ArrayList<>());
            names.add(alias);
        }

        @Override
        public String toString() {
            return "GenDB.Aliases( " + db.size() + " )";
        }

        List<Alias> all() {
            return new ArrayList<>(db.values());
        }

    }

    class Types implements Serializable {

        final private HashMap<String, List<Info>> db = new HashMap<>(); // all types hashed by suffix

        /**
         * This class holds all information on a type including various stages of
         * preparation.
         *
         * <pre>
         * TypeDeclaration -> TyDecl -> TyDecl -> Decl
         * </pre>
         *
         * The <tt>TypeDeclaration</tt> we get from the parser is ill-formed, it
         * confuses constants, variables and types. The pre_patched TyDecl is in better
         * shape but still refers to some things as types or variables. The patched
         * TyDecl adds nbound names to the DB. The final Decl is fully typed and are
         * ready to be used for generation. The class keeps all these stages for
         * debugging purposes.
         */
        class Info implements Serializable {

            TypeName nm; //name of the type
            TyDecl patched; // the type declaration after patching
            Decl decl; // the final form of the type declaration, this is used for subtype generation
            List<TypeName> subtypes = List.of(); // names of direct subtypes
            Info parent = null; // the parent of this type
            TypeName parentName; // the name of the parent
            List<Info> children = new ArrayList<>(); // nodes of direct children in the type hierarchy
            List<Type> level_1_kids = new ArrayList<>(); // the Fuel==1 children of this type

            /**
             * Create a type info from a parsed type declaration
             */
            Info(TypeDeclaration ty) {
                nm = ty.nm();
                patched = ty.toTy();
                if (isAny()) {
                    parentName = nm;
                    parent = this;
                    var tany = new Tuple(List.of(any, any));
                    decl = new Decl("abstract type", names.any(), tany, null, "");
                }
            }

            final boolean isAny() {
                return nm.isAny();
            }

            public boolean isVararg() {
                return nm.isVararg();
            }

            /**
             * This method fixes up types coming from the parser. This results in populating
             * the patched fields of Info objects. Some types that are referenced but for
             * which we have no definition are added to the GenDB.
             *
             * The pre_patched field retains the type as it came from the parser, the
             * patched field is the new one with variables corrected (see the Type.fixUp()
             * documentation for details).
             *
             * The method also adds children to the parent in the children field of Info.
             *
             * This also sets the parentName and parent fields of Info. For 'Any' the parent
             * is itself.
             */
            void fixUpParent() {
                if (isAny()) return;
                var env = new ArrayList<Bound>();
                var t = patched.ty().toType(env);
                var bounds = t.getBounds(env);
                var parinst = patched.parent().toType(bounds);
                while (t instanceof Exist e)
                    t = e.ty();
                t = new Tuple(List.of(t, parinst));
                while (!bounds.isEmpty())
                    t = new Exist(bounds.removeLast(), t);
                decl = new Decl(patched.mod(), nm, t, null, patched.src()).expandAliases();
                parentName = decl.parentTy().nm();
                parent = get(parentName);
                parent.children.add(this);
            }

            void toDecl() {
                decl = new Decl(decl.mod(), decl.nm(), decl.ty(), parent.decl, decl.src());
                subtypes = children.stream().map(c -> c.nm).collect(Collectors.toList());
                children.forEach(c -> c.toDecl());
            }

            @Override
            public String toString() {
                return nm + " <: " + parentName;
            }
        } /// End of Info //////////////////////////////////////////////////////////////////////

        /**
         * Return all types in the DB.
         */
        List<Info> all() {
            return new ArrayList<>(db.values()).stream().flatMap(List::stream).collect(Collectors.toList());
        }

        /**
         * Return the type info for a type name. Null if not found.
         */
        Info get(TypeName tn) {
            var infos = db.get(tn.nm); // use the trailing part of the name
            if (infos == null) return null;
            var eqs = new ArrayList<Info>();
            for (var i : infos)
                if (i.nm.equals(tn)) eqs.add(i); //semantic equality not syntactic.
            return eqs.size() == 1 ? eqs.get(0) : null;
        }

        /**
         * Return the names of the subtypes of a type. One thing we may be missing is
         * for Any: Tuple and Union. Generally, Tuple and Union are treated specially,
         * and we do not generate them from scratch.
         */
        List<TypeName> getSubtypes(TypeName nm) {
            var info = get(nm);
            return info == null ? null : info.subtypes;
        }

        /**
         * Add a freshly parsed type declaration to the DB. Called from the parser.
         */
        void addParsed(TypeDeclaration ty) {
            var tn = ty.nm();
            var infos = db.get(tn.nm);
            if (infos == null) db.put(tn.nm, infos = new ArrayList<>());
            Info it = null;
            for (var i : infos) // Infos holds all types with the same trailing name (e.g "Any") 
                if (i.nm.equals(tn)) it = i;
            if (it != null) throw new RuntimeException("Type already defined: " + tn);
            infos.add(new Info(ty));
        }

        /**
         * Is this type instance concrete. Consider:
         *
         * <pre>
         *   struct S{T} end
         * </pre>
         *
         * Then <tt>S</tt> is not concrete, but <tt>S{Int}</tt> is.
         */
        boolean isConcrete(TypeName nm, int passedArgs) {
            var i = get(nm);
            return i != null && // i is null if the type is not in the db
                    i.decl != null && !i.decl.isAbstract() && i.decl.argCount() == passedArgs;
        }

        /**
         * A comparator for sorting types by name alphabetically.
         */
        static class NameOrder implements Comparator<Types.Info> {

            @Override
            public int compare(Info n1, Info n2) {
                return n1.nm.toString().compareTo(n2.nm.toString());
            }
        }

        /**
         * Print the type hierarchy. This is a top down traversal of the type hierarchy.
         */
        void printHierarchy() {
            App.output("\nType hierarchy (Green is abstract )");
            printHierarchy(get(names.any()), 0);
        }

        /**
         * Helper method for printing the type hierarchy
         */
        private void printHierarchy(Info n, int pos) {
            var str = n.decl == null || n.decl.isAbstract() ? CodeColors.abstractType(n.nm.toString()) : n.nm.toString();
            str = n.decl.mod().contains("missing") ? ("? " + CodeColors.abstractType(str)) : str;
            App.output(CodeColors.comment(".").repeat(pos) + str);
            n.children.sort(new NameOrder());
            n.children.forEach(c -> printHierarchy(c, pos + 1));
        }

        @Override
        public String toString() {
            return "GenDB ( " + db.size() + " )";
        }

    }

    /**
     * This class holds data for every Julia function. A function is implemented by
     * a set of methods. The internal Info class represent a single method
     * definition. The db can be queried by opertion name.
     */
    static class Signatures implements Serializable {

        record Result(Type argTy, Type retTy) implements Serializable {
            @Override
            public String toString() {
                return argTy + " -> " + retTy;
            }
        }

        /**
         * Represents a method. The name is the full method name, the tysig is an
         * intermediary state from the Parser, and the sig is the final form.
         */
        class Info implements Serializable {
            FuncName nm;
            TySig tysig;
            Sig sig;
            ArrayList<Result> results = new ArrayList<>();

            Info(FuncName fn) {
                this.nm = fn;
            }

            @Override
            public String toString() {
                return nm + " " + (sig == null ? "missing" : "");
            }

            /**
             * Add the result of a stability test, `argTy` are the arguments passed and
             * `retTy` is the result of code_wrantype. The types may contain aliases and we
             * expand them before adding.
             */
            void addResult(Type argTy, Type retTy) {
                argTy = Type.expandAliasesFixpoint(argTy);
                retTy = Type.expandAliasesFixpoint(retTy);
                results.add(new Result(argTy, retTy));
            }

            /**
             * Compare a signature read from the TypeDiscover generated file with a
             * signature that comes from code_warntype. We are looking to see if they have
             * the same origin file and line number.
             * 
             * Soem functions are compiler-generated for example:
             * 
             * <pre>
             * " "var"#s138#262" (Any,Any,Any,Any,Any)" from var"#s138#262"(an, bn, ::Any, a, b) @ Core.Compiler none:0"  // Discovery
             * "function Core.Compiler.var"#s138#262"(an, bn, ::Core.Any, a, b)  [generic] // Code_warntype
             * </pre>
             * 
             * For those we comapre names and arguments.
             */
            boolean equalsSigFromCWT(Sig s, String other) {
                // There is a silly format mistmatch in the way the parser represents 
                // source info in methods read from code_warntype
                // Plus the paths can differ. So compare only the filenames. 
                int idx = other.indexOf(" ");
                other = idx != -1 ? other.substring(idx + 1) : other;
                other = "@ " + other.replaceAll(" ", "") + "]";
                var cmp = compareSources(tysig.src(), other);
                return cmp == 1 ? true : (cmp == 0 ? false : sig.equals(s));
            }

            /** Compare sigs coming from TypeDiscovery. True if same file+line number. */
            boolean equalsSTyig(TySig other) {
                var cmp = compareSources(tysig.src(), other.src());
                return cmp == 1 ? true : (cmp == 0 ? false : tysig.equals(other));
            }

            /** -1 == don't know, 0 == no, 1 == yes */
            private int compareSources(String a, String b) {
                // The src field contains the entire signature ending with the file name. 
                // "function Base.*(x::T, y::T) where T<:Union{Core.Int128, Core.UInt128}  [generic @ int.jl:1027]"
                // What we want to compare is the int.jl:1027 part.
                // Sometimes there is no @
                // "function SHA.var"#s972#1"(::Core.Any, context)  [generic]"
                    if (a.contains("[generic]") || b.contains("[generic]")) return a.contains("[generic]") && b.contains("[generic]") ? -1 : 0;
                var idx_a = a.lastIndexOf('@');
                a = idx_a != -1 ? a.substring(idx_a + 1) : a;
                var idx_b = b.lastIndexOf('@');
                b = idx_b != -1 ? b.substring(idx_b + 1) : b;
                idx_a = a.lastIndexOf('/');
                a = idx_a != -1 ? a.substring(idx_a + 1) : a;
                idx_b = b.lastIndexOf('/');
                b = idx_b != -1 ? b.substring(idx_b + 1) : b;
                return a.equals(b) ? 1 : 0;
            }

        }

        /** The DB is a map from operation names to a list of methods. */
        final private HashMap<String, List<Info>> db = new HashMap<>();

        /** Return list of Infos for a name. If not in the DB, create an empty list. */
        List<Info> get(String opName) {
            var res = db.get(opName);
            if (res == null) db.put(opName, res = new ArrayList<>());
            return res;
        }

        /**
         * Create a new method for a function name. It is okay to create methods with
         * the same FuncName because they will be distinguished by their signatures.
         */
        Info make(FuncName fn) {
            var info = new Info(fn);
            var infos = get(fn.operationName());
            infos.add(info);
            return info;
        }

        /**
         * Return the operation names of all functions in the DB.
         */
        List<String> allNames() {
            return new ArrayList<>(db.keySet());
        }

        /**
         * Return all signatures in the DB.
         */
        List<Sig> allSigs() {
            return db.entrySet().stream().flatMap(e -> e.getValue().stream()).map(i -> i.sig).collect(Collectors.toList());
        }

        /**
         * Return all info objects in the DB.
         */
        List<Info> allInfos() {
            return db.entrySet().stream().flatMap(e -> e.getValue().stream()).collect(Collectors.toList());
        }

        /**
         * Transform all signatures to Sigs. This is done after all types have been
         * patched.
         */
        void toSigAll() {
            for (var nm : allNames())
                for (var s : get(nm)) {
                    var n = s.tysig;
                    try {
                        s.sig = new Sig(n.nm(), n.ty().toType(new ArrayList<>()), n.argnms(), n.kwPos(), n.src()).expandAliases();
                    } catch (Exception e) {
                        App.print("Error: " + n.nm() + " " + e.getMessage() + "\n" + CodeColors.comment("Failed at " + n.src()));
                    }
                }
        }

        /**
         * Find a matching Info or return null. This method can be called before the
         * fully formed Sig objects are built. So we compare the pre_patched ones as
         * they are sure to be there and non-null. Furthermore we compare the src()
         * fields which contain the file and line number of the definition of that sig.
         */
        Info find(TySig sig) {
            var infos = db.get(sig.nm().operationName());
            if (infos == null) return null;
            for (var info : infos)
                if (info.equalsSTyig(sig)) return info;
            return null;

        }
    }

    static GenDB it = new GenDB();
    NameUtils names = new NameUtils();
    Aliases aliases = new Aliases();
    Types types = new Types();
    Signatures sigs = new Signatures();
    Inst any = new Inst(names.any(), List.of());
    Union none = new Union(List.of());
    HashSet<String> seenMissing = new HashSet<>();

    static boolean SAVE = false; // Should we serialize/deserialize 
    // Originally it seemed like a good idea to save some of the startup costs
    // but they are less and less relevant. It may be a good idea again
    // to save intermediate states so that one can restart search.
    // But we are not doing it right now. If we ever do, we will have 
    // to make sure that all the state is Serializable.

    /**
     * Save both the Types and Sigs to a file. Currently in the temp directory. This
     * assumes we have only one generator running at a time.
     */
    static final void saveDB() {
        if (!SAVE) return;
        try {
            try (var file = new FileOutputStream("/tmp/db.ser")) {
                try (var out = new ObjectOutputStream(file)) {
                    out.writeObject(it);
                    out.close();
                    file.close();
                    App.print("Saved DB to file");
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to save DB: " + e.getMessage());
        }
    }

    /**
     * Read the DB from a file. Currently in the temp directory. This assumes we
     * have only one generator running at a time.
     */
    static final boolean readDB() {
        if (SAVE)
            try {
                try (var file = new FileInputStream("/tmp/db.ser")) {
                    try (var in = new ObjectInputStream(file)) {
                        it = (GenDB) in.readObject();
                        in.close();
                        file.close();
                        App.print("Read DB from file");
                        return true;
                    }
                }
            } catch (Exception e) {
                App.print("Failed to read DB: " + e.getMessage());
                return false;
            }
        else
            return false;
    }

    /**
     * Add a mehotd declaration to the DB. Called from the parser. It turns out that
     * signatures have duplicates due to the way files are included. The idea is to
     * merge all those duplicates as they are not going to be more informative.
     * 
     * The difference in the duplicates is the package name of the function. Right
     * now, we do not use the package name, so perhaps we can just ignore it. Keep
     * the first package name we found.
     * 
     * Function "kwcall" is part of the translation of keyword arguments and is
     * ignored.
     * 
     * Functions with keyword arguments are compiled to an equivalent function
     * without any, we ignore the keyword version.
     */
    final void addSig(TySig sig) {
        var nm = sig.nm().operationName();
        if (nm.equals("kwcall")) return;
        if (sig.kwPos() >= 0) return;
        if (sigs.find(sig) == null) sigs.make(sig.nm()).tysig = sig;
    }

    /**
     * Performs all transformation needed for the types in the DB to be useable for
     * genreation.
     */
    public void cleanUp() {
        aliases.db.values().forEach(a -> a.fixUp());
        types.all().forEach(i -> i.fixUpParent());
        types.get(names.any()).toDecl();
        for (var i : types.all())
            i.decl = i.decl.expandAliases();
        types.printHierarchy();
        sigs.toSigAll();

        for (var sig : sigs.allSigs()) {
            var args = sig.ty();
            if (hasLower(args)) App.output("Lower bound in " + sig);
        }
    }

    boolean hasLower(Type t) {
        if (t instanceof Tuple tup) {
            return tup.tys().stream().anyMatch(this::hasLower);
        } else if (t instanceof Union u) {
            return u.tys().stream().anyMatch(this::hasLower);
        } else if (t instanceof Inst inst) {
            return inst.tys().stream().anyMatch(this::hasLower);
        } else if (t instanceof Exist e) {
            Bound bound = e.b();
            if (!bound.low().isEmpty())
                return true;
            else
                return hasLower(bound.up()) || hasLower(e.ty());
        } else {
            return false;
        }
    }

}

/**
 * A Type is the generic interface for objects representing Julia types.
 *
 * @author jan
 */
interface Type {

    @Override
    public String toString();

    /**
     * Return a clone of this type. Since <tt>Var</tt> objects have a reference to
     * their enclosing <tt>Bound</tt> object, we need to create a deep clone that
     * properly rebinds all variables to their cloned bounds.
     */
    Type deepClone(HashMap<Bound, Bound> map);

    /**
     * Structural equality means two types have the same syntactic representation.
     * This is a weak form of equallity as it does not account for equivalences such
     * as reording the elements of a union. Semantic equality is tricky due to
     * distributivity of unions over tuples and existentials.
     */
    boolean structuralEquals(Type t);

    /**
     * Return a Julia string representation of the type, this is a syntactic
     * transformation only.
     */
    String toJulia();

    /**
     * A type is equal to Any if it is an instance of Any or if it is a Union all of
     * the form "T where T".
     */
    boolean isAny();

    /**
     * A type is equal to None if it is a Union with no elements. NOTE: `Union` and
     * `Union{}` are not the same thing.
     */
    boolean isEmpty();

    /**
     * @return true if this type is a struct with all parameters bound or if it is a
     *         union with a single concrete element. Missing types are not
     *         considered concrete. Currently does not deal correctly with aliases.
     *         The type Vector{Int} is is an alias for Array{Int,1} and is not
     *         considered concrete but should be.
     */
    boolean isConcrete();

    /**
     * Find aliases and replaces them by their definition. Returns a strucuturally
     * equal object if no expension was made.
     * 
     * The argument is the map of bounds to their expanded versions.
     */
    Type expandAliases(HashMap<Bound, Bound> bounds);

    /**
     * Given a list of type arguments, attempts to replace existentials with the
     * value of the first type argument. Proceed until all arguments and existential
     * are exhausted.
     */
    Type reduce(List<Type> args, HashMap<Bound, Type> replacements, HashMap<Bound, Bound> bounds);

    /**
     * If this type consists of a number of nested existentials, return the bounds
     * of each of them.
     */
    List<Bound> getBounds(List<Bound> env);

    static Type expandAliasesFixpoint(Type t) {
        var newty = t.expandAliases(new HashMap<>());
        var oldty = t;
        while (!newty.structuralEquals(oldty)) {
            oldty = newty;
            newty = newty.expandAliases(new HashMap<>());
        }
        return newty;
    }

}

/**
 * A construtor instance may have type parameters, examples are:
 *
 * <pre>
 * Int32    Vector{Int,N}
 * </pre>
 *
 * The LHS of a type declaration can only have bound variables. The RHS of a
 * type declaration can have a mix of instance and variables (bound on LHS of
 * <:). On its own, an instance should not have free variables.
 */
record Inst(TypeName nm, List<Type> tys) implements Type, Serializable {

    @Override
    public String toString() {
        var name = nm.toString(); //nm.pkg.equals("Core") ? nm.nm : nm.toString(); // This will shorten the printing of Any
        var args = tys.stream().map(Type::toString).collect(Collectors.joining(","));
        return name + (tys.isEmpty() ? "" : "{" + args + "}");
    }

    @Override
    public Type deepClone(HashMap<Bound, Bound> map) {
        return new Inst(nm, tys.stream().map(t -> t.deepClone(map)).collect(Collectors.toList()));
    }

    @Override
    public boolean structuralEquals(Type t) {
        if (t instanceof Inst i) {
            if (!nm.equals(i.nm) || tys.size() != i.tys.size()) return false;

            for (int j = 0; j < tys.size(); j++) {
                if (!tys.get(j).structuralEquals(i.tys.get(j))) return false;
            }
            return true;
        }
        return false;
    }

    @Override
    public String toJulia() {
        var args = tys.stream().map(Type::toJulia).collect(Collectors.joining(","));
        return nm + (tys.isEmpty() ? "" : "{" + args + "}");
    }

    @Override
    public boolean isAny() {
        return nm.isAny();
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    /**
     * A Inst type is concrete if it is a struct with all parameters bound. There
     * are special cases for Tuple and Union, these are not Inst types, but if the
     * name "Tuple" is used as a type we may end up here.
     */
    @Override
    public boolean isConcrete() {
        if (nm.toString().equals("Tuple") || nm.toString().equals("Union")) {
            if (tys.size() != 0) throw new RuntimeException("Should be represented by class" + nm());
            return false;
        }
        return GenDB.it.types.isConcrete(nm, tys.size());
    }

    /**
     * Return the list of all types declaration that are subtypes of this type. This
     * is a recursive computation that does not unfold existentials.
     */
    List<Decl> subtypeDecls() {
        var res = new ArrayList<Decl>();
        var wl = new ArrayList<TypeName>();
        wl.add(nm);
        while (!wl.isEmpty())
            for (var subnm : GenDB.it.types.getSubtypes(wl.removeFirst())) {
                res.add(GenDB.it.types.get(nm).decl);
                wl.add(subnm);
            }
        return res;
    }

    /**
     * Check if this type is an alias, if it is, replace it by its definition.
     * 
     * <pre>
     *   Vector{Int} =>  (E T.Array{T,1}){Int} => Array{Int,1}
     * </pre>
     */
    @Override
    public Type expandAliases(HashMap<Bound, Bound> bs) {
        var a = GenDB.it.aliases.get(nm);
        var ntys = tys.stream().map(t -> t.expandAliases(bs)).collect(Collectors.toList());
        if (a == null) return new Inst(nm, ntys);
        var body = a.ty;
        return body.reduce(ntys, new HashMap<>(), bs);
    }

    @Override
    public Type reduce(List<Type> args, HashMap<Bound, Type> replacements, HashMap<Bound, Bound> bounds) {
        var ntys = tys.stream().map(t -> t.reduce(List.of(), replacements, bounds)).collect(Collectors.toList());
        var nntys = new ArrayList<Type>();
        nntys.addAll(args);
        nntys.addAll(ntys);
        return new Inst(nm, nntys);
    }

    /**
     * No bounds to add.
     */
    @Override
    public List<Bound> getBounds(List<Bound> bs) {
        return bs;
    }
}

/**
 * A variable refers to a Bound in an enclosing Exist. Free variables are not
 * allowed.
 *
 */
record Var(Bound b) implements Type, Serializable {

    Var(Bound b) {
        if (b == null) throw new RuntimeException("bound is null");
        this.b = b;
    }

    @Override
    public String toString() {
        return CodeColors.variable(b.nm());
    }

    @Override
    public Type deepClone(HashMap<Bound, Bound> map) {
        return new Var(map.get(b));
    }

    @Override
    public boolean structuralEquals(Type t) {
        return t instanceof Var v && b.nm().equals(v.b.nm());
    }

    @Override
    public String toJulia() {
        return b.nm();
    }

    @Override
    public boolean isAny() {
        return false;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public boolean isConcrete() {
        return false;
    }

    @Override
    public Type expandAliases(HashMap<Bound, Bound> bs) {
        return new Var(bs.get(b));
    }

    @Override
    public Type reduce(List<Type> args, HashMap<Bound, Type> replacements, HashMap<Bound, Bound> bounds) {
        var bd = replacements.get(b);
        if (bd != null) return bd;
        return new Var(bounds.get(b));
    }

    @Override
    public List<Bound> getBounds(List<Bound> bs) {
        return bs;
    }
}

/**
 * A Bound introduces a variable, with an upper and a lower bound. Julia allows
 * writing inconsistent bounds, i.e. !(low <: up). These are meaningless types
 * which cannot be used. We do not check this. We check that types are
 * well-formed (no undefined constructor and no free variables)
 */
record Bound(String nm, Type low, Type up) implements Serializable {

    @Override
    public String toString() {
        return (!low.isEmpty() ? low + "<:" : "") + CodeColors.variable(nm) + (!up.isAny() ? "<:" + up : "");
    }

    /**
     * Return a deep clone of this bound. The map is used to ensure that variables
     * are properly rebound.
     */
    public Bound deepClone(HashMap<Bound, Bound> map) {
        var me = new Bound(nm, low, up); // fix up and low
        map.put(this, me);
        return me;
    }

    /**
     * Structural equality means two bounds have the same syntactic representation.
     * This is a weak form of equallity as it does not account for equivalences such
     * as reording the elements of a union. Semantic equality is tricky due to
     * distributivity of unions over tuples and existentials.
     * 
     * This implementation requires names to be the same. That is too strict. We
     * could implement it up to alpha conversion.
     */
    public boolean structuralEquals(Bound b) {
        return nm.equals(b.nm) && low.structuralEquals(b.low) && up.structuralEquals(b.up);
    }

    /**
     * Return a Julia string representation of the bound, this is a syntactic
     * transformation only.
     */
    public String toJulia() {
        if (low.isEmpty() && up.isAny()) {
            return nm;
        } else if (low.isEmpty() && !up.isAny()) {
            return nm + "<:" + up.toJulia();
        } else if (!low.isEmpty() && up.isAny()) {
            return nm + ">:" + low.toJulia();
        } else {
            return low.toJulia() + "<:" + nm + "<:" + up.toJulia();
        }
    }

    Bound expandAliases(HashMap<Bound, Bound> bs) {
        return new Bound(nm, low.expandAliases(bs), up.expandAliases(bs));
    }

    Bound reduce(List<Type> args, HashMap<Bound, Type> replacements, HashMap<Bound, Bound> bounds) {
        return new Bound(nm, low.reduce(args, replacements, bounds), up.reduce(args, replacements, bounds));
    }
}

/**
 * A constant, such as a number, character or string. The implementation of the
 * parser does not attempt do much we constant, they are treated as
 * uninterpreted strings.
 */
record Con(String nm) implements Type, Serializable {

    @Override
    public String toString() {
        return CodeColors.comment(nm);
    }

    @Override
    public Type deepClone(HashMap<Bound, Bound> map) {
        return this;
    }

    @Override
    public boolean structuralEquals(Type t) {
        return t instanceof Con c && nm.equals(c.nm);
    }

    @Override
    public String toJulia() {
        return nm;
    }

    @Override
    public boolean isAny() {
        return false;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public boolean isConcrete() {
        return true;
    }

    @Override
    public Type expandAliases(HashMap<Bound, Bound> bs) {
        return this;
    }

    @Override
    public Type reduce(List<Type> args, HashMap<Bound, Type> replacements, HashMap<Bound, Bound> bounds) {
        if (args.isEmpty()) return this;
        throw new RuntimeException("Cannot reduce a constant");
    }

    @Override
    public List<Bound> getBounds(List<Bound> bs) {
        return bs;
    }

    /**
     * Equality between two constants. This is made more painful because when
     * comparing function signatures coming from TypeDiscovery and code_warntype,
     * there irrelevant differences such as `typeof(+)` and `typeof(Base.+)` which
     * should be considered equal. The problem is that the parser does not interpret
     * these constants, so we don't get an opportunity to normalize the names. One
     * solution would be to extend the parser to handle values. Or, hack up this
     * method.
     */
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Con c) {
            if (nm.equals(c.nm)) return true;
            if (nm.startsWith("typeof") && c.nm.startsWith("typeof")) {
                App.print(this + " ==? " + c);
            }
            return true;
        } else
            return false;
    }
}

/**
 * An existential type is of the form "∃ T. ty" where T is a variable and ty is
 * a type.
 */
record Exist(Bound b, Type ty) implements Type, Serializable {

    @Override
    public String toString() {
        return CodeColors.exists("∃") + b + CodeColors.exists(".") + ty;
    }

    @Override
    public Type deepClone(HashMap<Bound, Bound> map) {
        var newmap = new HashMap<Bound, Bound>(map);
        return new Exist(b.deepClone(newmap), ty.deepClone(newmap));
    }

    @Override
    public boolean structuralEquals(Type t) {
        return t instanceof Exist e && b.structuralEquals(e.b) && ty.structuralEquals(e.ty);
    }

    @Override
    public String toJulia() {
        return ty.toJulia() + " where " + b.toJulia();
    }

    @Override
    public boolean isAny() {
        return false;
    }

    @Override
    public boolean isEmpty() {
        return false; // That is not true. \E T. Union{} is empty. And unlikely.
    }

    @Override
    public boolean isConcrete() {
        return false;
    }

    @Override
    public Type expandAliases(HashMap<Bound, Bound> bs) {
        var newb = b.expandAliases(bs);
        var newbs = new HashMap<Bound, Bound>(bs);
        newbs.put(b, newb);
        return new Exist(newb, ty.expandAliases(newbs));
    }

    @Override
    public Type reduce(List<Type> args, HashMap<Bound, Type> replacements, HashMap<Bound, Bound> bounds) {
        if (!args.isEmpty()) {
            var head = args.removeFirst();
            var newrepls = new HashMap<Bound, Type>(replacements);
            newrepls.put(b, head);
            return ty.reduce(args, newrepls, bounds);
        } else {
            var newb = b.reduce(List.of(), replacements, bounds);
            var newbs = new HashMap<Bound, Bound>(bounds);
            newbs.put(b, newb);
            return new Exist(newb, ty.reduce(List.of(), replacements, newbs));
        }
    }

    @Override
    public List<Bound> getBounds(List<Bound> bs) {
        bs.addLast(b);
        return ty.getBounds(bs);
    }
}

/**
 * A Untion type is of the form "T1 | T2 | ... | Tn" where T1, ..., Tn are
 * types. An empty union represents no values.
 */
record Union(List<Type> tys) implements Type, Serializable {

    @Override
    public String toString() {
        var str = tys.stream().map(Type::toString).collect(Collectors.joining(CodeColors.union("|")));
        return CodeColors.union("[") + str + CodeColors.union("]");
    }

    @Override
    public Type deepClone(HashMap<Bound, Bound> map) {
        return new Union(tys.stream().map(t -> t.deepClone(map)).collect(Collectors.toList()));
    }

    @Override
    public boolean structuralEquals(Type t) {
        if (t instanceof Union u) {
            if (tys.size() != u.tys.size()) return false;
            for (int i = 0; i < tys.size(); i++)
                if (!tys.get(i).structuralEquals(u.tys.get(i))) return false;
            return true;
        }
        return false;
    }

    @Override
    public String toJulia() {
        var str = tys.stream().map(Type::toJulia).collect(Collectors.joining(","));
        return "Union{" + str + "}";
    }

    @Override
    public boolean isAny() {
        return false;
    }

    @Override
    public boolean isEmpty() {
        return tys.isEmpty();
    }

    /**
     * A union is concrete if it has a single element that is concrete.
     *
     * Julia does not consider a type Union{} to be concrete, yet for our purposes
     * perhaps it should be treated as such. The type does not represent any value,
     * so would it not be "stable"?
     */
    @Override
    public boolean isConcrete() {
        return //tys.isEmpty() ||
        (tys.size() == 1 && tys.get(0).isConcrete());
    }

    @Override
    public Type expandAliases(HashMap<Bound, Bound> bs) {
        return new Union(tys.stream().map(t -> t.expandAliases(bs)).collect(Collectors.toList()));
    }

    @Override
    public Type reduce(List<Type> args, HashMap<Bound, Type> replacements, HashMap<Bound, Bound> bounds) {
        return new Union(tys.stream().map(t -> t.reduce(List.of(), replacements, bounds)).collect(Collectors.toList()));
    }

    @Override
    public List<Bound> getBounds(List<Bound> bs) {
        return bs;
    }

}

/**
 * A tuple type is of the form "(T1, T2, ..., Tn)" where T1, ..., Tn are types.
 */
record Tuple(List<Type> tys) implements Type, Serializable {

    @Override
    public String toString() {
        var str = tys.stream().map(t -> t == null ? "NULL" : t.toString()).collect(Collectors.joining(CodeColors.tuple(",")));
        return CodeColors.tuple("(") + str + CodeColors.tuple(")");
    }

    @Override
    public Type deepClone(HashMap<Bound, Bound> map) {
        return new Tuple(tys.stream().map(t -> t.deepClone(map)).collect(Collectors.toList()));
    }

    @Override
    public boolean structuralEquals(Type t) {
        if (t instanceof Tuple tu) {
            if (tys.size() != tu.tys.size()) return false;
            for (int i = 0; i < tys.size(); i++) {
                if (!tys.get(i).structuralEquals(tu.tys.get(i))) return false;
            }
            return true;
        }
        return false;
    }

    @Override
    public String toJulia() {
        var str = tys.stream().map(Type::toJulia).collect(Collectors.joining(","));
        return "Tuple{" + str + "}";
    }

    @Override
    public boolean isAny() {
        return false;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    /**
     * A tuple is concrete if all of its elements are concrete. An empty tuple is
     * concrete. The type `Tuple` without any parameters is not concrete.
     */
    @Override
    public boolean isConcrete() {
        return tys.isEmpty() || tys.stream().allMatch(Type::isConcrete);
    }

    @Override
    public Type expandAliases(HashMap<Bound, Bound> bs) {
        return new Tuple(tys.stream().map(t -> t.expandAliases(bs)).collect(Collectors.toList()));
    }

    @Override
    public Type reduce(List<Type> args, HashMap<Bound, Type> replacements, HashMap<Bound, Bound> bounds) {
        return new Tuple(tys.stream().map(t -> t.reduce(List.of(), replacements, bounds)).collect(Collectors.toList()));
    }

    @Override
    public List<Bound> getBounds(List<Bound> bs) {
        return bs;
    }

}

/**
 * Represents a declaration of a type where `mod` is the type modifiers (e.g.
 * "abstract type"), `nm` is the type's name, `ty` has the parameters (e.g. "E
 * T. Vector{T}"), `parInst` is the the parent's instance (e.g. "AbsV{Int}" in
 * "V{Int} <: AbsV{Int}"), `parent` is the parent's declaration and `src` is the
 * source code of the declaration.
 */
record Decl(String mod, TypeName nm, Type ty, Decl parent, String src) implements Serializable {

    @Override
    public String toString() {
        var ignore = nm.isAny() || this.parent.nm.isAny(); // parent is null for Any
        return CodeColors.comment(nm + " ≡ ") + mod + " " + thisTy() + (ignore ? "" : CodeColors.comment(" <: ") + parentTy());
    }

    Type thisTy() {
        var t = ty;
        var bounds = t.getBounds(new ArrayList<>());
        while (t instanceof Exist e)
            t = e.ty();
        var pair = (Tuple) t;
        t = pair.tys().get(0);
        while (!bounds.isEmpty())
            t = new Exist(bounds.removeLast(), t);
        return t;
    }

    Inst parentTy() {
        var t = ty;
        while (t instanceof Exist e)
            t = e.ty();
        var pair = (Tuple) t;
        return (Inst) pair.tys().get(1);
    }

    /**
     * @return true if the class is abstract or if its definition was missing.
     */
    public boolean isAbstract() {
        return mod.contains("abstract") || mod.contains("missing");
    }

    /**
     * Returns the number of arguments for this type (e.g. 2 for `Vec{T, N}`).
     */
    int argCount() {
        var t = ty;
        var cnt = 0;
        while (t instanceof Exist e) {
            t = e.ty();
            cnt++;
        }
        return cnt;
    }

    Decl expandAliases() {
        var newty = Type.expandAliasesFixpoint(ty);
        return new Decl(mod, nm, newty, parent, src);
    }
}

/**
 * A type signature is of the form "f{T}(x::T, y::T) where T" where f is the
 * function name, T is a type variable, x and y are arguments and T is a type
 * bound.
 */
record Sig(FuncName nm, Type ty, List<String> argnms, int kwPos, String src) implements Serializable {

    // we say a method signature is "ground" if all of its arguments are concrete
    boolean isGround() {
        return ty instanceof Tuple tup ? tup.isConcrete() : false;
    }

    @Override
    public String toString() {
        return nm + " " + ty;
    }

    String toJulia() {
        var t = ty;
        var bounds = new ArrayList<Bound>();
        while (t instanceof Exist e) {
            bounds.add(e.b());
            t = e.ty();
        }
        Tuple args = (Tuple) t;
        var str = args.tys().stream().map(Type::toJulia).collect(Collectors.joining(","));
        return "function " + nm + "(" + str + ")" + (bounds.isEmpty() ? "" : (" where {" + bounds.stream().map(Bound::toJulia).collect(Collectors.joining(",")) + "}"));
    }

    /**
     * Returns the number of arguments of this function. The encoding has a number
     * of existentials wrapping a tuple, so we unwrap the existentials and return
     * the tuple's arity.
     */
    // TODO: varargs
    int arity() {
        var t = ty;
        while (t instanceof Exist e)
            t = e.ty();
        return t instanceof Tuple tup ? tup.tys().size() : 0;
    }

    /**
     * Structural equality means two signatures have the same syntactic structure
     * and the same operation names. (We ignore package names as they may be
     * abbreviated)
     */
    boolean structuralEquals(Sig other) {
        if (!nm.operationName().equals(other.nm().operationName())) return false;
        return ty.structuralEquals(other.ty());
    }

    Sig expandAliases() {
        var newty = Type.expandAliasesFixpoint(ty);
        return new Sig(nm, newty, argnms, kwPos, src);
    }
}

/**
 * Information about a method returned by code_warntype.
 */
class Method implements Serializable {
    Sig sig; // method signature that was used to instantiate
    Sig originSig; // method signature that was declared
    HashMap<String, Type> env = new HashMap<>(); // map from all variable/arg names to their inferred type
    List<String> argNames = new ArrayList<>(); // list of argument names
    Type returnType; // return type of the method
    List<Calls> ops = new ArrayList<>(); // operations that we were able to parse
    String filename; // source file name with code_warntype information
    String originPackageAndFile; // where does the code come from?

    /**
     * A function call. The field tgt is the possibly null variable the result is
     * assigned to, this can be a temporary %1 or a local variable. The args contain
     * the argumets passed. Since our parser is approximate, this may be wrong.
     */
    record Calls(String tgt, List<String> args, Sig called) {

        @Override
        public String toString() {
            var s = tgt != null ? tgt + " = " : "";
            return s + called + " (" + args.stream().collect(Collectors.joining(", ")) + ")";
        }
    }

    /**
     * Constructor gets the raw MethodInformation from the parser along with the
     * name of source file and performs all the necessary clean up and
     * transformations.
     */
    Method(MethodInformation mi, String filename) {
        this.sig = mi.decl.expandAliases();
        this.filename = filename;
        this.originSig = mi.originDecl.expandAliases();
        this.returnType = Type.expandAliasesFixpoint(mi.returnType);
        this.originPackageAndFile = mi.src;
        this.argNames = mi.argnames;
        for (int i = 0; i < mi.argnames.size(); i++)
            env.put(mi.argnames.get(i), Type.expandAliasesFixpoint(mi.arguments.get(i)));
        for (int i = 0; i < mi.locals.size(); i++)
            env.put(mi.locnames.get(i), Type.expandAliasesFixpoint(mi.locals.get(i)));
        for (var oper : mi.ops)
            if (oper instanceof RegAssign op && op.tgt() != null && op.ret() != null) {
                if (!env.containsKey(op.tgt())) {
                    var ty = op.ret().toTy().fixUp(new ArrayList<>());
                    env.put(op.tgt(), Type.expandAliasesFixpoint(ty.toType(new ArrayList<>())));
                }
            }

        for (var oper : mi.ops) {
            if (oper instanceof RegAssign op) {
                if (op.op().toString().equals("Core.Const") || op.op().toString().equals("Core.NewvarNode")) continue;
                var tys = new ArrayList<Type>();
                for (var arg : op.args()) {
                    if (env.containsKey(arg)) {
                        tys.add(env.get(arg));
                    } else {
                        if (arg.charAt(0) == '\"') {
                            tys.add(new Inst(GenDB.it.names.string(), List.of()));
                        } else if (arg.charAt(0) == ':') {
                            tys.add(new Inst(GenDB.it.names.symbol(), List.of()));
                        } else if (arg.charAt(0) >= '0' && arg.charAt(0) <= '9') {
                            tys.add(new Inst(GenDB.it.names.int64(), List.of()));
                        } else if (arg.equals("false") || arg.equals("true")) {
                            tys.add(new Inst(GenDB.it.names.bool(), List.of()));
                        } else if (arg.equals("nothing")) {
                            tys.add(new Inst(GenDB.it.names.nothing(), List.of()));
                        } else {
                            if (!GenDB.it.seenMissing.contains(arg)) {
                                GenDB.it.seenMissing.add(arg);
                                App.output("Missing type for " + arg);
                            }
                            tys.add(GenDB.it.types.get(GenDB.it.names.any()).decl.ty());
                        }
                    }
                }
                var s = new Sig(op.op(), new Tuple(tys), List.of(), -1, "none");
                var args = new ArrayList<String>();
                for (var arg : op.args())
                    if (env.containsKey(arg)) args.add(arg);
                ops.add(new Calls(op.tgt(), args, s));
            }
        }
    }

    @Override
    public String toString() {
        var s = originSig + ":: " + returnType + " @ " + filename + "\n";
        s += "  instance = " + sig + "\n";
        s += "  " + env + "\n-----\nOps:\n";
        for (var op : ops)
            s += "   " + op + "\n";
        return s;
    }
}
