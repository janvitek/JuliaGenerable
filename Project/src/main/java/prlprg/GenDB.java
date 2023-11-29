package prlprg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

class GenDB {

    final private static HashMap<String, TyDecl> pre_tydb = new HashMap<>();
    final private static HashMap<String, List<TySig>> pre_sigdb = new HashMap<>();

    HashMap<String, TyDecl> tydb;
    HashMap<String, List<TySig>> sigdb;

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
        tydb = new HashMap<>();
        sigdb = new HashMap<>();
        // At this point the definitions in the DB are ill-formed, as we don't
        // know what identifiers refer to types and what identifiers refer to
        // variables. We asume that we have seen all types declarations, so
        // anything that is not in tydb is a variable.
        // We try to clean up types by removing obvious variables.
        var keys = new ArrayList<>(pre_tydb.keySet());
        for (var name : keys) {
            var t0 = pre_tydb.get(name);
            var t = t0.fixVars();
            var f = t.freeVars();
            if (!f.isEmpty()) {
                System.out.println("Patching missing types " + t.freeVars() + " from " + t);
                for (var v : f) {
                    var missty = new TyInst(v, List.of()); // toString works because we only have variables
                    pre_tydb.put(v, new TyDecl(v, missty, Ty.any(), "(missing)"));
                }
                t = t0.fixVars(); // re run with the new types
            }
            assert t.freeVars().isEmpty();
            var m = t.missingTyInst();
            if (!m.isEmpty()) {
                System.out.println("Patching missing parametric types " + m + " from " + t);
                for (var v : m) {
                    var missty = new TyInst(v, List.of()); // toString works because we only have variables
                    pre_tydb.put(v, new TyDecl(v, missty, Ty.any(), "(missing)"));
                }
                t = t0.fixVars();
            }
            tydb.put(name, t);
        }
        // add patched types if any
        for (var name : pre_tydb.keySet()) {
            if (!tydb.containsKey(name)) {
                tydb.put(name, pre_tydb.get(name)); // no need to fixVars because this is a patched type
            }
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

        List<String> freeVars(List<String> bounds);

        List<String> missingTyInst();

    }

    record TyInst(String nm, List<Ty> tys) implements Ty {

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
            if (nm().equals("Val")) {
                return new TyCon(this.toString());
            } else if (nm().startsWith("typeof(")) { // typeof is a special case
                return new TyCon(nm());
            } else if (nm().equals("Nothing")) {
                return Ty.None;
            } else if (tys.isEmpty() && !pre_tydb.containsKey(nm)) {
                return new TyVar(nm, Ty.none(), Ty.any());
            } else {
                return new TyInst(nm, tys.stream().map(Ty::fixVars).collect(Collectors.toList()));
            }
        }

        @Override
        public Generator.Type toType(List<Generator.Bound> env) {
            List<TyVar> vars = tys.stream().filter(t -> t instanceof TyVar).map(t -> (TyVar) t).collect(Collectors.toList());
            if (vars.isEmpty()) {
                return new Generator.Inst(nm, tys.stream().map(tt -> tt.toType(env)).collect(Collectors.toList()));
            }
            var tys2 = tys.stream().map(tt -> unvar(tt)).collect(Collectors.toList());
            Ty ty = new TyInst(nm, tys2);
            for (var v : vars) {
                ty = new TyExist(v, ty);
            }
            return ty.toType(env);
        }

        Ty unvar(Ty ty) {
            if (ty instanceof TyVar tvar) {
                return new TyInst(tvar.nm(), List.of());
            } else {
                return ty;
            }
        }

        @Override
        public List<String> freeVars(List<String> bounds) {
            return tys.stream().flatMap(t -> t.freeVars(bounds).stream()).collect(Collectors.toList());
        }

        @Override
        public List<String> missingTyInst() {
            var miss = tys.stream().flatMap(t -> t.missingTyInst().stream()).collect(Collectors.toList());
            if (!pre_tydb.containsKey(nm)) {
                miss.add(nm);
            }
            return miss;
        }
    }

    record TyVar(String nm, Ty low, Ty up) implements Ty {

        @Override
        public String toString() {
            return (!low.equals(Ty.none()) ? low + "<:" : "") + CodeColors.light(nm) + (!up.equals(Ty.any()) ? "<:" + up : "");
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

        @Override
        public List<String> freeVars(List<String> bounds) {
            var ls = low.freeVars(bounds);
            var us = up.freeVars(bounds);
            var combined = new ArrayList<>(ls);
            combined.addAll(us);
            if (!bounds.contains(nm)) {
                combined.add(nm);
            }
            return combined;
        }

        @Override
        public List<String> missingTyInst() {
            var mup = this.up.missingTyInst();
            var mlow = this.low.missingTyInst();
            mlow.addAll(mup);
            return mlow;
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

        @Override
        public List<String> freeVars(List<String> bounds) {
            return List.of();
        }

        @Override
        public List<String> missingTyInst() {
            return List.of();
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

        @Override
        public List<String> freeVars(List<String> bounds) {
            return tys.stream().flatMap(t -> t.freeVars(bounds).stream()).collect(Collectors.toList());
        }

        @Override
        public List<String> missingTyInst() {
            return tys.stream().flatMap(t -> t.missingTyInst().stream()).collect(Collectors.toList());
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

        @Override
        public List<String> freeVars(List<String> bounds) {
            return tys.stream().flatMap(t -> t.freeVars(bounds).stream()).collect(Collectors.toList());
        }

        @Override
        public List<String> missingTyInst() {
            return tys.stream().flatMap(t -> t.missingTyInst().stream()).collect(Collectors.toList());
        }
    }

    record TyExist(Ty v, Ty ty) implements Ty {

        @Override
        public String toString() {
            return CodeColors.standout("∃") + v + CodeColors.standout(".") + ty;
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
            } else if (maybeVar instanceof TyInst inst) {
                assert inst.tys().isEmpty();
                if (pre_tydb.containsKey(inst.nm())) {
                    return body.fixVars(); // Somehow we got a type name in a list of variables. Funky.
                }
                var tv = new TyVar(inst.nm(), Ty.none(), Ty.any());
                return new TyExist(tv, body.fixVars());
            } else if (maybeVar instanceof TyTuple) {
                // struct RAICode.QueryEvaluator.Vectorized.Operators.var\"#21#22\"{var\"#10063#T\", var\"#10064#vars\", var_types, *, var\"#10065#target\", Tuple} <: Function end (from module RAICode.QueryEvaluator.Vectorized.Operators)
                // TODO: the symbol * is treated as a type...
                return body.fixVars();
            } else {
                throw new RuntimeException("Should be a TyVar or a TyInst with no arguments: " + maybeVar);
            }
        }

        @Override
        public Generator.Type toType(List<Generator.Bound> env) {
            String name;
            Generator.Type low;
            Generator.Type up;
            if (v() instanceof TyVar tvar) {
                name = tvar.nm();
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

        @Override
        public List<String> freeVars(List<String> bounds) {
            var newBounds = new ArrayList<>(bounds);
            var vname = ((TyVar) v()).nm();
            newBounds.add(vname);
            return ty().freeVars(newBounds);
        }

        @Override
        public List<String> missingTyInst() {
            var mty = ty().missingTyInst();
            mty.addAll(v().missingTyInst());
            return mty;
        }
    }

    record TyDecl(String nm, Ty ty, Ty parent, String src) {

        @Override
        public String toString() {
            return nm + " = " + ty + " <: " + parent + CodeColors.variables("\n# " + src);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof TyDecl t
                    ? nm.equals(t.nm()) && ty.equals(t.ty()) && parent.equals(t.parent()) : false;
        }

        public TyDecl fixVars() {
            return new TyDecl(nm, ty.fixVars(), parent.fixVars(), src);
        }

        public List<String> getBounds() {
            var bounds = new ArrayList<String>();
            var typ = ty();
            while (typ instanceof TyExist e) {
                var v = (TyVar) e.v();
                bounds.add(v.nm());
                typ = e.ty();
            }
            return bounds;
        }

        List<String> freeVars() {
            var bs = getBounds();
            var frees = ty.freeVars(List.of());
            var parentFrees = parent.freeVars(bs);
            frees.addAll(parentFrees);
            return frees;
        }

        List<String> missingTyInst() {
            var miss = ty.missingTyInst();
            miss.addAll(parent.missingTyInst());
            return miss;
        }
    }

    record TySig(String nm, Ty ty, String src) {

        @Override
        public String toString() {
            return "function " + nm + " " + ty + CodeColors.variables("\n# " + src);

        }

        public TySig fixVars() {
            return new TySig(nm, ty.fixVars(), src);
        }

    }

}

class CodeColors {
    // TODO: Change colors for the light mode (I, Jan, dont' use it)

    // ANSI escape codes for text colors in light mode
    static String lightRed = "\u001B[31m";
    static String lightResetText = "\u001B[0m";
    static String lightGreen = "\u001B[32m";
    static String lightYellow = "\u001B[33m";

    // ANSI escape codes for text colors in dark mode
    static String darkRed = "\u001B[91m";
    static String darkResetText = "\u001B[0m";
    static String darkGreen = "\u001B[92m";
    static String darkYellow = "\u001B[93m";

    // Default mode (light mode)
    static enum Mode {
        LIGHT,
        DARK, NONE
    }

    static Mode mode = Mode.LIGHT;

    // Helper method to get the appropriate ANSI escape code based on the current mode
    static String getTextColor(String color) {
        return switch (mode) {
            case DARK ->
                switch (color) {
                    case "red" ->
                        darkRed;
                    case "reset" ->
                        darkResetText;
                    case "green" ->
                        darkGreen;
                    case "yellow" ->
                        darkYellow;
                    default ->
                        "";
                };
            case LIGHT ->
                switch (color) {
                    case "red" ->
                        lightRed;
                    case "reset" ->
                        lightResetText;
                    case "green" ->
                        lightGreen;
                    case "yellow" ->
                        lightYellow;
                    default ->
                        "";
                };
            default ->
                "";
        };
    }

    static String standout(String s) {
        return getTextColor("red") + s + getTextColor("reset");
    }

    static String light(String s) {
        return getTextColor("yellow") + s + getTextColor("reset");
    }

    static String variables(String s) {
        return getTextColor("green") + s + getTextColor("reset");
    }
}
