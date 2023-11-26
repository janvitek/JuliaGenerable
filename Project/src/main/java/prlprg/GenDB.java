package prlprg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

class GenDB {

    private static HashMap<String, TyDecl> pre_tydb = new HashMap<>();
    private static HashMap<String, List<TySig>> pre_sigdb = new HashMap<>();

    HashMap<String, TyDecl> tydb = new HashMap<>();
    HashMap<String, List<TySig>> sigdb = new HashMap<>();

    GenDB() {
        // Some types that are needed but not found. A warning is issued for types that already exist,
        // and the new type will override the old one. TODO: add arguments for generic types.
        addTyDecl(new TyDecl("Exception", new TyInst("Exception"), Ty.any(), ""));
        addTyDecl(new TyDecl("Enum", new TyInst("Enum"), Ty.any(), ""));
        addTyDecl(new TyDecl("Function", new TyInst("Function"), Ty.any(), ""));
        addTyDecl(new TyDecl("Tuple", new TyInst("Tuple"), Ty.any(), ""));
        addTyDecl(new TyDecl("Union", new TyInst("Union"), Ty.any(), ""));
        addTyDecl(new TyDecl("EnumX.Enum", new TyInst("EnumX.Enum"), Ty.any(), ""));
        addTyDecl(new TyDecl("UnionAll", new TyInst("UnionAll"), Ty.any(), ""));
        addTyDecl(new TyDecl("DataType", new TyInst("DataType"), Ty.any(), ""));
        addTyDecl(new TyDecl("AbstractVector", new TyInst("AbstractVector"), Ty.any(), "")); // add arguments?
        addTyDecl(new TyDecl("AbstractArray", new TyInst("AbstractArray"), Ty.any(), "")); // add arguments?
        addTyDecl(new TyDecl("AbstractVecOrMat", new TyInst("AbstractVecOrMat"), Ty.any(), "")); // add arguments?
        addTyDecl(new TyDecl("AbstractDict", new TyInst("AbstractDict"), Ty.any(), "")); // add arguments?
    }

    final void addTyDecl(TyDecl ty) {
        if (pre_tydb.containsKey(ty.nm())) {
            System.out.println("Warning: " + ty.nm() + " already exists, replacing");
        }
        pre_tydb.put(ty.nm(), ty);
    }

    final void addSig(TySig sig) {
        if (!pre_sigdb.containsKey(sig.nm())) {
            pre_sigdb.put(sig.nm(), new ArrayList<>());
        }
        pre_sigdb.get(sig.nm()).add(sig);
    }

    public void cleanUp() {
        // At this point the definitions in the DB are ill-formed, as we don't
        // know what identifiers refer to types and what identifiers refer to
        // variables. We asume that we have seen all types declarations, so
        // anything that is not in tydb is a variable.
        // We try to clean up types by removing obvious variables.
        for (var name : pre_tydb.keySet()) {
            tydb.put(name, pre_tydb.get(name).fixVars());
        }
        for (var name : pre_sigdb.keySet()) {
            if (!sigdb.containsKey(name)) {
                sigdb.put(name, new ArrayList<>());
            }
            for (var sig : pre_sigdb.get(name)) {
                sigdb.get(name).add(sig.fixVars());
            }
        }
    }

    // The following types are used in the generator as an intermediate step towards the
    // final result in the Generator.
    interface Ty {

        final static Ty Any = new TyInst("Any", List.of());
        final static Ty None = new TyUnion(List.of());

        static Ty any() {
            return Any;
        }

        static Ty none() {
            return None;
        }

        Ty fixVars();

        Generator.Type toType(List<Generator.Bound> env);
    }

    record TyInst(String nm, List<Ty> tys) implements Ty {

        // An instance without arguments
        TyInst(String nm) {
            this(nm, List.of());
        }

        @Override
        public String toString() {
            var args = tys.stream().map(Ty::toString).collect(Collectors.joining(","));
            return nm + (tys.isEmpty() ? "" : "{" + args + "}");
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof TyInst t
                    ? nm.equals(t.nm()) && tys.equals(t.tys()) : false;
        }

        @Override
        public Ty fixVars() {
            return new TyInst(nm, tys.stream().map(Ty::fixVars).collect(Collectors.toList()));
        }

        @Override
        public Generator.Type toType(List<Generator.Bound> env) {
            return new Generator.Inst(nm, tys.stream().map(tt -> tt.toType(env)).collect(Collectors.toList()));
        }
    }

    record TyVar(String nm, Ty low, Ty up) implements Ty {

        @Override
        public String toString() {
            return (!low.equals(Ty.none()) ? low + "<:" : "") + Color.yellow(nm) + (!up.equals(Ty.any()) ? "<:" + up : "");
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof TyVar t
                    ? nm.equals(t.nm()) && low.equals(t.low()) && up.equals(t.up()) : false;
        }

        @Override
        public Ty fixVars() {
            return new TyVar(nm, low.fixVars(), up.fixVars());
        }

        @Override
        public Generator.Type toType(List<Generator.Bound> env) {
            var vne_stream = env.reversed().stream();
            var maybe = vne_stream.filter(b -> b.nm().equals(nm)).findFirst();
            if (maybe.isPresent()) {
                return new Generator.Var(maybe.get());
            }
            throw new RuntimeException("Variable " + nm + " not found in environment");
        }
    }

    record TyCon(String nm) implements Ty {

        @Override
        public String toString() {
            return nm;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof TyCon t
                    ? nm.equals(t.nm()) : false;
        }

        @Override
        public Ty fixVars() {
            return this;
        }

        @Override

        public Generator.Type toType(List<Generator.Bound> env) {
            return new Generator.Con(nm);
        }
    }

    record TyTuple(List<Ty> tys) implements Ty {

        @Override
        public String toString() {
            return "(" + tys.stream().map(Ty::toString).collect(Collectors.joining(",")) + ")";
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof TyTuple t
                    ? tys.equals(t.tys()) : false;
        }

        @Override
        public Ty fixVars() {
            return new TyTuple(tys.stream().map(Ty::fixVars).collect(Collectors.toList()));
        }

        @Override
        public Generator.Type toType(List<Generator.Bound> env) {
            return new Generator.Tuple(tys.stream().map(tt -> tt.toType(env)).collect(Collectors.toList()));
        }
    }

    record TyUnion(List<Ty> tys) implements Ty {

        @Override
        public String toString() {
            return "[" + tys.stream().map(Ty::toString).collect(Collectors.joining("|")) + "]";
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof TyUnion t ? tys.equals(t.tys()) : false;
        }

        @Override
        public Ty fixVars() {
            return new TyUnion(tys.stream().map(Ty::fixVars).collect(Collectors.toList()));
        }

        @Override
        public Generator.Type toType(List<Generator.Bound> env) {
            return new Generator.Union(tys.stream().map(tt -> tt.toType(env)).collect(Collectors.toList()));
        }
    }

    record TyDecl(String nm, Ty ty, Ty parent, String src) {

        @Override
        public String toString() {
            return nm + " = " + ty + " <: " + parent + Color.green("\n# " + src);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof TyDecl t
                    ? nm.equals(t.nm()) && ty.equals(t.ty()) && parent.equals(t.parent()) : false;
        }

        public TyDecl fixVars() {
            return new TyDecl(nm, ty.fixVars(), parent.fixVars(), src);
        }

    }

    record TySig(String nm, Ty ty, String src) {

        @Override
        public String toString() {
            return "function " + nm + " " + ty + Color.green("\n# " + src);
        }

        public TySig fixVars() {
            return new TySig(nm, ty.fixVars(), src);
        }
    }

    record TyExist(Ty v, Ty ty) implements Ty {

        @Override
        public String toString() {
            return Color.red("∃") + v + Color.red(".") + ty;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof TyExist t
                    ? v.equals(t.v()) && ty.equals(t.ty()) : false;
        }

        @Override
        public Ty fixVars() {
            var maybeVar = v();
            var body = ty();
            if (maybeVar instanceof TyVar defVar) {
                var tv = new TyVar(defVar.nm(), defVar.low(), defVar.up());
                return new TyExist(tv, body.fixVars());
            } else if (maybeVar instanceof TyInst inst && inst.tys().isEmpty()) {
                if (pre_tydb.containsKey(inst.toString())) {
                    return body.fixVars();
                } else {
                    var tv = new TyVar(inst.nm(), Ty.none(), Ty.any());
                    return new TyExist(tv, body.fixVars());
                }
            } else if (maybeVar instanceof TyInst inst) {
                return new TyInst(inst.nm(), inst.tys().stream().map(Ty::fixVars).collect(Collectors.toList()));
            } else {
                return body.fixVars();
            }
        }

        static int gen = 0;

        @Override
        public Generator.Type toType(List<Generator.Bound> env) {
            String name;
            Generator.Type low;
            Generator.Type up;
            if (v() instanceof TyVar tvar) {
                name = tvar.nm();
                name = name.equals("???") ? "t" + gen++ : name;
                low = tvar.low().toType(env);
                up = tvar.up().toType(env);
            } else {
                var inst = (TyInst) v();
                if (!inst.tys().isEmpty()) {
                    throw new RuntimeException("Should be a TyVar but is a type: " + ty);
                }
                name = inst.nm();
                low = Generator.none;
                up = Generator.any;
            }
            var b = new Generator.Bound(name, low, up);
            var newenv = new ArrayList<>(env);
            newenv.add(b);
            return new Generator.Exist(b, ty().toType(newenv));
        }
    }

}

class Color {

    static String red = "\u001B[31m";
    static String resetText = "\u001B[0m"; // ANSI escape code to reset text color
    static String green = "\u001B[32m";
    static String yellow = "\u001B[33m";

    static String red(String s) {
        return red + s + resetText;
    }

    static String yellow(String s) {
        return yellow + s + resetText;
    }

    static String green(String s) {
        return green + s + resetText;
    }
}
