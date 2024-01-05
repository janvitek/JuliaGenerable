package prlprg;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import prlprg.Parser.TypeDeclaration;

class GenDB {

    static class Types {

        final private HashMap<String, Info> db = new HashMap<>(); // all types
        final HashSet<String> reusedNames = new HashSet<>(); // names of types that are reused (i.e. types with multiple declarations)
        final HashMap<String, String> upperCaseNames = new HashMap<>(); // code_warntype returns upper case names

        // The Info class holds all the information we have on types including various stages of preparation.
        // TypeDeclaration -> TyDecl -> TyDecl -> Decl
        // The TypeDeclaration we get from the parser is still ill-formed, it confuses some constants and type names and
        // variables. The first TyDecl is in better shape but still refers to some things as types or variables in
        // a confused way. The second TyDecl adds any unbound names to the DB. The last Decl is fully typed and
        // are ready to be used for generation.
        //
        // The class keeps all these stages for debugging purposes.
        class Info {

            String nm; //name of the type
            boolean defMissing; // was this patched because it was missing ?
            TypeDeclaration parsed; // the parsed type declaration
            TyDecl pre_patched;  // the type declaration before patching
            TyDecl patched; // the type declaration after patching
            Decl decl;  // the final form of the type declaration, this is used for subtype generation
            List<String> subtypes = List.of(); // names of direct subtypes
            Info parent = null; // the parent of this type
            String parentName; // the name of the parent
            List< Info> children = new ArrayList<>();  // nodes of direct children in the type hierarchy
            List<Type> level_1_kids = new ArrayList<>(); // the Fuel==1 children of this type

            // Create a type info from a parsed type declaration
            Info(TypeDeclaration ty) {
                NameUtils.registerName(ty.nm());
                this.nm = ty.nm();
                this.parsed = ty;
                this.pre_patched = ty.toTy();
                this.patched = pre_patched; // default, good for simple types
                this.defMissing = false;
            }

            // Create a type info for a missing type
            Info(String missingType) {
                this.nm = missingType;
                NameUtils.registerName(nm);
                this.decl = isAny() ? new Decl("abstract type", "Any", any, any, null, "")
                        : new Decl("missing type", nm, new Inst(nm, new ArrayList<>()), any, null, "NA");
                this.defMissing = true;
            }

            final boolean isAny() {
                return nm.equals("Any");
            }

            // For types that are not missing we run fixUp to deal with variables and type names. FixUp will
            // add types to the DB (i.e. patching). Types without a definition are not patched, i.e. the
            // patched and pre_patched are identical. We also set the parent of this info node. If this is Any,
            // then the parent is itself. We add children to the parent node.
            void fixUpParent() {
                if (!defMissing) {
                    try {
                        patched = pre_patched.fixUp();
                    } catch (Exception e) {
                        App.warn("Error: " + nm + " " + e.getMessage() + "\n" + CodeColors.comment("Failed at " + pre_patched.src()));
                    }
                }
                if (isAny()) {
                    parentName = nm;
                    parent = this;
                    return;
                }
                parentName = defMissing ? "Any" : ((TyInst) patched.parent()).nm();
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

            // Unwrap the existentials in the type, and return the bounds in the order they appear.
            // Called with a type declaration, so there should only be instances and existentials.
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
        }

        // Check if there is a node for this type name, if not then create one and mark it as missing, report
        // a warning that the type had to be 'patched'. This can come about because of builtin types, any other
        // reason is fishy.
        void patchIfNeeeded(String nm) {
            if (get(nm) == null) {
                App.warn("Type " + nm + " not found, patching");
                db.put(nm, new Info(nm));
            }
        }

        // Transform all types to Decls, this is done after all types have been patched.
        void toDeclAll() {
            types.get("Any").toDecl();
        }

        // Fix up all types, this is done before any type is transformed to a Decl.
        void fixUpAll() {
            if (!types.reusedNames.isEmpty()) {
                App.warn("Multiple type definitions for: " + types.reusedNames.stream().collect(Collectors.joining(", ")));
            }
            all().forEach(i -> i.fixUpParent());
        }

        List<Info> all() {
            return new ArrayList<>(db.values());
        }

        Info get(String nm) {
            return db.get(nm);
        }

        List<String> getSubtypes(String nm) {
            var info = get(nm);
            return info == null ? null : info.subtypes;
        }

        /**
         * Add a parsed type declaration to the DB. This is called from the
         * parser. This method also takes care of adding the name of the type in
         * the upperCaseNames map and the reusedNames set.
         *
         * @param ty the type declaration
         */
        void addParsed(TypeDeclaration ty) {
            var nm = ty.nm();
            // We expect each type to have a non ambiguous upper case name, if not the case,
            // either accept inaccuracy or change the code that uses code_warntype.
            var upper = nm.toUpperCase();
            if (upperCaseNames.containsKey(upper)) {
                var other = upperCaseNames.get(upper);
                if (!other.equals(nm)) {
                    App.warn("!!!Types " + other + " and " + nm + " have same capitalization!!!");
                }
            }
            upperCaseNames.put(upper, nm);
            // We expect a single definition per type. If there are multiple we have a
            // a vague hope that they are the same. This is not checked!
            if (get(nm) != null) {
                reusedNames.add(nm); // remember we have seen this type before and overwrite it
            }
            db.put(nm, new Info(ty));
        }

        // Is this a concrete type ? This can only be used once decls is populated in build().
        boolean isConcrete(String nm, int passedArgs) {
            var i = get(nm);
            return i.decl != null && !i.decl.isAbstract() && i.decl.argCount() == passedArgs;
        }

        /**
         * A comparator for sorting types by name alphabetically.
         */
        static class NameOrder implements Comparator<Types.Info> {

            @Override
            public int compare(Info n1, Info n2) {
                return n1.nm.compareTo(n2.nm);
            }
        }

        void printHierarchy() {
            if (App.PRINT_HIERARCHY) {
                App.output("\nPrinting type hierarchy (in LIGHT color mode, RED means missing declaration, GREEN means abstract )");
                printHierarchy(get("Any"), 0);
            }
        }

        private void printHierarchy(Info n, int pos) {
            var str = n.decl == null || n.decl.isAbstract() ? CodeColors.abstractType(n.nm) : n.nm;
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

    static class Signatures {

        class Info {

            TySig pre_patched;
            TySig patched;
            Sig sig;
        }

        final private HashMap<String, List<Info>> db = new HashMap<>();

        List<Info> get(String nm) {
            var res = db.get(nm);
            if (res == null) {
                db.put(nm, res = new ArrayList<>());
            }
            return res;
        }

        Info make(String nm) {
            var info = new Info();
            var res = get(nm);
            res.add(info);
            return info;
        }

        List<String> allNames() {
            return new ArrayList<>(db.keySet());
        }

        List<Sig> allSigs() {
            var res = new ArrayList<Sig>();
            for (var nm : allNames()) {
                for (var info : get(nm)) {
                    res.add(info.sig);
                }
            }
            return res;
        }

        void fixUpAll() {
            // make sure that upperNames have all types
            types.upperCaseNames.put("ANY", "Any");
            types.upperCaseNames.put("UNION", "Union");
            types.upperCaseNames.put("TUPLE", "Tuple");
            for (var name : allNames()) {
                for (var sig : get(name)) {
                    sig.patched = sig.pre_patched.fixUp(new ArrayList<>());
                }
            }
        }

        void toDeclAll() {
            for (var nm : allNames()) {
                for (var s : get(nm)) {
                    var n = s.patched;
                    try {
                        s.sig = new Sig(nm, n.ty().toType(new ArrayList<>()), n.src());
                        App.info(s.sig.toString());
                    } catch (Exception e) {
                        App.warn("Error: " + n.nm() + " " + e.getMessage() + "\n" + CodeColors.comment("Failed at " + n.src()));
                    }
                }
            }
        }
    }

    static final Types types = new Types();
    static final Signatures sigs = new Signatures();
    static Inst any = new Inst("Any", List.of());
    static Union none = new Union(List.of());

    static final void addSig(TySig sig) {
        sigs.make(sig.nm()).pre_patched = sig;
    }

    // Definitions in the pre DB are ill-formed, as we don't know what identifiers refer to types or
    // variables. We asume all types declarations have been processed, so anything not in tydb is
    // is either a variable or a missing type.
    static public void cleanUp() {
        sigs.fixUpAll();
        types.fixUpAll();
        types.toDeclAll();
        types.printHierarchy();
        sigs.toDeclAll();
    }

}

interface Type {

    @Override
    public String toString();

    // Return a clone of the type with new Bounds. When called exterrnally, the map should be empty.
    Type deepClone(HashMap<Bound, Bound> map);

    // Two term that have exactly the same syntactic structure, does not try for semantic equality.
    boolean structuralEquals(Type t);

    // Return a Julia string representation of the type
    String toJulia();

    // The top type "Any"
    boolean isAny();

    // The empty union
    boolean isNone();

    // Inst, Tuples, amd Cons are concrete
    boolean isConcrete();

}

// A construtor instance may have type parameters, examples are: Int32 and Vector{Int,N}. The LHS
// of a type declaration can only have bound variables. The RHS of a type declaration can have a
// mix of instance and variables (bound on LHS of <:). On its own, an instance should not have
// free variables.
record Inst(String nm, List<Type> tys) implements Type {

    @Override
    public String toString() {
        var args = tys.stream().map(Type::toString).collect(Collectors.joining(","));
        var snm = NameUtils.shorten(nm);
        return snm + (tys.isEmpty() ? "" : "{" + args + "}");
    }

    @Override
    public Type deepClone(HashMap<Bound, Bound> map) {
        return new Inst(nm, tys.stream().map(t -> t.deepClone(map)).collect(Collectors.toList()));
    }

    @Override
    public boolean structuralEquals(Type t) {
        if (t instanceof Inst i) {
            if (!nm.equals(i.nm) || tys.size() != i.tys.size()) {
                return false;
            }
            for (int j = 0; j < tys.size(); j++) {
                if (!tys.get(j).structuralEquals(i.tys.get(j))) {
                    return false;
                }
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
        return nm.equals("Any");
    }

    @Override
    public boolean isNone() {
        return false;
    }

    @Override
    public boolean isConcrete() {
        return GenDB.types.isConcrete(nm, tys.size());
    }

    List<Decl> subtypeDecls() {
        var res = new ArrayList<Decl>();
        var wl = new ArrayList<String>();
        wl.add(nm);
        while (!wl.isEmpty()) {
            for (var subnm : GenDB.types.getSubtypes(wl.removeFirst())) {
                res.add(GenDB.types.get(nm).decl);
                wl.add(subnm);
            }
        }
        return res;
    }
}

// A variable refers to a Bound in an enclosing Exist. Free variables are not expected.
record Var(Bound b) implements Type {

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

// A Bound introduces a variable, with an upper and a lower bound. Julia allows writing inconsistent
// bounds, i.e. !(low <: up). These are meaningless types which cannot be used. We do not check this.
// We check that types are well-formed (no undefined constructor and no free variables)
record Bound(String nm, Type low, Type up) {

    @Override
    public String toString() {
        return (!low.isNone() ? low + "<:" : "") + CodeColors.variable(nm) + (!up.isAny() ? "<:" + up : "");
    }

    public Bound deepClone(HashMap<Bound, Bound> map) {
        var me = new Bound(nm, low, up); // fix up and low
        map.put(this, me);
        return me;
    }

    public boolean structuralEquals(Bound b) {
        return nm.equals(b.nm) && low.structuralEquals(b.low) && up.structuralEquals(b.up);
    }

    public String toJulia() {
        return (!low.isNone() ? low.toJulia() + "<:" : "") + nm + (!up.isAny() ? "<:" + up.toJulia() : "");
    }

}

// A constant, such as a number, character or string. The implementation of the parser does not attempt
// do much we constant, they are treated as uninterpreted strings.
record Con(String nm) implements Type {

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

record Exist(Bound b, Type ty) implements Type {

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

record Union(List<Type> tys) implements Type {

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
            if (tys.size() != u.tys.size()) {
                return false;
            }
            for (int i = 0; i < tys.size(); i++) {
                if (!tys.get(i).structuralEquals(u.tys.get(i))) {
                    return false;
                }
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

    @Override
    public boolean isConcrete() {
        return false; // technically a union with one elment that is concrete is concrete
    }

}

record Tuple(List<Type> tys) implements Type {

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
            if (tys.size() != tu.tys.size()) {
                return false;
            }
            for (int i = 0; i < tys.size(); i++) {
                if (!tys.get(i).structuralEquals(tu.tys.get(i))) {
                    return false;
                }
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

    @Override
    public boolean isConcrete() {
        return true;
    }

    boolean elementsConcrete() {
        return tys.stream().allMatch(Type::isConcrete);
    }

}

/**
 * Represents a declaration of a type where `mod` is the type modifiers (e.g.
 * "abstract type"), `nm` is the type's name, `ty` has the parameters (e.g. "E
 * T. Vector{T}"), `parInst` is the the parent's instance (e.g. "AbsV{Int}" in
 * "V{Int} <: AbsV{Int}"), `parent` is the parent's declaration and `src` is the
 * source code of the declaration.
 */
record Decl(String mod, String nm, Type ty, Inst parInst, Decl parent, String src) {

    @Override
    public String toString() {
        var ignore = nm.equals("Any") || this.parent.nm.equals("Any"); // parent is null for Any
        var snm = NameUtils.shorten(nm);
        return CodeColors.comment(snm + " ≡ ") + mod + " " + ty + (ignore ? "" : CodeColors.comment(" <: ") + parInst);
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
        while ( t instanceof Exist e) {
            t = e.ty();
            cnt++;
        }
        return cnt;
    }
}

record Sig(String nm, Type ty, String src) {

    // we say a method signature is "ground" if all of its arguments are concrete
    boolean isGround() {
        return ty instanceof Tuple tup ? tup.elementsConcrete() : false;
    }

    @Override
    public String toString() {
        return "function " + nm + " " + ty;
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
        return "function " + nm + "(" + str + ")"
                + (bounds.isEmpty() ? ""
                : (" where {" + bounds.stream().map(Bound::toJulia).collect(Collectors.joining(",")) + "}"));
    }

}
