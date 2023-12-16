package prlprg;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import prlprg.Parser.TypeDeclaration;

class GenDB {

    static class Types {

        final private HashMap<String, Info> db = new HashMap<>();
        final HashSet<String> reusedNames = new HashSet<>(); // names of types that are reused
        List<String> allTypes;

        class Info {

            String nm;
            TypeDeclaration parsed;
            TyDecl pre_patched;
            TyDecl patched;
            Decl decl;
            InhNode inhNode;
            List<String> subtypes = List.of();

            Info(String nm) {
                this.nm = nm;
            }

            @Override
            public String toString() {
                return nm + "\n" + parsed + "\n" + pre_patched + "\n" + patched + "\n" + decl;
            }
        }

        List<String> allTypes() {
            if (allTypes == null) {
                allTypes = new ArrayList<>(db.keySet());
                allTypes.sort(String::compareTo);
            }
            return allTypes;
        }

        private Info getOrMake(String nm) {
            var info = db.get(nm);
            if (info == null) {
                db.put(nm, info = new Info(nm));
            }
            return info;
        }

        List<String> getSubtypes(String nm) {
            var info = db.get(nm);
            return info == null ? null : info.subtypes;
        }

        void addSubtypes(String nm, List<String> subtypes) {
            getOrMake(nm).subtypes = subtypes;
        }

        TyDecl getPrePatched(String nm) {
            var info = db.get(nm);
            return info == null ? null : info.pre_patched;
        }

        TyDecl getPatched(String nm) {
            var info = db.get(nm);
            return info == null ? null : info.patched;
        }

        void addPrePatched(TyDecl ty) {
            getOrMake(ty.nm()).pre_patched = ty;
        }

        void addPatched(TyDecl ty) {
            getOrMake(ty.nm()).patched = ty;
        }

        void addInhNode(InhNode inhNode) {
            getOrMake(inhNode.name).inhNode = inhNode;
        }

        // When we add a declaration, we already have the inhNode, so we can add the children
        void addDecl(Decl decl) {
            var i = getOrMake(decl.nm());
            i.decl = decl;
            var kids = i.inhNode.children;
            var strs = kids.stream().map(c -> c.name).collect(Collectors.toList());
            types.addSubtypes(decl.nm(), strs);
        }

        Decl getDecl(String nm) {
            var info = db.get(nm);
            return info == null ? null : info.decl;
        }

        InhNode getInhNode(String nm) {
            var info = db.get(nm);
            return info == null ? null : info.inhNode;
        }

        TypeDeclaration getParsed(String nm) {
            var info = db.get(nm);
            return info == null ? null : info.parsed;
        }

        void addParsed(TypeDeclaration ty) {
            var tyd = ty.toTy();
            if (App.NO_CLOSURES && tyd.mod().contains("closure")) {
                return;
            }
            var nm = ty.nm();
            var info = getOrMake(nm);
            info.parsed = ty;
            if (getPrePatched(nm) != null) {
                reusedNames.add(nm); // remember we have seen this type before and overwrite it
            }
            addPrePatched(tyd);
        }

        // Is this a concrete type ? This can only be used once decls is populated in build().
        boolean isConcrete(String nm) {
            var d = getDecl(nm);
            return d != null && !d.isAbstract();
        }

        static class NameOrder implements Comparator<Types.InhNode> {

            @Override
            public int compare(InhNode n1, InhNode n2) {
                return n1.name.compareTo(n2.name);
            }
        }

        void printHierarchy(InhNode n, int pos) {
            var str = n.d == null || n.d.isAbstract() ? CodeColors.abstractType(n.name) : n.name;
            str = n.d.mod().contains("missing") ? ("? " + CodeColors.abstractType(str)) : str;
            App.output(CodeColors.comment(".").repeat(pos) + str);
            n.children.sort(new NameOrder());
            for (var c : n.children) {
                printHierarchy(c, pos + 1);
            }
        }

        void build(InhNode n) {
            try {
                GenDB.types.addDecl(n.toDecl());
            } catch (Exception e) {
                App.warn("Error: " + n.name + " " + e.getMessage() + "\n" + "Failed at " + n.decl);
            }
            for (var c : n.children) {
                build(c);
            }
        }

        InhNode make(TyDecl d) {
            return new InhNode(d);
        }

        final class InhNode {

            String name;
            TyDecl decl;
            Decl d = null;
            InhNode parent = null;
            String parentName;
            List< InhNode> children = new ArrayList<>();

            InhNode(TyDecl d) {
                this.decl = d;
                this.name = d.nm();
                this.parentName = ((TyInst) d.parent()).nm();
                NameUtils.registerName(name);
            }

            void fixUp() {
                if (name.equals("Any")) {
                    this.parent = this;
                    return;
                }
                var pNode = GenDB.types.getInhNode(parentName);
                assert pNode != null;
                this.parent = pNode;
                pNode.children.add(this);
            }

            Decl toDecl() {
                if (name.equals("Any")) {
                    d = new Decl("abstract type", "Any", any, any, null, "");
                } else {
                    var env = new ArrayList<Bound>();
                    var t = decl.ty().toType(env);
                    var inst = decl.parent().toType(getBounds(t, env));
                    d = new Decl(decl.mod(), name, t, (Inst) inst, parent.d, decl.src());
                }
                return d;
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
        }

    }

    static class Signatures {

        class Info {

            String nm;
            TySig pre_patched;
            TySig patched;
            Parser.Function parsed;
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
            info.nm = nm;
            var res = get(nm);
            res.add(info);
            return info;
        }

        List<String> allNames() {
            return new ArrayList<>(db.keySet());
        }

    }

    static final Types types = new Types();
    static final Signatures sigs = new Signatures();
    static Inst any = new Inst("Any", List.of());
    static Union none = new Union(List.of());

    static final void addSig(TySig sig) {
        if (App.NO_CLOSURES && sig.nm().startsWith("var#")) {
            return;
        }
        sigs.make(sig.nm()).pre_patched = sig;
    }

    static public void cleanUp() {
        // Definitions in the pre DB are ill-formed, as we don't know what identifiers refer to types or
        // variables. We asume all types declarations have been processed, so anything not in tydb is
        // is either a variable or a missing type.
        for (var name : types.allTypes()) {
            try {
                types.addPatched(types.getPrePatched(name).fixUp());
            } catch (Exception e) {
                System.err.println("Error: " + name + " " + e.getMessage() + "\n"
                        + CodeColors.comment("Failed at " + types.getPrePatched(name).src()));
            }
        }
        if (!types.reusedNames.isEmpty()) {
            System.out.println("Warning: types defined more than once:" + types.reusedNames.stream().collect(Collectors.joining(", ")));
            types.reusedNames.clear();
        }
        for (var name : sigs.allNames()) {
            for (var sig : sigs.get(name)) {
                sig.patched = sig.pre_patched.fixUp(new ArrayList<>());
            }
        }
        // add patched types if any
        for (var name : types.allTypes()) {
            if (types.getPatched(name) == null) {
                types.addPatched(types.getPrePatched(name)); // no need to fixVars because this is a patched type
            }
        }

        var tdb = GenDB.types;
        for (var nm : tdb.allTypes()) {
            tdb.addInhNode(tdb.make(tdb.getPatched(nm)));
        }
        for (var nm : tdb.allTypes()) {
            tdb.getInhNode(nm).fixUp();
        }
        var d = tdb.getInhNode("Any");
        tdb.build(d);
        if (App.PRINT_HIERARCHY) {
            App.output("\nPrinting type hierarchy (in LIGHT color mode, RED means missing declaration, GREEN means abstract )");
            tdb.printHierarchy(d, 0);
        }
        for (var nm : GenDB.sigs.allNames()) {
            for (var sig : GenDB.sigs.get(nm)) {
                var n = sig.patched;
                try {
                    sig.sig = new Sig(nm, n.ty().toType(new ArrayList<>()), n.src());
                    App.info(sig.sig.toString());
                } catch (Exception e) {
                    App.warn("Error: " + n.nm() + " " + e.getMessage());
                    App.warn(CodeColors.comment("Failed at " + n.src()));
                }
            }
        }

        try {
            var w = new BufferedWriter(new FileWriter("test.jl"));
            for (var nm : GenDB.sigs.allNames()) {
                var b = GenDB.sigs.get(nm);
                for (var si : b) {
                    var s = si.sig;
                    if (s.isGround()) {
                        App.info("Ground: " + s);
                        var str = ((Tuple) s.ty()).tys().stream().map(Type::toJulia).collect(Collectors.joining(","));
                        var content = "code_warntype(" + s.nm() + ",[" + str + "])\n";
                        w.write(content);
                    }
                }
            }
        } catch (IOException e) {
            throw new Error(e);
        }
    }

}

interface Type {

    @Override
    public String toString();

    Type deepClone(HashMap<Bound, Bound> map);

    boolean structuralEquals(Type t);

    String toJulia();

    boolean isAny();

    boolean isNone();

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

}

// A type declaration introduces a new type name, with a type instance and a parent.
// The type Any has no parent, and is the root of the type hierarchy.
// We do not print the case where the parent is Any, since it is the default.
record Decl(String mod, String nm, Type ty, Inst inst, Decl parent, String src) {

    @Override
    public String toString() {
        var ignore = nm.equals("Any") || this.parent.nm.equals("Any"); // parent is null for Any
        var snm = NameUtils.shorten(nm);
        return CodeColors.comment(snm + " ≡ ") + mod + " " + ty + (ignore ? "" : CodeColors.comment(" <: ") + inst);
    }

    public boolean isAbstract() {
        return mod.contains("abstract") || mod.contains("missing");
    }
}

record Sig(String nm, Type ty, String src) {

    boolean isGround() {
        if (ty instanceof Tuple t) {
            for (var typ : t.tys()) {
                if (typ instanceof Inst inst) {
                    if (!GenDB.types.isConcrete(inst.nm())) {
                        return false;
                    }
                } else if (typ instanceof Exist) {
                    return false; // not ground
                } else if (typ instanceof Union) {
                    return false; // not ground either
                } else if (typ instanceof Var) {
                    return false; // we should have rejected this earlier (at the exists)
                } else if (typ instanceof Con) {
                    // ok
                } else if (typ instanceof Tuple) {
                    return false; // not ground??
                } else {
                    throw new RuntimeException("Unknown type: " + ty);
                }
            }
            return true;
        } else {
            return false; // i.e. the method has an existential type, definitely not ground
        }
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
