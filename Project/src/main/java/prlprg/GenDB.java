package prlprg;

import prlprg.NameUtils.FuncName;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.HashSet;

import prlprg.NameUtils.TypeName;
import prlprg.Parser.MethodInformation;
import prlprg.Parser.TypeDeclaration;

/**
 * A database containing all the information that we have acquired about types
 * and signatures of methods.
 *
 * NOTE: for some reason I have static variables in the DB, perhaps this should
 * be revisited. I don't recall why that was.
 */
class GenDB implements Serializable {
    class Aliases implements Serializable {
        final private HashMap<TypeName, Alias> db = new HashMap<>();
        final private HashMap<String, List<Alias>> shortNames = new HashMap<>();

        class Alias implements Serializable {
            TypeName nm;
            Ty pre_patched;
            Ty patched;
            Type sig;

            Alias(TypeName tn) {
                nm = tn;
            }

            @Override
            public String toString() {
                return "alias " + nm + " = " + sig == null ? pre_patched.toString() : sig.toString();
            }

            void fixUp() {
                try {
                    patched = pre_patched.fixUp();
                } catch (Exception e) {
                    App.print("Error: " + nm + " " + e.getMessage() + "\n" + CodeColors.comment("Failed at " + pre_patched.src()));
                }
            }
        }

        void addParsed(TypeName tn, Ty sig) {
            if (db.get(tn) != null) throw new RuntimeException("Alias already defined");
            var alias = new Alias(tn);
            alias.pre_patched = sig;
            db.put(tn, alias);
            var names = shortNames.getOrDefault(tn.nm, new ArrayList<>());
            names.add(alias);
        }

        @Override
        public String toString() {
            return "GenDB.Aliases( " + db.size() + " )";
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
            boolean defMissing; // was this patched because it was missing ?
            TyDecl pre_patched; // the type declaration before patching
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
                this.nm = ty.nm();
                this.pre_patched = ty.toTy();
                this.patched = pre_patched; // default, good for simple types
                this.defMissing = false;
            }

            /**
             * Create a type info for a missing type
             */
            Info(TypeName missingType) {
                this.nm = missingType;
                this.decl = isAny() ? new Decl("abstract type", names.getShort("Any"), any, any, null, "") : new Decl("missing type", nm, new Inst(nm, new ArrayList<>()), any, null, "NA");
                this.defMissing = true;
            }

            void replaceWith(Info i) {
                if (!defMissing) {
                    App.print("Replacing a non-missing type");
                }
                this.nm = i.nm;

                this.pre_patched = i.pre_patched;
                this.patched = i.patched;
                this.defMissing = i.defMissing;
            }

            final boolean isAny() {
                return nm.isAny();
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
                if (!defMissing) {
                    try {
                        patched = pre_patched.fixUp();
                    } catch (Exception e) {
                        App.print("Error: " + nm + " " + e.getMessage() + "\n" + CodeColors.comment("Failed at " + pre_patched.src()));
                    }
                }
                if (isAny()) {
                    parentName = nm;
                    parent = this;
                    return;
                }
                parentName = defMissing ? names.getShort("Any") : ((TyInst) patched.parent()).nm();
                parent = get(parentName);
                parent.children.add(this);
            }

            // Final transformatoin to a Decl by a top down traversal of the hierarchy.
            // Info nodes that are missing are provided with a default Decl on creation.
            void toDecl() {
                if (!defMissing) {
                    var env = new ArrayList<Bound>();
                    var t = patched.ty().toType(env);
                    var inst = patched.parent().toType(getBounds(t, env));
                    decl = new Decl(patched.mod(), nm, t, (Inst) inst, parent.decl, patched.src());
                }
                subtypes = children.stream().map(c -> c.nm).collect(Collectors.toList());
                children.forEach(c -> c.toDecl());
            }

            /**
             * Unwrap the existentials in the type, and return the bounds in the order they
             * appear. Called with a type declaration, so there should only be instances and
             * existentials.
             */
            private List<Bound> getBounds(Type t, List<Bound> env) {
                if (t instanceof Inst) {
                    return env;
                } else if (t instanceof Exist ty) {
                    env.addLast(ty.b());
                    return getBounds(ty.ty(), env);
                }
                throw new RuntimeException("Unknown type: " + t);
            }

            @Override
            public String toString() {
                return nm + " " + (defMissing ? "missing" : "defined") + " " + parentName;
            }
        } /// End of Info //////////////////////////////////////////////////////////////////////

        // Transform all types to Decls, this is done after all types have been patched.
        void toDeclAll() {
            types.get(names.getShort("Any")).toDecl();
        }

        /**
         * Fix up the type hierarchy starting at Any. This method also prints the types
         * we saw mulitple definitions for.
         */
        void fixUpAll() {
            all().forEach(i -> i.fixUpParent());
        }

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
                if (i.nm.juliaEq(tn)) eqs.add(i); //semantic equality not syntactic.
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
            for (var i : infos) // Infos holds all types withe same trailing name (e.g "Any") 
                if (i.nm.juliaEq(tn)) it = i; // assume we are adding Core.Any and "".Any  is in ...
            if (it == null)
                infos.add(new Info(ty));
            else {
                it.replaceWith(new Info(ty));
            }
        }

        void addMissing(TypeName nm) {
            if (get(nm) != null) return; // got it already
            var infos = db.get(nm.nm);
            if (infos == null) db.put(nm.nm, infos = new ArrayList<>());
            infos.add(new Info(nm));
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
            App.output("\nPrinting type hierarchy (in LIGHT color mode, RED means missing declaration, GREEN means abstract )");
            printHierarchy(get(names.getShort("Any")), 0);

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
            return "Type db with " + db.size() + " entries";
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
         * Represents a method. The name is the full method name, the pre_patched and
         * patched are intermediary states coming from the Parser, and the sig is the
         * final form.
         */
        class Info implements Serializable {
            FuncName nm;
            TySig pre_patched;
            TySig patched;
            Sig sig;
            ArrayList<Result> results = new ArrayList<>();

            Info(FuncName fn) {
                this.nm = fn;
            }

            @Override
            public String toString() {
                return nm + " " + (sig == null ? "missing" : "defined");
            }

            void addResult(Type argTy, Type retTy) {
                results.add(new Result(argTy, retTy));
            }
        }

        /**
         * The DB is a map from operation names to a list of methods.
         */
        final private HashMap<String, List<Info>> db = new HashMap<>();

        /**
         * Return the list of signatures for a name. If the name is not in the DB,
         * create an empty list.
         *
         * @return a list (never null)
         */
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
         * Fix up all signatures. Call this to patch the databases after the types have
         * been patched.
         */
        void fixUpAll() {
            // make sure that upperNames have all types
            for (var name : allNames()) {
                for (var sig : get(name))
                    sig.patched = sig.pre_patched.fixUp(new ArrayList<>());
            }
        }

        /**
         * Transform all signatures to Sigs. This is done after all types have been
         * patched.
         */
        void toSigAll() {
            for (var nm : allNames()) {
                for (var s : get(nm)) {
                    var n = s.patched;
                    try {
                        s.sig = new Sig(s.patched.nm(), n.ty().toType(new ArrayList<>()), n.argnms(), n.kwPos(), n.src());
                    } catch (Exception e) {
                        App.print("Error: " + n.nm() + " " + e.getMessage() + "\n" + CodeColors.comment("Failed at " + n.src()));
                    }
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
            if (infos != null) for (var info : infos)
                if (sig.src().equals(info.pre_patched.src())) return info;
            return null;

        }
    }

    static GenDB it = new GenDB();

    NameUtils names = new NameUtils();
    Aliases aliases = new Aliases();
    Types types = new Types();
    Signatures sigs = new Signatures();
    Inst any = new Inst(names.getShort("Any"), List.of());
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
        if (SAVE) return;
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
     * Functions withe keyword arguments are compiled to an equivalent function
     * without any, we ignore the keyword version.
     */
    final void addSig(TySig sig) {
        var nm = sig.nm().operationName();
        if (nm.equals("kwcall")) return;
        if (sig.kwPos() >= 0) return;
        if (sigs.find(sig) == null) sigs.make(sig.nm()).pre_patched = sig;
    }

    /**
     * Performs all transformation needed for the types in the DB to be useable for
     * genreation.
     */
    public void cleanUp() {
        sigs.fixUpAll();
        types.fixUpAll();
        types.toDeclAll();
        types.printHierarchy();
        sigs.toSigAll();
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
    boolean isNone();

    /**
     * @return true if this type is a struct with all parameters bound or if it is a
     *         union with a single concrete element. Missing types are not
     *         considered concrete. Currently does not deal correctly with aliases.
     *         The type Vector{Int} is is an alias for Array{Int,1} and is not
     *         considered concrete but should be. TODO revisit treatemnt of aliases.
     */
    boolean isConcrete();

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
        var args = tys.stream().map(Type::toString).collect(Collectors.joining(","));
        return nm + (tys.isEmpty() ? "" : "{" + args + "}");
    }

    @Override
    public Type deepClone(HashMap<Bound, Bound> map) {
        return new Inst(nm, tys.stream().map(t -> t.deepClone(map)).collect(Collectors.toList()));
    }

    @Override
    public boolean structuralEquals(Type t) {
        if (t instanceof Inst i) {
            if (!nm.juliaEq(i.nm) || tys.size() != i.tys.size()) return false;

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
    public boolean isNone() {
        return nm().nm.equals("Nothing"); // THIS IS AN ALIAS... Todo we should deal with aliasses properly
    }

    @Override
    public boolean isConcrete() {
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
}

/**
 * A variable refers to a Bound in an enclosing Exist. Free variables are not
 * allowed.
 *
 */
record Var(Bound b) implements Type, Serializable {

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
    public boolean isNone() {
        return false;
    }

    @Override
    public boolean isConcrete() {
        return false;
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
        return (!low.isNone() ? low + "<:" : "") + CodeColors.variable(nm) + (!up.isAny() ? "<:" + up : "");
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
        return (!low.isNone() ? low.toJulia() + "<:" : "") + nm + (!up.isAny() ? "<:" + up.toJulia() : "");
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
    public boolean isNone() {
        return false;
    }

    @Override
    public boolean isConcrete() {
        return true;
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
    public boolean isNone() {
        return false;
    }

    @Override
    public boolean isConcrete() {
        return false;
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

            for (int i = 0; i < tys.size(); i++) {
                if (!tys.get(i).structuralEquals(u.tys.get(i))) return false;
            }
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
    public boolean isNone() {
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
    public boolean isNone() {
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

}

/**
 * Represents a declaration of a type where `mod` is the type modifiers (e.g.
 * "abstract type"), `nm` is the type's name, `ty` has the parameters (e.g. "E
 * T. Vector{T}"), `parInst` is the the parent's instance (e.g. "AbsV{Int}" in
 * "V{Int} <: AbsV{Int}"), `parent` is the parent's declaration and `src` is the
 * source code of the declaration.
 */
record Decl(String mod, TypeName nm, Type ty, Inst parInst, Decl parent, String src) implements Serializable {

    @Override
    public String toString() {
        var ignore = nm.isAny() || this.parent.nm.isAny(); // parent is null for Any
        return CodeColors.comment(nm + " ≡ ") + mod + " " + ty + (ignore ? "" : CodeColors.comment(" <: ") + parInst);
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
     * the argumets passed.
     *
     * Since our parser is approximate, this may be wrong.
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
        this.sig = mi.decl;
        this.filename = filename;
        this.originSig = mi.originDecl;
        this.returnType = mi.returnType;
        this.originPackageAndFile = mi.src;
        for (var v : mi.arguments) {
            argNames.add(v.nm());
            var ty = v.ty().toTy().fixUp(new ArrayList<>());
            env.put(v.nm(), ty.toType(new ArrayList<>()));
        }
        for (var v : mi.locals) {
            var ty = v.ty().toTy().fixUp(new ArrayList<>());
            env.put(v.nm(), ty.toType(new ArrayList<>()));
        }
        for (var op : mi.ops)
            if (op.tgt() != null && op.ret() != null) {
                if (!env.containsKey(op.tgt())) {
                    var ty = op.ret().toTy().fixUp(new ArrayList<>());
                    env.put(op.tgt(), ty.toType(new ArrayList<>()));
                }
            }
        for (var op : mi.ops) {
            if (op.op().toString().equals("Core.Const") || op.op().toString().equals("Core.NewvarNode")) continue;
            var tys = new ArrayList<Type>();
            for (var arg : op.args()) {
                if (env.containsKey(arg)) {
                    tys.add(env.get(arg));
                } else {
                    if (arg.charAt(0) == '\"') {
                        tys.add(new Inst(GenDB.it.names.getShort("String"), List.of()));
                    } else if (arg.charAt(0) == ':') {
                        tys.add(new Inst(GenDB.it.names.getShort("Symbol"), List.of()));
                    } else if (arg.charAt(0) >= '0' && arg.charAt(0) <= '9') {
                        tys.add(new Inst(GenDB.it.names.getShort("Int64"), List.of()));
                    } else if (arg.equals("false") || arg.equals("true")) {
                        tys.add(new Inst(GenDB.it.names.getShort("Bool"), List.of()));
                    } else if (arg.equals("nothing")) {
                        tys.add(new Inst(GenDB.it.names.getShort("Nothing"), List.of()));
                    } else {
                        if (!GenDB.it.seenMissing.contains(arg)) {
                            GenDB.it.seenMissing.add(arg);
                            App.output("Missing type for " + arg);
                        }
                        tys.add(GenDB.it.types.get(GenDB.it.names.getShort("Any")).decl.ty());
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

    @Override
    public String toString() {
        var s = originSig + ":: " + returnType + " @ " + filename + "\n";
        s += "  instance = " + sig + "\n";
        s += "  " + env + "\n-----\nOps:\n";
        for (var op : ops) {
            s += "   " + op + "\n";
        }
        return s;
    }
}