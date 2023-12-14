package prlprg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

class GenDB {

    final private static HashMap<String, TyDecl> pre_tydb = new HashMap<>(); // staging area for types and functions
    final private static HashMap<String, List<TySig>> pre_sigdb = new HashMap<>(); // they are not fully formed yet

    HashMap<String, TyDecl> tydb; // types and functions, fully formed
    HashMap<String, List<TySig>> sigdb;
    HashSet<String> reusedNames = new HashSet<>(); // names of types that are reused

    final void addTyDecl(TyDecl ty) {
        if (App.NO_CLOSURES && ty.mod.contains("closure")) {
            return;
        }
        if (pre_tydb.containsKey(ty.nm())) {
            reusedNames.add(ty.nm()); // remember we have seen this type before and overwrite it
        }
        pre_tydb.put(ty.nm(), ty);
    }

    final void addSig(TySig sig) {
        if (App.NO_CLOSURES && sig.nm.startsWith("var#")) {
            return;
        }
        var e = pre_sigdb.get(sig.nm());
        if (e == null) {
            pre_sigdb.put(sig.nm(), e = new ArrayList<>());
        }
        e.add(sig);
    }

    public void cleanUp() {
        tydb = new HashMap<>();
        sigdb = new HashMap<>();
        // Definitions in the pre DB are ill-formed, as we don't know what identifiers refer to types or
        // variables. We asume all types declarations have been processed, so anything not in tydb is
        // is either a variable or a missing type.
        for (var name : new ArrayList<>(pre_tydb.keySet())) {
            try {
                tydb.put(name, pre_tydb.get(name).fixUp());
            } catch (Exception e) {
                System.err.println("Error: " + name + " " + e.getMessage() + "\n"
                        + CodeColors.comment("Failed at " + pre_tydb.get(name).src));
            }
        }
        if (!reusedNames.isEmpty()) {
            System.out.println("Warning: types defined more than once:" + reusedNames.stream().collect(Collectors.joining(", ")));
            reusedNames.clear();
        }
        for (var name : pre_sigdb.keySet()) {
            var entry = sigdb.get(name);
            if (entry == null) {
                sigdb.put(name, entry = new ArrayList<>());
            }
            for (var sig : pre_sigdb.get(name)) {
                entry.add(sig.fixUp(new ArrayList<>()));
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
            var varOrNull = bounds.stream().filter(v -> v.nm().equals(nm())).findFirst();
            if (varOrNull.isPresent()) {
                if (tys.isEmpty()) {
                    return varOrNull.get();
                }
                throw new RuntimeException("Type " + nm() + " is a variable, but is used as a type");
            }
            switch (nm()) {
                case "typeof": // typeof is a special case
                    return new TyCon(toString());
                // TODO: Core.TypeofBottom and Nothing are different!
                // case "Nothing":
                //     return Ty.None;
                default:
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
            return bounds.stream().anyMatch(b -> b.nm().equals(tv.nm()));
        }

        @Override
        public Generator.Type toType(List<Generator.Bound> env) {
            return new Generator.Inst(nm, tys.stream().map(tt -> tt.toType(env)).collect(Collectors.toList()));
        }

    }

    record TyVar(String nm, Ty low, Ty up) implements Ty {

        @Override
        public String toString() {
            return (!low.equals(Ty.none()) ? low + "<:" : "") + CodeColors.variable(nm) + (!up.equals(Ty.any()) ? "<:" + up : "");
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
            return CodeColors.exists("∃") + v + CodeColors.exists(".") + ty;
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
            return nm + " = " + mod + " " + ty + " <: " + parent + CodeColors.comment("\n# " + src);
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
            for (var arg : fixedArgs.reversed()) {
                t = new TyExist(arg, t);
            }
            return new TyDecl(mod, nm, t, fixedRHS, src);
        }

    }

    record TySig(String nm, Ty ty, String src) {

        @Override
        public String toString() {
            return "function " + nm + " " + ty + CodeColors.comment("\n# " + src);

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
    static String lightGreen = "\u001B[32m";
    static String lightYellow = "\u001B[33m";
    // ANSI escape codes for text colors in dark mode
    static String darkRed = "\u001B[91m";
    static String ResetText = "\u001B[0m";
    static String darkGreen = "\u001B[92m";
    static String darkYellow = "\u001B[93m";
    static String BLUE = "\u001B[34m";
    static String MAGENTA = "\u001B[35m";
    static String CYAN = "\u001B[36m";
    static String LIGHT_WHITE = "\u001B[37m";
    static String BRIGHT_YELLOW = "\u001B[93m";
    static String BRIGHT_MAGENTA = "\u001B[95m";
    static String BRIGHT_CYAN = "\u001B[96m";

    // Default mode (light mode)
    static enum Mode {
        LIGHT, DARK, NONE
    }

    static Mode mode = Mode.LIGHT;

    // Helper method to get the appropriate ANSI escape code based on the current mode
    static String getTextColor(String color) {
        return switch (mode) {
            case LIGHT ->
                ""; // not implemented
            case DARK ->
                switch (color) {
                    case "LightRed" ->
                        lightRed;
                    case "LightGreen" ->
                        lightGreen;
                    case "LightYellow" ->
                        lightYellow;
                    case "Red" ->
                        darkRed;
                    case "Green" ->
                        darkGreen;
                    case "Yellow" ->
                        darkYellow;
                    case "reset" ->
                        ResetText;
                    case "Blue" ->
                        BLUE;
                    case "Magenta" ->
                        MAGENTA;
                    case "Cyan" ->
                        CYAN;
                    case "LightWhite" ->
                        LIGHT_WHITE;
                    case "BrightYellow" ->
                        BRIGHT_YELLOW;
                    case "BrightMagenta" ->
                        BRIGHT_MAGENTA;
                    case "BrightCyan" ->
                        BRIGHT_CYAN;
                    default ->
                        "";
                };
            default ->
                "";
        };
    }

    static String comment(String s) {
        return color(s, "LightWhite");
    }

    static String variable(String s) {
        return color(s, "Cyan");
    }

    static String abstractType(String s) {
        return color(s, "Green");
    }

    static String concreteType(String s) {
        return color(s, "reset");
    }

    static String exists(String s) {
        return color(s, "Red");
    }

    static String tuple(String s) {
        return color(s, "Yellow");
    }

    static String union(String s) {
        return color(s, "Cyan");
    }

    static String color(String s, String color) {
        return getTextColor(color) + s + getTextColor("reset");
    }
}
