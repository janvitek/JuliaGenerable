package prlprg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import prlprg.Parser.TypeDeclaration;
import prlprg.Generator.InhNode;
import prlprg.Generator.Decl;

class GenDB {

    static class Types {

        private HashMap<String, Info> db = new HashMap<>();
        HashSet<String> reusedNames = new HashSet<>(); // names of types that are reused
        List<String> allTypes;

        class Info {

            String nm;
            TypeDeclaration parsed;
            TyDecl pre_patched;
            TyDecl patched;
            Generator.Decl decl;
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

        void addDecl(Decl decl) {
            getOrMake(decl.nm()).decl = decl;
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
            if (App.NO_CLOSURES && tyd.mod.contains("closure")) {
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
    }

    static class Signatures {

        class Info {

            String nm;
            TySig pre_patched;
            TySig patched;
            Parser.Function parsed;
            Generator.Sig sig;
        }

        private HashMap<String, List<Info>> db = new HashMap<>();

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

    static Types types = new Types();
    static Signatures sigs = new Signatures();

    static final void addSig(TySig sig) {
        if (App.NO_CLOSURES && sig.nm.startsWith("var#")) {
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
                        + CodeColors.comment("Failed at " + types.getPrePatched(name).src));
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
                case "Nothing":
                    return Ty.None;
                default:
                    if (types.getPrePatched(nm) == null) {
                        System.err.println("Warning: " + nm + " not found in type database, patching");
                        var miss = new TyInst(nm, List.of());
                        types.addPrePatched(new TyDecl("missing", nm, miss, Ty.any(), "(missing)"));
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
