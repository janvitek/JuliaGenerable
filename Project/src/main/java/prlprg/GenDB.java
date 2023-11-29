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
            try {
                var t = t0.fixUp();
                tydb.put(name, t);
            } catch (Exception e) {
                System.err.println("Error: " + name + " " + e.getMessage());
                System.err.println(CodeColors.variables("Failed at " + t0.src));
            }
        }

        for (var name : pre_sigdb.keySet()) {
            if (!sigdb.containsKey(name)) {
                sigdb.put(name, new ArrayList<>());
            }
            for (var sig : pre_sigdb.get(name)) {
                sigdb.get(name).add(sig.fixUp(new ArrayList<>()));
            }
        }
        // add patched types if any
        for (var name : pre_tydb.keySet()) {
            if (!tydb.containsKey(name)) {
                tydb.put(name, pre_tydb.get(name)); // no need to fixVars because this is a patched type
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

        Generator.Type toType(List<Generator.Bound> env);

        Ty fixUp(List<TyVar> bounds);

    }

    record TyInst(String nm, List<Ty> tys) implements Ty {

        @Override
        public String toString() {
            var args = tys.stream().map(Ty::toString).collect(Collectors.joining(","));
            return nm + (tys.isEmpty() ? "" : "{" + args + "}");
        }

        @Override
        public Ty fixUp(List<TyVar> bounds) {
            if (nm().equals("Val")) {
                return new TyCon(this.toString());
            } else if (nm().startsWith("typeof(")) { // typeof is a special case
                return new TyCon(nm());
            } else if (nm().equals("Nothing")) {
                return Ty.None;
            } else {
                for (var v : bounds) {
                    if (v.nm().equals(nm())) {
                        if (tys.isEmpty()) {
                            return v;
                        } else {
                            throw new RuntimeException("Type " + nm() + " is a variable, but is used as a type");
                        }
                    }
                }
                if (!pre_tydb.containsKey(nm)) {
                    System.err.println("Warning: " + nm + " not found in type database, patching");
                    var miss = new TyInst(nm, List.of());
                    pre_tydb.put(nm, new TyDecl("missing", nm, miss, Ty.any(), "(missing)"));
                }
                var args = new ArrayList<Ty>();
                var vars = new ArrayList<TyVar>();
                var newBounds = new ArrayList<TyVar>(bounds);
                for (var a : tys) {
                    if (a instanceof TyVar tv && !inList(tv, bounds)) {
                        tv = (TyVar) tv.fixUp(bounds);
                        newBounds.add(tv);
                        vars.add(tv);
                        args.add(tv);
                    } else {
                        args.add(a.fixUp(newBounds));
                    }
                }
                Ty t = new TyInst(nm, args);
                for (var v : vars) {
                    t = new TyExist(v, t);
                }
                return t;
            }
        }

        private boolean inList(TyVar tv, List<TyVar> bounds) {
            for (var b : bounds) {
                if (b.nm().equals(tv.nm())) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public Generator.Type toType(List<Generator.Bound> env
        ) {
            return new Generator.Inst(nm, tys.stream().map(tt -> tt.toType(env)).collect(Collectors.toList()));
        }

    }

    record TyVar(String nm, Ty low, Ty up) implements Ty {

        @Override
        public String toString() {
            return (!low.equals(Ty.none()) ? low + "<:" : "") + CodeColors.light(nm) + (!up.equals(Ty.any()) ? "<:" + up : "");
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
        public Ty fixUp(List<TyVar> bounds) {
            return new TyVar(nm, low.fixUp(bounds), up.fixUp(bounds));
        }

    }

    record TyCon(String nm) implements Ty {

        @Override
        public String toString() {
            return nm;
        }

        @Override

        public Generator.Type toType(List<Generator.Bound> env) {
            return new Generator.Con(nm);
        }

        @Override
        public Ty fixUp(List<TyVar> bounds) {
            return this;
        }
    }

    record TyTuple(List<Ty> tys) implements Ty {

        @Override
        public String toString() {
            return "(" + tys.stream().map(Ty::toString).collect(Collectors.joining(",")) + ")";
        }

        @Override
        public Generator.Type toType(List<Generator.Bound> env) {
            return new Generator.Tuple(tys.stream().map(tt -> tt.toType(env)).collect(Collectors.toList()));
        }

        @Override
        public Ty fixUp(List<TyVar> bounds) {
            return new TyTuple(tys.stream().map(t -> t.fixUp(bounds)).collect(Collectors.toList()));
        }

    }

    record TyUnion(List<Ty> tys) implements Ty {

        @Override
        public String toString() {
            return "[" + tys.stream().map(Ty::toString).collect(Collectors.joining("|")) + "]";
        }

        @Override
        public Generator.Type toType(List<Generator.Bound> env) {
            return new Generator.Union(tys.stream().map(tt -> tt.toType(env)).collect(Collectors.toList()));
        }

        @Override
        public Ty fixUp(List<TyVar> bounds) {
            return new TyUnion(tys.stream().map(t -> t.fixUp(bounds)).collect(Collectors.toList()));
        }

    }

    record TyExist(Ty v, Ty ty) implements Ty {

        @Override
        public String toString() {
            return CodeColors.standout("∃") + v + CodeColors.standout(".") + ty;
        }

        @Override
        public Ty fixUp(List<TyVar> bound) {
            var maybeVar = v();
            var body = ty();
            if (maybeVar instanceof TyVar defVar) {
                var tv = new TyVar(defVar.nm(), defVar.low().fixUp(bound), defVar.up().fixUp(bound));
                var newBound = new ArrayList<>(bound);
                newBound.add(tv);
                return new TyExist(tv, body.fixUp(newBound));
            } else if (maybeVar instanceof TyInst inst) {
                assert inst.tys().isEmpty();
                var tv = new TyVar(inst.nm(), Ty.none(), Ty.any());
                var newBound = new ArrayList<>(bound);
                newBound.add(tv);
                return new TyExist(tv, body.fixUp(newBound));
            } else if (maybeVar instanceof TyTuple) {
                // struct RAICode.QueryEvaluator.Vectorized.Operators.var\"#21#22\"{var\"#10063#T\", var\"#10064#vars\", var_types, *, var\"#10065#target\", Tuple} <: Function end (from module RAICode.QueryEvaluator.Vectorized.Operators)
                throw new RuntimeException("Should be a TyVar or a TyInst with no arguments: got tuple ");
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

    }

    record TyDecl(String mod, String nm, Ty ty, Ty parent, String src) {

        @Override
        public String toString() {
            return nm + " = " + mod + " " + ty + " <: " + parent + CodeColors.variables("\n# " + src);
        }

        TyDecl fixUp() {
            var lhs = (TyInst) ty;
            var rhs = (TyInst) parent;
            var fixedArgs = new ArrayList<TyVar>();
            for (var targ : lhs.tys) {
                switch (targ) {
                    case TyVar tv ->
                        fixedArgs.add((TyVar) tv.fixUp(fixedArgs));
                    case TyInst ti -> {
                        if (!ti.tys.isEmpty()) {
                            throw new RuntimeException("Should be a TyVar but is a type: " + targ);
                        }
                        fixedArgs.add(new TyVar(ti.nm(), Ty.none(), Ty.any()));
                    }
                    default ->
                        throw new RuntimeException("Should be a TyVar or a TyInst with no arguments: " + targ);
                }
            }
            var fixedRHS = (TyInst) rhs.fixUp(fixedArgs);
            var args = new ArrayList<Ty>();
            args.addAll(fixedArgs);
            Ty t = new TyInst(lhs.nm(), args);
            for (var arg : fixedArgs) {
                t = new TyExist(arg, t);
            }
            return new TyDecl(mod, nm, t, fixedRHS, src);
        }

    }

    record TySig(String nm, Ty ty, String src) {

        @Override
        public String toString() {
            return "function " + nm + " " + ty + CodeColors.variables("\n# " + src);

        }

        TySig fixUp(List<TyVar> bounds) {
            return new TySig(nm, ty.fixUp(bounds), src);
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
